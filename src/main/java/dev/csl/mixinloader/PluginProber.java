package dev.csl.mixinloader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.csl.mixinloader.service.PaperMixinService;
import dev.csl.mixinloader.util.ClassLoaderUtils;
import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerReader;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixins;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class PluginProber {
	public static final AccessWidener accessWidener = new AccessWidener();

	private static final Gson gson = new Gson();
	private static final Yaml yaml = new Yaml();

	public static void probePluginsAndAddConfigs() {
		for (File pluginFile : getPluginPaths()) {
			if (!pluginFile.exists() || !pluginFile.isFile() || pluginFile.length() == 0) {
				continue;
			}

			try (JarFile jar = new JarFile(pluginFile)) {
				probeJar(jar);
			} catch (IOException e) {
				System.err.println("[MixinLoader] Skipping unreadable jar: " + pluginFile.getName() + " (" + e.getMessage() + ")");
			}
		}
	}

	private static List<File> getPluginPaths() {
		List<File> paths = new ArrayList<>();

		String[] args = ProcessHandle.current().info().arguments()
				.orElse(new String[0]);

		for (int i = 0; i < args.length; i++) {
			String arg = args[i];

			if (arg.startsWith("--add-plugin=") || arg.startsWith("-add-plugin=")) {
				paths.add(new File(arg.substring(arg.indexOf('=') + 1)));
			} else if (arg.equals("--add-plugin") || arg.equals("-add-plugin")) {
				if (i + 1 < args.length) {
					paths.add(new File(args[++i]));
				}
			} else if (arg.startsWith("--add-plugin-dir=") || arg.startsWith("-add-plugin-dir=")) {
				collectJarsFromDirectory(new File(arg.substring(arg.indexOf('=') + 1)), paths);
			} else if (arg.equals("--add-plugin-dir") || arg.equals("-add-plugin-dir")) {
				if (i + 1 < args.length) {
					collectJarsFromDirectory(new File(args[++i]), paths);
				}
			}
		}

		collectJarsFromDirectory(Path.of("plugins").toAbsolutePath().toFile(), paths);
		return paths;
	}

	private static void collectJarsFromDirectory(File dir, List<File> jarFiles) {
		if (!dir.exists() || !dir.isDirectory()) return;

		File[] files = dir.listFiles((d, name) -> name.endsWith(".jar"));
		if (files != null) {
			Collections.addAll(jarFiles, files);
		}
	}

	private static void probeJar(@NotNull JarFile jar) {
		try {
			String modLoader = detectModLoader(jar);
			if (modLoader != null) {
				System.err.println("[MixinLoader] Skipping " + jar.getName() + " — detected as a " + modLoader + " mod, not a Paper mixin plugin.");
				return;
			}

			boolean ignored =
					probeYamlConfig(jar, "paper-plugin.yml") ||
							probeYamlConfig(jar, "plugin.yml") ||
							probeIgnite(jar) ||
							probeHorizon(jar) ||
							probeOrigami(jar);
		} catch (Exception e) {
			System.err.println("[MixinLoader] Error probing " + jar.getName() + ": " + e.getMessage());
		}
	}

	private static String detectModLoader(@NotNull JarFile jar) {
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
	private static boolean probeYamlConfig(@NotNull JarFile jar, String fileName) {
		ZipEntry entry = jar.getEntry(fileName);
		if (entry == null) return false;

		try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
			Map<String, Object> data = yaml.load(reader);
			if (data != null && data.get("mixins") instanceof List<?> mixinList) {
				boolean found = false;
				for (Object config : mixinList) {
					found |= addMixinConfig(jar, config.toString(), true);
				}
				return found;
			}
		} catch (Exception e) {
			System.err.println("[MixinLoader] Error parsing " + fileName + ": " + e.getMessage());
		}
		return false;
	}

	/** Ignite: ignite.mod.json → mixins[] + wideners[] */
	private static boolean probeIgnite(@NotNull JarFile jar) {
		return probeJsonManifest(jar, "ignite.mod.json");
	}

	/** Horizon: horizon.plugin.json → mixins[] + wideners[] */
	private static boolean probeHorizon(@NotNull JarFile jar) {
		return probeJsonManifest(jar, "horizon.plugin.json");
	}

	private static boolean probeJsonManifest(@NotNull JarFile jar, String fileName) {
		ZipEntry entry = jar.getEntry(fileName);
		if (entry == null) return false;

		try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
			JsonObject json = gson.fromJson(reader, JsonObject.class);
			boolean mixins = handleMixinList(jar, json.get("mixins"), false);
			boolean wideners = handleWidenerList(jar, json.get("wideners"));
			return mixins || wideners;
		} catch (Exception e) {
			System.err.println("[MixinLoader] Error parsing " + fileName + ": " + e.getMessage());
			return false;
		}
	}

	/**
	 * Origami: auto-detect every *.mixins.json and every *.aw / *.accesswidener / *.at
	 */
	private static boolean probeOrigami(@NotNull JarFile jar) {
		boolean found = false;
		var entries = jar.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();

			if (name.endsWith(".mixins.json")) {
				found |= addMixinConfig(jar, name, false);
			} else if (isWidenerFile(name)) {
				found |= loadAccessWidener(jar, name);
			}
		}
		return found;
	}

	private static boolean isWidenerFile(String name) {
		return name.endsWith(".accesswidener")
				|| name.endsWith(".aw")
				|| name.endsWith(".at");
	}

	@SuppressWarnings("SameParameterValue")
    private static boolean handleMixinList(@NotNull JarFile jar, JsonElement mixinsElement, boolean fabricStyleWideners) {
		if (mixinsElement == null || !mixinsElement.isJsonArray()) return false;

		boolean found = false;
		for (JsonElement element : mixinsElement.getAsJsonArray()) {
			found |= addMixinConfig(jar, element.getAsString(), fabricStyleWideners);
		}
		return found;
	}

	private static boolean handleWidenerList(@NotNull JarFile jar, JsonElement element) {
		if (element == null || element.isJsonNull()) return false;

		if (element.isJsonPrimitive()) {
			return loadAccessWidener(jar, element.getAsString());
		}

		if (!element.isJsonArray()) return false;

		boolean found = false;
		for (JsonElement item : element.getAsJsonArray()) {
			if (item.isJsonPrimitive()) {
				found |= loadAccessWidener(jar, item.getAsString());
			}
		}
		return found;
	}

	private static boolean addMixinConfig(@NotNull JarFile jar, String configName, boolean fabricStyleWideners) {
		if (configName == null || configName.isEmpty()) return false;

		try {
			ClassLoaderUtils.addURL(PaperMixinService.extraClassLoader, Path.of(jar.getName()).toUri().toURL());

			Mixins.addConfiguration(configName);
			System.out.println("[MixinLoader] Added mixin config: " + configName);
		} catch (Exception e) {
			System.err.println("[MixinLoader] Failed to add config " + configName + ": " + e.getMessage());
			return false;
		}

		if (fabricStyleWideners) {
			probeAccessWidenerFromMixinConfig(jar, configName);
		}
		return true;
	}

	/** Fabric-like: mixin JSON may declare "accessWidener" / "accessWideners". */
	private static void probeAccessWidenerFromMixinConfig(@NotNull JarFile jar, String configName) {
		ZipEntry entry = jar.getEntry(configName);
		if (entry == null) return;

		try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
			JsonObject json = gson.fromJson(reader, JsonObject.class);
			if (json == null) return;
			handleWidenerList(jar, json.get("accessWidener"));
			handleWidenerList(jar, json.get("accessWideners"));
		} catch (Exception e) {
			System.err.println("[MixinLoader] Error reading access widener from mixin config " + configName + ": " + e.getMessage());
		}
	}

	private static boolean loadAccessWidener(@NotNull JarFile jar, String entryName) {
		if (entryName == null || entryName.isEmpty()) return false;

		ZipEntry entry = jar.getEntry(entryName);
		if (entry == null) {
			System.err.println("[MixinLoader] Access widener not found in " + jar.getName() + ": " + entryName);
			return false;
		}

		try (BufferedReader br = new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
			new AccessWidenerReader(accessWidener).read(br);
			System.out.println("[MixinLoader] Loaded Access Widener from " + jar.getName() + ": " + entryName);
			return true;
		} catch (Exception e) {
			System.err.println("[MixinLoader] Failed to read Access Widener: " + entryName + " (" + e.getMessage() + ")");
			return false;
		}
	}
}