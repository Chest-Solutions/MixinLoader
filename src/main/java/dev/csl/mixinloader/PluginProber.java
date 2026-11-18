package dev.csl.mixinloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import dev.csl.mixinloader.util.SneakyExceptions;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixins;
import org.yaml.snakeyaml.Yaml;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.csl.mixinloader.service.PaperMixinService;
import dev.csl.mixinloader.util.ClassLoaderUtils;
import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerReader;

public class PluginProber {
	public static final AccessWidener accessWidener = new AccessWidener();

	private static final Gson gson = new Gson();
	private static final Yaml yaml = new Yaml();

	public static void probePluginsAndAddConfigs() {
		for (final Path pluginFile : getPluginPaths()) {
			//noinspection DataFlowIssue
			if (!Files.exists(pluginFile) || !Files.isRegularFile(pluginFile) || SneakyExceptions.get(() -> Files.size(pluginFile) == 0)) {
				continue;
			}

			probeJar(pluginFile);
		}
	}

	private static List<Path> getPluginPaths() {
		final String[] args = ProcessHandle.current().info().arguments().orElse(new String[0]);
		final Set<Path> paths = new LinkedHashSet<>();   // dedupe across overlapping flags

		// additive single-jar flags (both generations, both dashes)
		for (final String p : flagValues(args, "--add-plugin", "-add-plugin", "--add-extra-plugin-jar")) {
			paths.add(Path.of(p));
		}
		// additive directory flags
		for (final String d : flagValues(args, "--add-plugin-dir", "-add-plugin-dir", "--add-extra-plugin-dir")) {
			collectJarsFromDirectory(Path.of(d), paths);
		}

		// plugin root: -P / --plugins REPLACES the default plugins/ scan (verified live:
		// with -P customplugins, Paper ignored plugins/ entirely)
		final List<String> roots = flagValues(args, "-P", "--plugins");
		collectJarsFromDirectory(Path.of(roots.isEmpty() ? "plugins" : roots.get(0)).toAbsolutePath(), paths);

		return new ArrayList<>(paths);
	}

