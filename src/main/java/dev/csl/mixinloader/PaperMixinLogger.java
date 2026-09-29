package dev.csl.mixinloader;

import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.Level;

public class PaperMixinLogger implements ILogger {

	private final String name;

	public PaperMixinLogger(final String name) {
		this.name = name;
	}

	@Override
	public String getId() {
		return this.name;
	}

	@Override
	public String getType() {
		return "MixinLoader";
	}

	@Override
	public void catching(final Level level, final Throwable t) {
		log(level, "Catching: " + t.getMessage(), t);
	}

	@Override
	public void catching(final Throwable t) {
		catching(Level.WARN, t);
	}

	@Override
	public void debug(final String message, final Object... params) {
		if (Boolean.getBoolean("mixin.debug.verbose")) {
			System.out.println("[DEBUG] [" + name + "] " + format(message, params));
		}
	}

	@Override
	public void debug(final String message, final Throwable t) {
		debug(message, ((Object)t));
	}

	@Override
	public void error(final String message, final Object... params) {
		System.err.println("[ERROR] [" + name + "] " + format(message, params));
	}

	@Override
	public void error(final String message, final Throwable t) {
		error(message, ((Object)t));
	}

	@Override
	public void fatal(final String message, final Object... params) {
		System.err.println("[FATAL] [" + name + "] " + format(message, params));
	}

	@Override
	public void fatal(final String message, final Throwable t) {
		fatal(message, ((Object)t));
	}

	@Override
	public void info(final String message, final Object... params) {
		System.out.println("[INFO] [" + name + "] " + format(message, params));
	}

	@Override
	public void info(final String message, final Throwable t) {
		info(message, ((Object)t));
	}

	@Override
	public void log(final Level level, final String message, final Object... params) {
		switch (level) {
			case DEBUG, TRACE -> debug(message, params);
			case INFO -> info(message, params);
			case WARN -> warn(message, params);
			case ERROR -> error(message, params);
			case FATAL -> fatal(message, params);
		}
	}

	@Override
	public void log(final Level level, final String message, final Throwable t) {
		log(level, message, ((Object)t));
	}

	@Override
	public <T extends Throwable> T throwing(final T t) {
		error("Throwing: " + t.getMessage(), t);
		return t;
	}

	@Override
	public void trace(final String message, final Object... params) {
		if (Boolean.getBoolean("mixin.debug.verbose")) {
			System.out.println("[TRACE] [" + name + "] " + format(message, params));
		}
	}

	@Override
	public void trace(final String message, final Throwable t) {
		trace(message, ((Object)t));
	}

	@Override
	public void warn(final String message, final Object... params) {
		System.err.println("[WARN] [" + name + "] " + format(message, params));
	}

	@Override
	public void warn(final String message, final Throwable t) {
		warn(message, ((Object)t));
	}

	private String format(final String message, final Object... params) {
		if (params == null || params.length == 0) {
			return message;
		}
		// Simple {} replacement
		String result = message;
		for (final Object param : params) {
			final int idx = result.indexOf("{}");
			if (idx >= 0) {
				result = result.substring(0, idx) + param + result.substring(idx + 2);
			} else {
				break;
			}
		}
		return result;
	}
}
