package dev.csl.mixinloader;

import static dev.csl.mixinloader.Proxies.MIXIN_ENVIRONMENT;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameMatches;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NamespaceRegistry;
import java.util.Set;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;

public class MixinLoader {
	/**
	 * TypePool resolution during transformation looks up referenced classes through the
	 * instrumented class's own classloader. Plugin classloaders are plain URLClassLoaders
	 * over plugin jars and cannot see JDK module classes (jrt:), so redefining any
	 * plugin-bundled {@code *ClassLoader} class (e.g. LuckPerms' JarInJarClassLoader)
	 * failed with {@code NoSuchTypeException: Cannot resolve type description for
	 * java.net.URLClassLoader} and the transformation was aborted. The boot loader's
	 * locator reads java.base class files, so it serves as the fallback.
	 */
	private static final AgentBuilder.LocationStrategy JDK_AWARE_POOL =
		AgentBuilder.LocationStrategy.ForClassLoader.WEAK
			.withFallbackTo(ClassFileLocator.ForClassLoader.ofBootLoader());

	public static class PostSuperVisitor extends AsmVisitorWrapper.AbstractBase {
		private static final class ClassVisitorExtension extends ClassVisitor {
			private ClassVisitorExtension(int api, ClassVisitor classVisitor) { super(api, classVisitor); }

			@Override
			public MethodVisitor visitMethod(
				final int access,
				final String name,
				final String descriptor,
				final String signature,
				final String[] exceptions
			) {
				final MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				if (!name.equals("<init>")) return mv;

				return new MethodVisitor(Opcodes.ASM9, mv) {
					private boolean injected = false;

					@Override
					public void visitMethodInsn(
						final int opcode,
						final String owner,
						final String methodName,
						final String methodDesc,
						final boolean isInterface
					) {
						final boolean isClassNewInstance =
							opcode == Opcodes.INVOKEVIRTUAL &&
							owner.equals("java/lang/Class") &&
							methodName.equals("newInstance");
						final boolean isConstructorNewInstance =
							opcode == Opcodes.INVOKEVIRTUAL &&
							owner.equals("java/lang/reflect/Constructor") &&
							methodName.equals("newInstance");

						if (!injected && (isClassNewInstance || isConstructorNewInstance)) {
							injected = true;
							super.visitVarInsn(Opcodes.ALOAD, 0);
							super.visitMethodInsn(
								Opcodes.INVOKESTATIC,
								"java/util/NamespaceRegistry",
								"register",
								"(Ljava/net/URLClassLoader;)V",
								false
							);
						}
						super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
					}
				};
			}
		}

		@Override
		public @NotNull ClassVisitor wrap(
			@NotNull final TypeDescription instrumentedType,
			@NotNull final ClassVisitor classVisitor,
			@NotNull final Implementation.Context implementationContext,
			@NotNull final TypePool typePool,
			@NotNull final FieldList<FieldDescription.InDefinedShape> fields,
			@NotNull final MethodList<?> methods,
			final int writerFlags,
			final int readerFlags
		) { return new ClassVisitorExtension(Opcodes.ASM9, classVisitor); }
	}

	public static final class FindClassMissVisitor extends AsmVisitorWrapper.AbstractBase {
		@Override
		public @NotNull ClassVisitor wrap(
			@NotNull TypeDescription type,
			@NotNull ClassVisitor visitor,
			@NotNull Implementation.Context context,
			@NotNull TypePool typePool,
			@NotNull FieldList<FieldDescription.InDefinedShape> fields,
			@NotNull MethodList<?> methods,
			int writerFlags,
			int readerFlags
		) {
			return new ClassVisitor(Opcodes.ASM9, visitor) {
				@Override
				public MethodVisitor visitMethod(
					int access,
					String name,
					String descriptor,
					String signature,
					String[] exceptions
				) {
					MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

					if (mv == null || !(name.equals("findClass") &&
						descriptor.equals("(Ljava/lang/String;)Ljava/lang/Class;"))) {
						return mv;
					}

					return new MethodVisitor(Opcodes.ASM9, mv) {
						@Override
						public void visitInsn(int opcode) {
							if (opcode != Opcodes.ATHROW) {
								super.visitInsn(opcode);
								return;
							}

							// Stack: [throwable]
							Label fallback = new Label();

							super.visitInsn(Opcodes.DUP);
							super.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/ClassNotFoundException");
							super.visitJumpInsn(Opcodes.IFNE, fallback);

							super.visitInsn(Opcodes.DUP);
							super.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/NoClassDefFoundError");
							super.visitJumpInsn(Opcodes.IFNE, fallback);

							// Neither type: preserve the original throw.
							super.visitInsn(Opcodes.ATHROW);

							super.visitLabel(fallback);
							// Stack: [throwable]
							super.visitInsn(Opcodes.POP);
							super.visitVarInsn(Opcodes.ALOAD, 0);
							super.visitVarInsn(Opcodes.ALOAD, 1);
							super.visitMethodInsn(
								Opcodes.INVOKESTATIC,
								"java/util/NamespaceRegistry",
								"findClassFromNamespaceRegistry",
								"(Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/Class;",
								false
							);
							super.visitInsn(Opcodes.ARETURN);
						}
					};
				}
			};
		}

