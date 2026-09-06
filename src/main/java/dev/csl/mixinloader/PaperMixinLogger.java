package dev.csl.mixinloader;

import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.Level;

public class PaperMixinLogger implements ILogger {

	private final String name;

	public PaperMixinLogger(String name) {
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
	public void catching(Level level, Throwable t) {
		log(level, "Catching: " + t.getMessage(), t);
	}

	@Override
	public void catching(Throwable t) {
		catching(Level.WARN, t);
	}

	@Override
	public void debug(String message, Object... params) {
		if (Boolean.getBoolean("mixin.debug.verbose")) {
			System.out.println("[DEBUG] [" + name + "] " + format(message, params));
		}
	}

	@Override
	public void debug(String message, Throwable t) {
		debug(message, ((Object)t));
	}

	@Override
	public void error(String message, Object... params) {
		System.err.println("[ERROR] [" + name + "] " + format(message, params));
	}

	@Override
	public void error(String message, Throwable t) {
		error(message, ((Object)t));
	}

	@Override
	public void fatal(String message, Object... params) {
		System.err.println("[FATAL] [" + name + "] " + format(message, params));
	}

	@Override
	public void fatal(String message, Throwable t) {
		fatal(message, ((Object)t));
	}

	@Override
	public void info(String message, Object... params) {
		System.out.println("[INFO] [" + name + "] " + format(message, params));
	}

	@Override
	public void info(String message, Throwable t) {
		info(message, ((Object)t));
	}

	@Override
	public void log(Level level, String message, Object... params) {
		switch (level) {
			case DEBUG, TRACE -> debug(message, params);
			case INFO -> info(message, params);
			case WARN -> warn(message, params);
			case ERROR -> error(message, params);
			case FATAL -> fatal(message, params);
		}
	}

	@Override
	public void log(Level level, String message, Throwable t) {
		log(level, message, ((Object)t));
	}

	@Override
	public <T extends Throwable> T throwing(T t) {
		error("Throwing: " + t.getMessage(), t);
		return t;
	}

	@Override
	public void trace(String message, Object... params) {
		if (Boolean.getBoolean("mixin.debug.verbose")) {
			System.out.println("[TRACE] [" + name + "] " + format(message, params));
		}
	}

	@Override
	public void trace(String message, Throwable t) {
		trace(message, ((Object)t));
	}

	@Override
	public void warn(String message, Object... params) {
		System.err.println("[WARN] [" + name + "] " + format(message, params));
	}

	@Override
	public void warn(String message, Throwable t) {
		warn(message, ((Object)t));
	}

	private String format(String message, Object... params) {
		if (params == null || params.length == 0) {
			return message;
		}
		// Simple {} replacement
		String result = message;
		for (Object param : params) {
			int idx = result.indexOf("{}");
			if (idx >= 0) {
				result = result.substring(0, idx) + param + result.substring(idx + 2);
			} else {
				break;
			}
		}
		return result;
	}
}