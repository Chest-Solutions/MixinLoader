package dev.csl.mixinloader.service;

import static dev.csl.mixinloader.Proxies.PAPERCLIP_CLASS;

import com.google.auto.service.AutoService;
import dev.csl.mixinloader.MixinBootstrapper;
import dev.csl.mixinloader.MixinLoader;
import dev.csl.mixinloader.MixinTransformer;
import dev.csl.mixinloader.PaperMixinLogger;
import dev.csl.mixinloader.util.ClassLoaderUtils;
import dev.csl.mixinloader.util.SneakyExceptions;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.*;
import org.spongepowered.asm.util.ReEntranceLock;

@AutoService(IMixinService.class)
public class PaperMixinService implements IMixinService, IClassProvider, IClassBytecodeProvider {
	public static final URLClassLoader minecraftClassLoader;
	public static final URLClassLoader extraClassLoader = new URLClassLoader(new URL[0], ClassLoader.getSystemClassLoader());

	static {
		URLClassLoader tempMinecraftClassLoader;
		try {
			tempMinecraftClassLoader = new URLClassLoader(
				PAPERCLIP_CLASS.executeSetupClasspath(),
				MixinLoader.class.getClassLoader()
			);
		} catch (final Throwable ignored) {
			try {
				//tempMinecraftClassLoader = new URLClassLoader(new URL[]{PAPERCLIP_CLASS.executeSetupEnv().toUri().toURL()}, MixinLoader.class.getClassLoader());
				throw new Throwable();
			} catch (final Throwable ignored2) {
				tempMinecraftClassLoader = SneakyExceptions.get(() ->
					new URLClassLoader(new URL[] {
						MixinBootstrapper.getArgs().jar().toUri().toURL(),
					})
				);
			}
		}
		minecraftClassLoader = tempMinecraftClassLoader;
	}

	private final ReEntranceLock lock = new ReEntranceLock(1);

	@Override public String getName() { return "MixinLoader"; }
	@Override public boolean isValid() { return true; }
	@Override public void prepare() {}
	@Override public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.PREINIT; }
	@Override public void offer(final IMixinInternal internal) { if (internal instanceof IMixinTransformerFactory) MixinTransformer.setTransformerFactory((IMixinTransformerFactory) internal); }
	@Override public void init() {}
	@Override public void beginPhase() {}
	@Override public void checkEnv(final Object bootSource) {}
	@Override public ReEntranceLock getReEntranceLock() { return this.lock; }
	@Override public IClassProvider getClassProvider() { return this; }
	@Override public IClassBytecodeProvider getBytecodeProvider() { return this; }
	@Override public ITransformerProvider getTransformerProvider() { return null; }
	@Override public IClassTracker getClassTracker() { return null; }
	@Override public IMixinAuditTrail getAuditTrail() { return null; }
	@Override public IFeatureValidator getFeatureValidator() { return null; }
	@Override public IAdviceProvider getAdviceProvider() { return null; }
	@Override public Collection<String> getPlatformAgents() { return Collections.emptyList(); }
	@Override public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual(this.getName()); }
	@Override public Collection<IContainerHandle> getMixinContainers() { return Collections.emptyList(); }

	@Override
	public InputStream getResourceAsStream(final String name) {
		InputStream is = extraClassLoader.getResourceAsStream(name);
		if (is != null) return is;
		is = minecraftClassLoader.getResourceAsStream(name);
		if (is != null) return is;
		final ClassLoader ctx = Thread.currentThread().getContextClassLoader();
		if (ctx != null && ctx != minecraftClassLoader) return ctx.getResourceAsStream(name);
		return null;
	}

	@Override public String getSideName() { return "SERVER"; }

	@Override
	public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
		final int classVersion = Integer.parseInt(System.getProperty("java.class.version").split("\\.")[0]);
		final MixinEnvironment.CompatibilityLevel level = MixinEnvironment.CompatibilityLevel.forClassVersion(classVersion);

		return level != null ? level : getMaxCompatibilityLevel();
	}

	@Override
	public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
		final int classVersion = Integer.parseInt(System.getProperty("java.class.version").split("\\.")[0]);
		final MixinEnvironment.CompatibilityLevel level = MixinEnvironment.CompatibilityLevel.forClassVersion(classVersion);
		final MixinEnvironment.CompatibilityLevel maxLevel = MixinEnvironment.CompatibilityLevel.getMaxEffective();

		if (level == null || level.canSupport(maxLevel)) return maxLevel;
		return level;
	}

	@Override public ILogger getLogger(final String name) { return new PaperMixinLogger(name); }
	@Override public URL[] getClassPath() { return ClassLoaderUtils.getURLClassPath(getClass().getClassLoader()).getURLs(); }

	@Override
	public Class<?> findClass(final String name) throws ClassNotFoundException {
		try { return Class.forName(name, false, extraClassLoader); } catch (final ClassNotFoundException ignored) {}
		try { return Class.forName(name, false, getClass().getClassLoader()); } catch (final ClassNotFoundException ignored) {}
		try { return Class.forName(name, false, minecraftClassLoader); } catch (final ClassNotFoundException ignored) {}

		final ClassLoader ctx = Thread.currentThread().getContextClassLoader();
		if (ctx != null) { return Class.forName(name, false, ctx); }

		throw new ClassNotFoundException(name);
	}

	@Override
	public Class<?> findClass(final String name, final boolean initialize) throws ClassNotFoundException {
		try { return Class.forName(name, initialize, extraClassLoader); } catch (final ClassNotFoundException ignored) {}
		try { return Class.forName(name, initialize, getClass().getClassLoader()); } catch (final ClassNotFoundException ignored) {}
		try { return Class.forName(name, initialize, minecraftClassLoader); } catch (final ClassNotFoundException ignored) {}

		final ClassLoader ctx = Thread.currentThread().getContextClassLoader();
		if (ctx != null) { return Class.forName(name, initialize, ctx); }

		throw new ClassNotFoundException(name);
	}

	@Override public Class<?> findAgentClass(final String name, final boolean initialize) throws ClassNotFoundException { return findClass(name, initialize); }

	@Override
	public ClassNode getClassNode(final String name, final boolean runTransformers, final int readerFlags) throws ClassNotFoundException, IOException {
		final String path = name.replace('.', '/') + ".class";
		try (InputStream in = getResourceAsStream(path)) {
			if (in == null) throw new ClassNotFoundException(name);
			final byte[] bytes = in.readAllBytes();
			final ClassReader reader = new ClassReader(bytes);
			final ClassNode node = new ClassNode();
			reader.accept(node, readerFlags);
			return node;
		}
	}

	@Override public ClassNode getClassNode(final String name, final boolean runTransformers) throws ClassNotFoundException, IOException { return getClassNode(name, runTransformers, 0); }
	@Override public ClassNode getClassNode(final String name) throws ClassNotFoundException, IOException { return getClassNode(name, true, 0); }
}