	/**
	 * Collects every value passed to any of {@code flags}, accepting all forms:
	 * {@code --flag=value}, {@code flag value}, and (for short flags like {@code -P})
	 * {@code flagValue}. Repeated flags accumulate. Handles flag prefix collisions
	 * (e.g. {@code --add-plugin-dir} must not match under {@code --add-plugin}).
	 */
	private static List<String> flagValues(final String[] args, final String... flags) {
		final List<String> values = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			for (final String flag : flags) {
				if (!args[i].startsWith(flag)) continue;
				final String rest = args[i].substring(flag.length());
				if (rest.isEmpty()) {                                  // exact match → space form
					if (i + 1 < args.length) values.add(args[++i]);
				} else if (rest.charAt(0) == '=') {                    // = form
					values.add(rest.substring(1));
				} else if (flag.length() == 2 && !flag.startsWith("--")) { // -Pdir attached form
					values.add(rest);
				}
				// otherwise: longer flag sharing this prefix (e.g. --add-plugin-dir under
				// --add-plugin) — fall through, the real flag matches it on its own turn
			}
		}
		return values;
	}

	private static void collectJarsFromDirectory(final Path dir, final Set<Path> jarFiles) {
		if (!Files.exists(dir) || !Files.isDirectory(dir) || !Files.isReadable(dir)) return;

		Path[] paths = null;
		try (Stream<Path> stream = Files.list(dir)) {
			paths = stream
				.filter(p -> p.toString().endsWith(".jar"))
				.toArray(Path[]::new);
		} catch (IOException ignored) {}
		if (paths != null && paths.length != 0) {
			Collections.addAll(jarFiles, paths);
		}
	}

	private static void probeJar(@NotNull final Path jarPath) {
		try (final JarFile jar = new JarFile(jarPath.toFile())) {
			final String modLoader = detectModLoader(jar);

			final boolean isPaperMixin =
				probeYamlConfig(jar, jarPath, "paper-plugin.yml") ||
					probeYamlConfig(jar, jarPath, "plugin.yml") ||
					probeJsonManifest(jar, jarPath, "ignite.mod.json") ||
					probeJsonManifest(jar, jarPath, "horizon.plugin.json");
			if (!isPaperMixin && modLoader != null) {
				System.err.println("[MixinLoader] Skipping " + jarPath + " — detected as a " + modLoader + " mod, not a Paper mixin plugin.");
				return;
			}
			if (!isPaperMixin) probeOrigami(jar, jarPath);
		} catch (final Exception e) {
			System.err.println("[MixinLoader] Error probing " + jarPath + ": " + e.getMessage());
		}
	}

	private static String detectModLoader(@NotNull final JarFile jar) {
		if (jar.getEntry("fabric.mod.json") != null) return "Fabric";
		if (jar.getEntry("quilt.mod.json") != null) return "Quilt";
		if (jar.getEntry("META-INF/neoforge.mods.toml") != null) return "NeoForge";
		if (jar.getEntry("META-INF/mods.toml") != null) return "Forge";
		return null;
	}

	/**
	 * Our system: mixins listed in paper-plugin.yml / plugin.yml.
	 * Access wideners come from those mixin JSON files (Fabric-like).
	 */
	private static boolean probeYamlConfig(@NotNull final JarFile jar, final Path jarPath, final String fileName) {
		final ZipEntry entry = jar.getEntry(fileName);
		if (entry == null) return false;

		try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
			final Map<String, Object> data = yaml.load(reader);
			if (data != null && data.get("mixins") instanceof final List<?> mixinList) {
				boolean found = false;
				for (final Object config : mixinList) {
					found |= addMixinConfig(jar, jarPath, config.toString(), true);
				}
				return found;
			}
		} catch (final Exception e) {
			System.err.println("[MixinLoader] Error parsing " + fileName + ": " + e.getMessage());
		}
		return false;
	}

	private static boolean probeJsonManifest(@NotNull final JarFile jar, final Path jarPath, final String fileName) {
		final ZipEntry entry = jar.getEntry(fileName);
		if (entry == null) return false;

		try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
			final JsonObject json = gson.fromJson(reader, JsonObject.class);
			final boolean mixins = handleMixinList(jar, jarPath, json.get("mixins"), false);
			final boolean wideners = handleWidenerList(jar, json.get("wideners"));
			return mixins || wideners;
		} catch (final Exception e) {
			System.err.println("[MixinLoader] Error parsing " + fileName + ": " + e.getMessage());
			return false;
		}
	}

	/**
	 * Origami: auto-detect every *.mixins.json and every *.aw / *.accesswidener / *.at
	 */
	private static boolean probeOrigami(@NotNull final JarFile jar, final Path jarPath) {
		boolean found = false;
		final var entries = jar.entries();
		while (entries.hasMoreElements()) {
			final ZipEntry entry = entries.nextElement();
			final String name = entry.getName();

			if (name.endsWith(".mixins.json")) {
				found |= addMixinConfig(jar, jarPath, name, false);
			} else if (isWidenerFile(name)) {
				found |= loadAccessWidener(jar, name);
			}
		}
		return found;
	}

	private static boolean isWidenerFile(final String name) {
		return name.endsWith(".accesswidener")
				|| name.endsWith(".aw")
				|| name.endsWith(".at");
	}

	@SuppressWarnings("SameParameterValue")
    private static boolean handleMixinList(
		@NotNull final JarFile jar,
		final Path jarPath,
		final JsonElement mixinsElement,
		final boolean fabricStyleWideners
	) {
		if (mixinsElement == null || !mixinsElement.isJsonArray()) return false;

		boolean found = false;
		for (final JsonElement element : mixinsElement.getAsJsonArray()) {
			found |= addMixinConfig(jar, jarPath, element.getAsString(), fabricStyleWideners);
		}
		return found;
	}

	private static boolean handleWidenerList(@NotNull final JarFile jar, final JsonElement element) {
		if (element == null || element.isJsonNull()) return false;

		if (element.isJsonPrimitive()) {
			return loadAccessWidener(jar, element.getAsString());
		}

		if (!element.isJsonArray()) return false;

		boolean found = false;
		for (final JsonElement item : element.getAsJsonArray()) {
			if (item.isJsonPrimitive()) {
				found |= loadAccessWidener(jar, item.getAsString());
			}
		}
		return found;
	}

	private static final AtomicLong SYNTHETIC_SEQ = new AtomicLong();

	private static boolean addMixinConfig(
		@NotNull final JarFile jar,
		final Path jarPath,
		final String configName,
		final boolean fabricStyleWideners
	) {
		if (configName == null || configName.isEmpty()) return false;

		String synthetic = null;
		try {
			ClassLoaderUtils.addURL(PaperMixinService.extraClassLoader, jarPath.toUri().toURL());   // class side: unchanged from today

			// Serve this jar's config bytes under a unique name — same-named configs
			// from different jars can never shadow each other.
			final byte[] configBytes = readEntry(jar, configName);
			if (configBytes != null) {
				synthetic = "__ml_" + SYNTHETIC_SEQ.incrementAndGet() + "_" + configName;
				final byte[] rewritten = rewriteRefmap(jar, configBytes);
				PaperMixinService.syntheticMappers.put(synthetic, rewritten != null ? rewritten : configBytes);
			}

			Mixins.addConfiguration(synthetic != null ? synthetic : configName);
			System.out.println("[MixinLoader] Added mixin config: " + configName + (synthetic != null ? " (as " + synthetic + ")" : ""));
		} catch (final Exception e) {
			if (synthetic != null) PaperMixinService.syntheticMappers.remove(synthetic);   // no stale entry behind a closed loader
			System.err.println("[MixinLoader] Failed to add config " + configName + ": " + e.getMessage());
			return false;
		}

		if (fabricStyleWideners) {
			probeAccessWidenerFromMixinConfig(jar, configName);
		}
		return true;
	}

	/** Reads an entry's bytes; null if absent or unreadable. */
	private static byte[] readEntry(@NotNull final JarFile jar, final String entryName) {
		try {
			final ZipEntry entry = jar.getEntry(entryName);
			if (entry == null) return null;
			try (InputStream in = jar.getInputStream(entry)) {
				return in != null ? in.readAllBytes() : null;
			}
		} catch (final IOException e) { return null; }
	}

	/**
	 * If the config declares "refmap", rewrites it to a unique synthetic name and
	 * serves this jar's refmap bytes under that name. Returns the rewritten config
	 * bytes, the original bytes when there is no refmap, or null if unparseable
	 * (caller then falls back to raw bytes).
	 */
	private static byte[] rewriteRefmap(@NotNull final JarFile jar, final byte[] configBytes) {
		try {
			final JsonObject json = gson.fromJson(new String(configBytes, StandardCharsets.UTF_8), JsonObject.class);
			if (json == null || !json.has("refmap") || !json.get("refmap").isJsonPrimitive()) return configBytes;

			final String refmapName = json.get("refmap").getAsString();
			final byte[] refmapBytes = readEntry(jar, refmapName);
			if (refmapBytes == null) return configBytes;   // nothing to serve; keep original name, mixin warns as usual

			final String syntheticRefmap = "__ml_" + SYNTHETIC_SEQ.incrementAndGet() + "_" + refmapName;
			PaperMixinService.syntheticMappers.put(syntheticRefmap, refmapBytes);
			json.addProperty("refmap", syntheticRefmap);
			return gson.toJson(json).getBytes(StandardCharsets.UTF_8);
		} catch (final Exception e) {
			return null;   // caller falls back to raw bytes
		}
	}

	/** Fabric-like: mixin JSON may declare "accessWidener" / "accessWideners". */
	private static void probeAccessWidenerFromMixinConfig(@NotNull final JarFile jar, final String configName) {
		final ZipEntry entry = jar.getEntry(configName);
		if (entry == null) return;

		try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
			final JsonObject json = gson.fromJson(reader, JsonObject.class);
			if (json == null) return;
			handleWidenerList(jar, json.get("accessWidener"));
			handleWidenerList(jar, json.get("accessWideners"));
		} catch (final Exception e) {
			System.err.println("[MixinLoader] Error reading access widener from mixin config " + configName + ": " + e.getMessage());
		}
	}

	private static boolean loadAccessWidener(@NotNull final JarFile jar, final String entryName) {
		if (entryName == null || entryName.isEmpty()) return false;

		final ZipEntry entry = jar.getEntry(entryName);
		if (entry == null) {
			System.err.println("[MixinLoader] Access widener not found in " + jar.getName() + ": " + entryName);
			return false;
		}

		try (BufferedReader br = new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
			new AccessWidenerReader(accessWidener).read(br);
			System.out.println("[MixinLoader] Loaded Access Widener from " + jar.getName() + ": " + entryName);
			return true;
		} catch (final Exception e) {
			System.err.println("[MixinLoader] Failed to read Access Widener: " + entryName + " (" + e.getMessage() + ")");
			return false;
		}
	}
}
