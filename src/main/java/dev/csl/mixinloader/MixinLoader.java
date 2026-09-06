package dev.csl.mixinloader;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import dev.csl.mixinloader.service.PaperMixinService;
import dev.csl.mixinloader.util.ClassLoaderUtils;
import dev.csl.mixinloader.util.SneakyExceptions;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.pool.TypePool;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

import static dev.csl.mixinloader.Proxies.MIXIN_ENVIRONMENT;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class MixinLoader {
	private static Instrumentation INSTRUMENTATION;
	public static final Map<String, ClassLoader> NAMESPACE_REGISTRY = new ConcurrentHashMap<>();

	private static MethodHandle loadClassMH;

	@SuppressWarnings({"unchecked", "RedundantSuppression"})
	public static void premain(String args, @NotNull Instrumentation inst) {
		System.setProperty("mixinloader.loaded", "true");
		System.setProperty("origami.agent.loaded", "true");
		INSTRUMENTATION = inst;

		openInternals(
				MixinLoader.class.getModule(),
				"jdk.internal.loader",
				"java.net",
				"java.lang"
		);

		try {
			loadClassMH = MethodHandles.privateLookupIn(ClassLoader.class, MethodHandles.lookup())
					.findVirtual(ClassLoader.class, "loadClass", MethodType.methodType(Class.class, String.class, boolean.class));
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}

		new AgentBuilder.Default()
				.disableClassFormatChanges()
				.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
				.type(named("io.papermc.paperclip.Paperclip"))
				.transform((b, type, cl, module, pd) ->
						b.visit(MemberSubstitution.relaxed()
										// Target the constructor call: new URLClassLoader(URL[], ClassLoader)
										.constructor(takesArguments(URL[].class, ClassLoader.class))
										// Replace it with our factory method
										.replaceWith(Objects.requireNonNull(SneakyExceptions.get(() ->
												MixinLoader.class.getMethod("createAndCaptureClassLoader", URL[].class, ClassLoader.class)
										)))
										.on(named("main"))
								)
				)
				.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
				.installOn(inst);

		new AgentBuilder.Default()
				.disableClassFormatChanges()
				.type(named("org.bukkit.plugin.java.PluginClassLoader"))
				.transform((builder, typeDescription, classLoader, module, protectionDomain) ->
						builder.visit(new PostSuperVisitor()))
				.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
				.installOn(inst);

		new AgentBuilder.Default()
				.disableClassFormatChanges()
				.type(
						nameStartsWith("io.papermc.paper.plugin.entrypoint.classloader.PaperPluginClassLoader")
								.or(nameStartsWith("io.papermc.paper.plugin.provider.classloader.PaperPluginClassLoader"))
				)
				.transform((b, type, cl, module, pd) ->
						b.visit(Advice.to(MixinLoader.class)
								.on(isConstructor()))
				)
				.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
				.installOn(inst);

		new AgentBuilder.Default()
				.disableClassFormatChanges()
				.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
				.type(is(URLClassLoader.class) // Catches base Java URLClassLoader
						.or(named("org.bukkit.plugin.java.PluginClassLoader")) // Catches Bukkit/Spigot/Paper (Legacy)
						.or(named("io.papermc.paper.plugin.entrypoint.classloader.PaperPluginClassLoader")) // Catches modern Paper
						.or(is(RoutingServerClassLoader.class)) // Catches your custom loader
				)
				.transform((builder, typeDescription, classLoader, module, protectionDomain) ->
						builder.visit(Advice.to(LoadClassRouterAdvice.class)
								.on(named("loadClass").and(takesArguments(String.class, boolean.class))))
				)
				.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
				.installOn(inst);

		SneakyExceptions.run(() ->
				ClassLoaderUtils.addURL(
						PaperMixinService.extraClassLoader,
                        MixinLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI().toURL()
				)
		);

		boolean addedSnakeYaml = false;
		boolean addedGson = false;

		for (URL url : PaperMixinService.minecraftClassLoader.getURLs()) {
			String str = url.toString();
			boolean isYaml = !addedSnakeYaml && str.contains("snakeyaml");
			boolean isGson = !addedGson && str.contains("gson");

			if (isYaml || isGson) {
				inst.appendToSystemClassLoaderSearch(SneakyExceptions.get(() ->
						new JarFile(new File(url.toURI()))));

				if (isYaml) addedSnakeYaml = true;
				if (isGson) addedGson = true;
				if (addedSnakeYaml && addedGson) break;
			}
		}

		MixinBootstrap.init();
		inst.addTransformer(new MixinTransformer(), true);
		MIXIN_ENVIRONMENT.executeGotoPhase(MixinEnvironment.Phase.INIT);
		MixinExtrasBootstrap.init();
		PluginProber.probePluginsAndAddConfigs();
		MIXIN_ENVIRONMENT.executeGotoPhase(MixinEnvironment.Phase.DEFAULT);
	}

	@Advice.OnMethodExit
	@SuppressWarnings("unused")
	public static void register(@Advice.This URLClassLoader loader) {
		if (loader == null) return;
		try {
			scanAndRegisterPluginJar(loader);
		} catch (Throwable t) {
			System.err.println("[MixinLoader] Failed to register plugin classloader ID: " + t);
		}
	}

	public static void scanAndRegisterPluginJar(URLClassLoader loader) {
		try {
			URL[] urls = loader.getURLs();

			for (URL jarUrl : urls) {
				File jarFile;

				if ("jar".equals(jarUrl.getProtocol())) {
					String path = jarUrl.getPath();
					int bang = path.indexOf("!/");
					if (bang != -1) path = path.substring(0, bang);
					jarFile = new File(new URL(path).toURI());
				} else {
					jarFile = new File(jarUrl.toURI());
				}

				if (!jarFile.exists() || !jarFile.isFile()) continue;
				try (JarFile jar = new JarFile(jarFile)) {
					jar.stream().forEach(entry -> {
						String name = entry.getName();
						if (name.endsWith(".class") && name.contains("/")) {
							String pkg = name.substring(0, name.lastIndexOf('/')).replace('/', '.');
							if (pkg.contains(".")) {
								NAMESPACE_REGISTRY.put(pkg, loader);
							}
						}
					});
				}
			}
		} catch (Throwable t) {
			System.err.println("[MixinLoader] Failed to scan plugin namespaces: " + t);
		}
	}

	public static URLClassLoader createAndCaptureClassLoader(URL[] urls, ClassLoader parent) {
		URLClassLoader loader = new RoutingServerClassLoader(urls, parent);
		openInternals(loader.getUnnamedModule(), "jdk.internal.loader", "java.net");
		return loader;
	}

	public static void openInternals(Module targetModule, String... packages) {
		if (INSTRUMENTATION == null || targetModule == null) return;

		Module javaBase = Object.class.getModule();
		Map<String, Set<Module>> extraOpens = new HashMap<>();
		Map<String, Set<Module>> extraExports = new HashMap<>();

		for (String pkg : packages) {
			extraOpens.put(pkg, Set.of(targetModule));
			extraExports.put(pkg, Set.of(targetModule));
		}

		INSTRUMENTATION.redefineModule(
				javaBase,
				Set.of(),
				extraExports,
				extraOpens,
				Set.of(),
				Map.of()
		);
	}

	public static final class RoutingServerClassLoader extends URLClassLoader {
		private static final ThreadLocal<Set<String>> IN_FLIGHT = ThreadLocal.withInitial(HashSet::new);

        public RoutingServerClassLoader(URL[] urls, ClassLoader parent) {
			super(urls, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> clazz = findLoadedClass(name);
				if (clazz != null) {
					return clazz;
				}

				Set<String> inFlight = IN_FLIGHT.get();
				if (inFlight.add(name)) {
					try {
						try {
							return super.loadClass(name, resolve);
						} catch (ClassNotFoundException ignored) {}

						int lastDot = name.lastIndexOf('.');
						if (lastDot > 0) {
							String pkg = name.substring(0, lastDot);
							ClassLoader targetLoader = NAMESPACE_REGISTRY.get(pkg);
							if (targetLoader != null && targetLoader != this) {
								try {
									clazz = (Class<?>) loadClassMH.invoke(targetLoader, name, resolve);
									if (clazz != null) {
										return clazz;
									}
								} catch (Throwable ignored) {}
							}
						}
					} finally {
						inFlight.remove(name);
					}
				}
				throw new ClassNotFoundException(name);
			}
		}
	}

	@SuppressWarnings({"unused", "UnusedAssignment"})
    public static final class LoadClassRouterAdvice {
		@Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
		public static Class<?> onEnter(
				@Advice.This ClassLoader self,
				@Advice.Argument(0) String name,
				@Advice.Argument(1) boolean resolve
		) {
			if (name != null && !name.startsWith("io.papermc.paperclip")) {
				try {
					Class<?> clazz = (Class<?>) loadClassMH.invoke(ClassLoader.getSystemClassLoader(), name, resolve);
					if (clazz != null) return clazz;
				} catch (Throwable ignored) {}
			}

			return null;
		}

		@Advice.OnMethodExit
		public static void onExit(
				@Advice.Return(readOnly = false) Class<?> result,
				@Advice.Enter Class<?> routedClass
		) {
			if (routedClass != null && result == null) {
				result = routedClass;
			}
		}
	}

	public static class PostSuperVisitor extends AsmVisitorWrapper.AbstractBase {
		@Override
		public @NotNull ClassVisitor wrap(
                @NotNull TypeDescription instrumentedType,
                @NotNull ClassVisitor classVisitor,
                @NotNull Implementation.Context implementationContext,
                @NotNull TypePool typePool,
                @NotNull FieldList<FieldDescription.InDefinedShape> fields,
                @NotNull MethodList<?> methods,
                int writerFlags,
                int readerFlags
		) {
			return new ClassVisitor(Opcodes.ASM9, classVisitor) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

					if (!name.equals("<init>")) {
						return mv;
					}

					return new MethodVisitor(Opcodes.ASM9, mv) {
						private boolean injected = false;

						@Override
						public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean isInterface) {
							boolean isClassNewInstance = (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/Class") && methodName.equals("newInstance"));
							boolean isConstructorNewInstance = (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/reflect/Constructor") && methodName.equals("newInstance"));

							if (!injected && (isClassNewInstance || isConstructorNewInstance)) {
								injected = true;
								super.visitVarInsn(Opcodes.ALOAD, 0);
								super.visitMethodInsn(
										Opcodes.INVOKESTATIC,
										"dev/csl/mixinloader/MixinLoader",
										"register",
										"(Ljava/net/URLClassLoader;)V",
										false
								);
							}
							super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
						}
					};
				}
			};
		}
	}
}
