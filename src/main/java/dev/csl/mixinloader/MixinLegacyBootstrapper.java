package dev.csl.mixinloader;

import org.bukkit.plugin.java.JavaPlugin;

public class MixinLegacyBootstrapper extends JavaPlugin {
    @Override
    public void onLoad() {
        MixinBootstrapper.checkBootAndLoadMixinLoader();
    }
}
