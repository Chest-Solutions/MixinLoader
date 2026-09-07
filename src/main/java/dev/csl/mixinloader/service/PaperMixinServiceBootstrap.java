package dev.csl.mixinloader.service;

import com.google.auto.service.AutoService;
import org.spongepowered.asm.service.IMixinServiceBootstrap;
@SuppressWarnings("unused")
@AutoService(IMixinServiceBootstrap.class)
public class PaperMixinServiceBootstrap implements IMixinServiceBootstrap {

	@Override
	public String getName() { return "MixinLoader"; }

	@Override
	public String getServiceClassName() { return "dev.csl.mixinloader.service.PaperMixinService"; }

	@Override
	public void bootstrap() {}
}
