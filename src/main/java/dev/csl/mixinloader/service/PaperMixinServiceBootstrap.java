package dev.csl.mixinloader.service;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

public class PaperMixinServiceBootstrap implements IMixinServiceBootstrap {

	@Override
	public String getName() { return "MixinLoader"; }

	@Override
	public String getServiceClassName() { return "dev.csl.mixinloader.service.PaperMixinService"; }

	@Override
	public void bootstrap() {}
}