package dev.csl.mixinloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CrashReporter {
	private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);
	private static final AtomicBoolean CRASHING = new AtomicBoolean(false);

	public static boolean isCrashing() { return CRASHING.get(); }

	public static boolean crash(final ClassLoader realLoader, final ClassLoader fallbackLoader,
							 final String reason, final Throwable cause) {
		if (!CRASHING.compareAndSet(false, true)) return true;   // one report, no reentry
		try {
			// real loader first (avoids duplicate class loads); fallback only if it can't see the jar
			final ClassLoader cl = canLoad("net.minecraft.CrashReport", false, realLoader) ? realLoader : fallbackLoader;
			final Class<?> crashReport = Class.forName("net.minecraft.CrashReport", false, cl);
			final Class<?> reportType = Class.forName("net.minecraft.ReportType", false, cl);

			final Object report = crashReport
				.getMethod("forThrowable", Throwable.class, String.class)
				.invoke(null, cause, "MixinLoader: " + reason);
			final Object type = reportType.getField("CRASH").get(null);   // record, not an enum; no "SERVER"

			final Path dir = Path.of("").resolve("crash-reports");        // CWD; refine later if you like
			Files.createDirectories(dir);                                 // saveToFile does NOT mkdirs
			final Path file = dir.resolve("crash-" + FMT.format(ZonedDateTime.now()) + "-server.txt");
			crashReport.getMethod("saveToFile", Path.class, reportType).invoke(report, file, type);

			System.err.println(crashReport.getMethod("getFriendlyReport", reportType).invoke(report, type));

			// graceful stop ONLY through the real loader — minecraftClassLoader is a distinct loader
			// instance, its duplicate MinecraftServer.getServer() static is always null
			if (cl == realLoader) {
				final Class<?> server = Class.forName("net.minecraft.server.MinecraftServer", false, cl);
				final Object instance = server.getMethod("getServer").invoke(null);
				if (instance != null) {
					server.getMethod("close").invoke(instance);
					return true;                                               // server stopping; caller returns null
				}
			}
			Runtime.getRuntime().halt(1);   // pre-server (bootstrap) or no real loader: report saved+printed first
			return true;
		} catch (final Throwable ignored) {}
		return false;
	}

	public static boolean canLoad(final String className, final boolean initialize, final ClassLoader loader) {
		try {
			Class.forName(className, initialize, loader);
			return true;
		} catch (Throwable ignored) {}
		return false;
	}
}
