package dev.csl.mixinloader;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import dev.csl.mixinloader.service.PaperMixinService;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.launch.MixinInitialisationError;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;

import net.fabricmc.accesswidener.AccessWidenerClassVisitor;

public class MixinTransformer implements ClassFileTransformer {
	private static IMixinTransformerFactory transformerFactory;
	private static IMixinTransformer transformer;

	public static void setTransformerFactory(final IMixinTransformerFactory factory) {
		transformerFactory = factory;
	}

	public static IMixinTransformer getTransformer() {
		if (transformer == null) {
			if (transformerFactory != null) {
				try {
					transformer = transformerFactory.createTransformer();
				} catch (MixinInitialisationError ignored) {}
			}
		}

		return transformer;
	}

	private static boolean shouldSkip(final String className) {
		return className.startsWith("java/")
				|| className.startsWith("javax/")
				|| className.startsWith("jdk/")
				|| className.startsWith("sun/")
				|| className.startsWith("com/sun/")
				|| className.startsWith("org/spongepowered/")
				|| className.startsWith("org/objectweb/asm/")
				|| className.startsWith("com/google/common/")
				|| className.startsWith("com/google/gson/")
				|| className.startsWith("dev/csl/mixinloader/");
	}

	@Override
	public byte[] transform(final ClassLoader loader, final String className, final Class<?> classBeingRedefined,
	                        final ProtectionDomain protectionDomain, final byte[] classfileBuffer) {

		if (className == null || shouldSkip(className) || CrashReporter.isCrashing()) return null;

		final String dottedName = className.replace('/', '.');

		boolean modified = false;
		byte[] bytes = classfileBuffer;

		try {
			if (PluginProber.accessWidener.getTargets().contains(dottedName)) {
				final ClassReader cr = new ClassReader(bytes);
				final ClassWriter cw = new ClassWriter(cr, 0);
				final ClassVisitor visitor = AccessWidenerClassVisitor.createClassVisitor(
					Opcodes.ASM9, cw, PluginProber.accessWidener
				);
				cr.accept(visitor, 0);

				bytes = cw.toByteArray();
				modified = true;
			}
		} catch (final Throwable t) {
			if (!CrashReporter.crash(loader, PaperMixinService.minecraftClassLoader, "Access widening of " + dottedName + " failed", t)) {
				System.err.println("[MixinLoader] Access widener error for " + dottedName + " (loader=" + loader + "):");
				t.printStackTrace(System.err);
			}
			return null;
		}

		final IMixinTransformer tx = getTransformer();
		if (tx == null) {
			return modified ? bytes : null;
		}

		try {
			final MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();
			final byte[] mixed = tx.transformClass(env, dottedName, bytes);

			if (mixed != null) {
				return mixed;
			}
			return modified ? bytes : null;
		} catch (final Throwable t) {
			if (!CrashReporter.crash(loader, PaperMixinService.minecraftClassLoader, "Mixin transformation of " + dottedName + " failed", t)) {
				System.err.println("[MixinLoader] Transform error for " + dottedName + " (loader=" + loader + "):");
				t.printStackTrace(System.err);
			}
			return modified ? bytes : null;
		}
	}
}
