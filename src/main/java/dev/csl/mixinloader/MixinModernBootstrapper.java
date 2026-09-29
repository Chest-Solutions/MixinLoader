package dev.csl.mixinloader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;

@SuppressWarnings({"UnstableApiUsage"})
public class MixinModernBootstrapper implements PluginLoader {
    @Override public void classloader(final PluginClasspathBuilder classpathBuilder) { MixinBootstrapperIsolator.loadMixinBootstrapperIsolated(); }
}
