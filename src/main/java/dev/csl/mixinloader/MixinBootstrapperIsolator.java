package dev.csl.mixinloader;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;

class MixinBootstrapperIsolator {
	static void loadMixinBootstrapperIsolated() {
		final ClassLoader originalContextLoader = Thread.currentThread().getContextClassLoader();
		try (URLClassLoader loader = new URLClassLoader(new URL[]{MixinBootstrapperIsolator.class.getProtectionDomain().getCodeSource().getLocation()}, null)) {
			Thread.currentThread().setContextClassLoader(loader);

			final MethodHandle checkBootAndLoad = MethodHandles.publicLookup().findStatic(
				loader.loadClass("dev.csl.mixinloader.MixinBootstrapper"),
				"checkBootAndLoadMixinLoader",
				MethodType.methodType(void.class)
			);

			checkBootAndLoad.invoke();
		} catch (final IOException e) {
			throw new RuntimeException("Failed to open or close the ClassLoader", e);
		} catch (final Throwable t) {
			throw new RuntimeException("Failed to execute mixin bootstrap", t);
		} finally {
			Thread.currentThread().setContextClassLoader(originalContextLoader);
		}
	}
}
