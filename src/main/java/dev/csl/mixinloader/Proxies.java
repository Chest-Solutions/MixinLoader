package dev.csl.mixinloader;

import io.papermc.paperclip.Paperclip;
import org.spongepowered.asm.mixin.MixinEnvironment;
import xyz.jpenilla.reflectionremapper.ReflectionRemapper;
import xyz.jpenilla.reflectionremapper.proxy.ReflectionProxyFactory;
import xyz.jpenilla.reflectionremapper.proxy.annotation.MethodName;
import xyz.jpenilla.reflectionremapper.proxy.annotation.Static;

import java.net.URL;

public class Proxies {
	public static final ReflectionRemapper reflectionRemapper = ReflectionRemapper.noop();
	public static final ReflectionProxyFactory reflectionProxyFactory = ReflectionProxyFactory.create(reflectionRemapper, MixinLoader.class.getClassLoader());

	public static final MixinEnvironmentProxy MIXIN_ENVIRONMENT = reflectionProxyFactory.reflectionProxy(MixinEnvironmentProxy.class);
	public static final PaperclipProxy PAPERCLIP_CLASS = reflectionProxyFactory.reflectionProxy(PaperclipProxy.class);

	@xyz.jpenilla.reflectionremapper.proxy.annotation.Proxies(MixinEnvironment.class)
	public interface MixinEnvironmentProxy {
		@MethodName("gotoPhase")
		@Static void executeGotoPhase(MixinEnvironment.Phase phase);
	}

	@xyz.jpenilla.reflectionremapper.proxy.annotation.Proxies(Paperclip.class)
	public interface PaperclipProxy {
		@MethodName("setupClasspath")
		@Static URL[] executeSetupClasspath();
	}
}
