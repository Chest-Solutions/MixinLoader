package dev.csl.mixinloader;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;

public class MixinLegacyBootstrapper extends JavaPlugin {
    @Override
    public void onLoad() {
		URL jarUrl = MixinLegacyBootstrapper.class.getProtectionDomain().getCodeSource().getLocation();

		try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, null)) {
			Class<?> bootstrapperClass = loader.loadClass("dev.csl.mixinloader.MixinBootstrapper");

			MethodType signature = MethodType.methodType(void.class);
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			MethodHandle checkBootAndLoad = lookup.findStatic(
				bootstrapperClass,
				"checkBootAndLoadMixinLoader",
				signature
			);

			ClassLoader originalContextLoader = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(loader);
				checkBootAndLoad.invoke();

			} finally {
				Thread.currentThread().setContextClassLoader(originalContextLoader);
			}

		} catch (IOException e) {
			throw new RuntimeException("Failed to open or close the ClassLoader", e);
		} catch (Throwable t) {
			throw new RuntimeException("Failed to execute mixin bootstrap", t);
		}
    }
}
