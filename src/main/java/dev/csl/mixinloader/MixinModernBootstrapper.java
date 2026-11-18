package dev.csl.mixinloader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import org.jetbrains.annotations.NotNull;

@SuppressWarnings({"UnstableApiUsage"})
public class MixinModernBootstrapper implements PluginLoader {
    @Override public void classloader(final @NotNull PluginClasspathBuilder classpathBuilder) { MixinBootstrapperIsolator.loadMixinBootstrapperIsolated(); }
}
