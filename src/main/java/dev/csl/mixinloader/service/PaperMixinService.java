package dev.csl.mixinloader.service;

import com.google.auto.service.AutoService;
import dev.csl.mixinloader.MixinBootstrapper;
import dev.csl.mixinloader.MixinLoader;
import dev.csl.mixinloader.MixinTransformer;
import dev.csl.mixinloader.PaperMixinLogger;
import dev.csl.mixinloader.util.SneakyExceptions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.*;
import org.spongepowered.asm.util.ReEntranceLock;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;

import static dev.csl.mixinloader.Proxies.PAPERCLIP_CLASS;
@AutoService(IMixinService.class)
public class PaperMixinService implements IMixinService, IClassProvider, IClassBytecodeProvider {
	public static final URLClassLoader minecraftClassLoader;
	public static final URLClassLoader extraClassLoader = new URLClassLoader(new URL[0], ClassLoader.getSystemClassLoader());

	static {
		URLClassLoader tempMinecraftClassLoader;
		try {
			tempMinecraftClassLoader = new URLClassLoader(PAPERCLIP_CLASS.executeSetupClasspath(), MixinLoader.class.getClassLoader());
		} catch (Throwable ignored) {
			try {
				//tempMinecraftClassLoader = new URLClassLoader(new URL[]{PAPERCLIP_CLASS.executeSetupEnv().toUri().toURL()}, MixinLoader.class.getClassLoader());
				throw new Throwable();
			} catch (Throwable ignored2) {
				tempMinecraftClassLoader = SneakyExceptions.get(() ->
						new URLClassLoader(new URL[]{MixinBootstrapper.getArgs().jar().toUri().toURL()}));
			}
		}
		minecraftClassLoader = tempMinecraftClassLoader;
	}

	private final ReEntranceLock lock = new ReEntranceLock(1);

	@Override
	public String getName() {
		return "MixinLoader";
	}

	@Override
	public boolean isValid() {
		return true;
	}

	@Override
	public void prepare() {
	}

	@Override
	public MixinEnvironment.Phase getInitialPhase() {
		return MixinEnvironment.Phase.PREINIT;
	}

	@Override
	public void offer(IMixinInternal internal) {
		if (internal instanceof IMixinTransformerFactory) {
			MixinTransformer.setTransformerFactory((IMixinTransformerFactory) internal);
		}
	}

	@Override
	public void init() {
	}

	@Override
	public void beginPhase() {
	}

	@Override
	public void checkEnv(Object bootSource) {
	}

	@Override
	public ReEntranceLock getReEntranceLock() {
		return this.lock;
	}

	@Override
	public IClassProvider getClassProvider() {
		return this;
	}

	@Override
	public IClassBytecodeProvider getBytecodeProvider() {
		return this;
	}

	@Override
	public ITransformerProvider getTransformerProvider() {
		return null;
	}

	@Override
	public IClassTracker getClassTracker() {
		return null;
	}

	@Override
	public IMixinAuditTrail getAuditTrail() {
		return null;
	}

	@Override
	public IFeatureValidator getFeatureValidator() {
		return null;
	}

	@Override
	public IAdviceProvider getAdviceProvider() {
		return null;
	}

	@Override
	public Collection<String> getPlatformAgents() {
		return Collections.emptyList();
	}

	@Override
	public IContainerHandle getPrimaryContainer() {
		return new ContainerHandleVirtual(this.getName());
	}

	@Override
	public Collection<IContainerHandle> getMixinContainers() {
		return Collections.emptyList();
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		// 1. Extra classloader (plugin JARs)
		InputStream is = extraClassLoader.getResourceAsStream(name);
		if (is != null) return is;

		// 2. Minecraft classloader
		is = minecraftClassLoader.getResourceAsStream(name);
		if (is != null) return is;

		// 3. Thread context loader
		ClassLoader ctx = Thread.currentThread().getContextClassLoader();
		if (ctx != null && ctx != minecraftClassLoader) {
			is = ctx.getResourceAsStream(name);
            return is;
		}

		return null;
	}

	@Override
	public String getSideName() {
		return "SERVER";
	}

	@Override
	public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
		int classVersion = Integer.parseInt(System.getProperty("java.class.version").split("\\.")[0]);
		MixinEnvironment.CompatibilityLevel level = MixinEnvironment.CompatibilityLevel.forClassVersion(classVersion);

		// Fallback to the current environment level or highest available if null
		return level != null ? level : getMaxCompatibilityLevel();
	}

	@Override
	public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
		int classVersion = Integer.parseInt(System.getProperty("java.class.version").split("\\.")[0]);
		MixinEnvironment.CompatibilityLevel level = MixinEnvironment.CompatibilityLevel.forClassVersion(classVersion);
		MixinEnvironment.CompatibilityLevel maxLevel = MixinEnvironment.CompatibilityLevel.getMaxEffective();

		if (level == null || level.canSupport(maxLevel)) return maxLevel;
		return level;
	}

	@Override
	public ILogger getLogger(String name) {
		return new PaperMixinLogger(name);
	}

	// IClassProvider

	@Override
	@SuppressWarnings({"deprecation", "RedundantSuppression"})
	public URL[] getClassPath() {
		// In Java 17, we can't easily get the URLs from the System loader.
		// Returning an empty array is usually fine for Agents, as the Instrumentation
		// handles the class discovery.
		return new URL[0];
	}

	@Override
	public Class<?> findClass(String name) throws ClassNotFoundException {
		// 1. Extra classloader (plugin JARs)
        try {
            return Class.forName(name, false, extraClassLoader);
        } catch (ClassNotFoundException ignored) {
        }

        // 2. Agent/Mixin loader
		try {
			return Class.forName(name, false, getClass().getClassLoader());
		} catch (ClassNotFoundException ignored) {}

		// 3. Minecraft classloader
		try {
			return Class.forName(name, false, minecraftClassLoader);
		} catch (ClassNotFoundException ignored) {}

		// 4. Thread context loader
		ClassLoader ctx = Thread.currentThread().getContextClassLoader();
		if (ctx != null) {
			return Class.forName(name, false, ctx);
		}

		throw new ClassNotFoundException(name);
	}

	@Override
	public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        try {
            return Class.forName(name, initialize, extraClassLoader);
        } catch (ClassNotFoundException ignored) {
        }

        try {
			return Class.forName(name, initialize, getClass().getClassLoader());
		} catch (ClassNotFoundException ignored) {}

		try {
			return Class.forName(name, initialize, minecraftClassLoader);
		} catch (ClassNotFoundException ignored) {}

		ClassLoader ctx = Thread.currentThread().getContextClassLoader();
		if (ctx != null) {
			return Class.forName(name, initialize, ctx);
		}

		throw new ClassNotFoundException(name);
	}

	@Override
	public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return findClass(name, initialize);
	}

	// IClassBytecodeProvider

	@Override
	public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
			throws ClassNotFoundException, IOException {
		String path = name.replace('.', '/') + ".class";
		try (InputStream in = getResourceAsStream(path)) {
			if (in == null) {
				throw new ClassNotFoundException(name);
			}
			byte[] bytes = in.readAllBytes();
			ClassReader reader = new ClassReader(bytes);
			ClassNode node = new ClassNode();
			reader.accept(node, readerFlags); // use the flags Mixin asked for!
			return node;
		}
	}

	@Override
	public ClassNode getClassNode(String name, boolean runTransformers)
			throws ClassNotFoundException, IOException {
		return getClassNode(name, runTransformers, 0);
	}

	@Override
	public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
		return getClassNode(name, true, 0);
	}
}
