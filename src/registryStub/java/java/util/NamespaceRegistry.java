package java.util;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

public final class NamespaceRegistry {
    public static final Map<String, ClassLoader> NAMESPACE_REGISTRY = new ConcurrentHashMap<>();
    private static final MethodHandle FIND_CLASS, FIND_LOADED_CLASS, GET_CLASS_LOADING_LOCK;

    static { try {
        final Method mFindClass = ClassLoader.class.getDeclaredMethod("findClass", String.class);
        final Method mFindLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
		final Method mGetClassLoadingLock = ClassLoader.class.getDeclaredMethod("getClassLoadingLock", String.class);
        mFindClass.setAccessible(true); mFindLoadedClass.setAccessible(true); mGetClassLoadingLock.setAccessible(true);
        FIND_CLASS = MethodHandles.lookup().unreflect(mFindClass);
        FIND_LOADED_CLASS = MethodHandles.lookup().unreflect(mFindLoadedClass);
		GET_CLASS_LOADING_LOCK = MethodHandles.lookup().unreflect(mGetClassLoadingLock);
    } catch (final Throwable t) { throw new ExceptionInInitializerError(t); } }

	@SuppressWarnings("unused")
	public static void register(final URLClassLoader loader) {
		if (loader == null) return;
		try {
			for (final URL jarUrl : loader.getURLs()) {
				File jarFile;

				if ("jar".equals(jarUrl.getProtocol())) {
					String path = jarUrl.getPath();
					final int bang = path.indexOf("!/");
					if (bang != -1) path = path.substring(0, bang);
					jarFile = new File(new URL(path).toURI());
				} else {
					jarFile = new File(jarUrl.toURI());
				}

				if (!jarFile.exists() || !jarFile.isFile()) continue;
				try (JarFile jar = new JarFile(jarFile)) {
					final boolean isAgentJar = jar.getEntry("dev/csl/mixinloader/MixinLoader.class") != null;
					boolean warnedMixinExtras = false, warnedMixin = false;

					if (isAgentJar) return;

					final var entries = jar.entries();

					while (entries.hasMoreElements()) {
						final var entry = entries.nextElement();
						final String name = entry.getName();

						if (name.endsWith(".class") && name.contains("/")) {
							if (name.startsWith("com/llamalad7/mixinextras")) {
								if (!warnedMixinExtras) {
									warnedMixinExtras = true;
									System.out.println("[MixinLoader] Note: " + jarFile.getName() + " bundles its own MixinExtras, this is not supported and may lead to issues!");
								}
								continue;
							} else if (name.startsWith("org/spongepowered/asm")) {
								if (!warnedMixin) {
									warnedMixin = true;
									System.out.println("[MixinLoader] Note: " + jarFile.getName() + " bundles its own Mixin, this is not supported and may lead to issues!");
								}
								continue;
							}

							final String pkg = name
								.substring(0, name.lastIndexOf('/'))
								.replace('/', '.');

							NAMESPACE_REGISTRY.put(
								pkg,
								loader
							);
						}
					}
				}
			}
		} catch (final Throwable t) {
			System.err.println("[MixinLoader] Failed to register plugin classloader ID: " + t);
		}
	}

    public static Class<?> findClassFromNamespaceRegistry(final ClassLoader requester, final String name) throws ClassNotFoundException {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
			final String pkg = name.substring(0, lastDot);
            final ClassLoader owner = NAMESPACE_REGISTRY.get(pkg);
            if (owner != null && owner != requester) {
                try {
					if (GET_CLASS_LOADING_LOCK != null) {
						synchronized (GET_CLASS_LOADING_LOCK.invoke(owner, name)) {
							if (FIND_LOADED_CLASS != null) {
								final Class<?> loaded = (Class<?>) FIND_LOADED_CLASS.invoke(owner, name);
								if (loaded != null) return loaded;
							}
							if (FIND_CLASS != null) return (Class<?>) FIND_CLASS.invoke(owner, name);
						}
					}
                } catch (final Throwable ignored) {}
            }
        }
		if (name.startsWith("com.llamalad7.mixinextras") || name.startsWith("org.spongepowered.asm")) {
			final ClassLoader agent = ClassLoader.getSystemClassLoader();
			try {
				if (GET_CLASS_LOADING_LOCK != null) {
					synchronized (GET_CLASS_LOADING_LOCK.invoke(agent, name)) {
						if (FIND_LOADED_CLASS != null) {
							final Class<?> loaded = (Class<?>) FIND_LOADED_CLASS.invoke(agent, name);
							if (loaded != null) return loaded;
						}
						if (FIND_CLASS != null) return (Class<?>) FIND_CLASS.invoke(agent, name);
					}
				}
			}
			catch (final Throwable ignored) {}
		}
        throw new ClassNotFoundException(name);
    }
}