		@Override public int mergeReader(int flags) { return flags | ClassReader.EXPAND_FRAMES; }
		@Override public int mergeWriter(int flags) { return (flags | ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); }
	}

	private static Instrumentation INSTRUMENTATION;

	public static void premain(final String args, @NotNull final Instrumentation inst) {
		System.setProperty("mixinloader.loaded", "true");
		System.setProperty("origami.agent.loaded", "true");
		INSTRUMENTATION = inst;

		openInternals(
			MixinLoader.class.getModule(),
			"jdk.internal.loader",
			"java.net",
			"java.lang",
			"java.util"
		);

		defineNamespaceRegistry();

		new AgentBuilder.Default()
			.disableClassFormatChanges()
			.with(JDK_AWARE_POOL)
			.type(named("org.bukkit.plugin.java.PluginClassLoader"))
			.transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder.visit(new PostSuperVisitor()))
			.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
			.installOn(inst);

		new AgentBuilder.Default()
			.disableClassFormatChanges()
			.with(JDK_AWARE_POOL)
			.type(
				nameMatches(
					"^io\\.papermc\\.paper\\.plugin\\.(?:entrypoint|provider)\\.classloader\\.Paper(?:Simple)?PluginClassLoader$"
				)
			)
			.transform((b, type, cl, module, pd) -> b.visit(Advice.to(MixinLoader.class).on(isConstructor())))
			.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
			.installOn(inst);

		new AgentBuilder.Default()
			.disableClassFormatChanges()
			.with(JDK_AWARE_POOL)
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.ignore(none())
			.type(nameEndsWith("ClassLoader"))
			.transform((builder, type, loader, module, pd) -> builder.visit(new FindClassMissVisitor()))
			.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
			.installOn(inst);

		MixinBootstrap.init();
		inst.addTransformer(new MixinTransformer(), true);
		MIXIN_ENVIRONMENT.executeGotoPhase(MixinEnvironment.Phase.INIT);
		MixinExtrasBootstrap.init();
		PluginProber.probePluginsAndAddConfigs();
		MIXIN_ENVIRONMENT.executeGotoPhase(MixinEnvironment.Phase.DEFAULT);
	}

	@Advice.OnMethodExit
	public static void register(@Advice.This final URLClassLoader loader) {
		NamespaceRegistry.register(loader);
	}

	public static void openInternals(
		final Module targetModule,
		final String... packages
	) {
		if (INSTRUMENTATION == null || targetModule == null) return;

		final Module javaBase = Object.class.getModule();
		final Map<String, Set<Module>> extraOpens = new HashMap<>();
		final Map<String, Set<Module>> extraExports = new HashMap<>();

		for (final String pkg : packages) {
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

	private static void defineNamespaceRegistry() {
		final String name = "java.util.NamespaceRegistry";
		final String resource = "/META-INF/mixinloader/java/util/NamespaceRegistry.class";

		final byte[] bytes;
		try (InputStream in = MixinLoader.class.getResourceAsStream(resource)) {
			if (in == null) throw new IllegalStateException("Missing " + resource);
			bytes = in.readAllBytes();
		} catch (IOException e) {
			throw new IllegalStateException("Could not read " + resource, e);
		}

		try {
			final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ArrayList.class, MethodHandles.lookup());

			final Class<?> defined = ClassInjector.UsingLookup.of(lookup).injectRaw(Collections.singletonMap(name, bytes)).get(name);

			if (
				defined == null ||
				defined.getClassLoader() != null ||
				defined.getModule() != Object.class.getModule()
			) throw new IllegalStateException("Registry was not defined in java.base: " + defined);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Could not define " + name, e);
		}
	}
}
