# MixinLoader

MixinLoader starts [SpongePowered Mixin](https://github.com/SpongePowered/Mixin), [MixinExtras](https://github.com/LlamaLad7/MixinExtras), and Access Wideners on a PaperMC server. It installs like a normal plugin. You do not edit your start script.

## Overview

A Paper server loads classes through several classloaders. Paperclip loads the server code, and each plugin runs in its own classloader. Because of that split, a Mixin in one plugin cannot see server classes or other plugin classes.

MixinLoader removes that barrier. It adds itself to the JVM as a Java agent. It records the package of every plugin with its owning classloader. Failed class lookups go to the loader that owns the class. The Mixin system then starts before the server completes startup.

## Features

- **No startup changes.** The plugin relaunches the JVM with itself as a `-javaagent`. You do not touch `start.sh` or `start.bat`.
- **Loads plugins from other Mixin loaders.** Mixin plugins built for Ignite, Horizon, and Origami work. See Supported and unsupported.
- **Works with both plugin systems.** The loader instruments the legacy `org.bukkit.plugin.java.PluginClassLoader` and the modern `io.papermc.paper.plugin.provider.classloader.PaperPluginClassLoader`.
- **Access Wideners.** A plugin can ship widener files in its JAR, and the loader applies them without extra setup.
- **MixinExtras included.** The loader starts MixinExtras automatically, so advanced Mixin features work.
- **No extra JVM flags.** The agent opens the needed `java.base` packages at runtime with `Instrumentation.redefineModule`.

## Requirements

- A PaperMC server. MixinLoader ships a `plugin.yml` (Bukkit path, API 1.13) and a `paper-plugin.yml` (Paper path, API 1.19).
- Java 17 or later.
- Linux, Windows, or macOS.

## Install

1. Download the newest `MixinLoader-<version>.jar` from the Releases page.
2. Copy the JAR into the `plugins/` folder of your server.
3. Start the server with your usual command:

    ```bash
    java -jar server.jar
    ```

The server restarts once during startup. This is normal. The plugin sees that the JVM has no agent attached. It closes the listening sockets of the server, relaunches the JVM with `-javaagent`, and exits.

The close step matters. If the old JVM kept the port, the new JVM would fail with `java.net.BindException: Address already in use`.

To skip the restart, attach the agent yourself:

```bash
java -javaagent:plugins/MixinLoader-<version>.jar -jar server.jar
```

The agent sets `mixinloader.loaded=true` before the plugin loads. The plugin checks that property and skips the relaunch.

## Plugin formats

The loader scans every JAR in `plugins/` and reads the Mixin config of each plugin:

| Plugin made for | Mixin configs | Access wideners |
| --- | --- | --- |
| Paper or Bukkit | `mixins` list in `paper-plugin.yml` or `plugin.yml` | entries in the mixin JSON files |
| Ignite | `mixins` array in `ignite.mod.json` | `wideners` array in `ignite.mod.json` |
| Horizon | `mixins` array in `horizon.plugin.json` | `wideners` array in `horizon.plugin.json` |
| Origami | every `*.mixins.json` file | every `*.aw`, `*.accesswidener`, or `*.at` file |

## Supported and unsupported

MixinLoader supports Mixin injection, MixinExtras extensions, Access Wideners, and class routing. A plugin that uses only these tools works.

MixinLoader does not implement the public or private APIs of Ignite, Horizon, or Origami. It does not supply their custom utility classes, lifecycle events, or API methods. A plugin that calls those APIs fails to load.

## How it works

### Bootstrap

`plugin.yml` and `paper-plugin.yml` point to `MixinLegacyBootstrapper` and `MixinModernBootstrapper`. Both call `MixinBootstrapperIsolator`. The isolator loads `MixinBootstrapper` in a small child `URLClassLoader` with no parent. The plugin classloader state stays out of the bootstrap.

`MixinBootstrapper.checkBootAndLoadMixinLoader` then reads the `mixinloader.loaded` property. If the property is missing, it closes the listening sockets. It relaunches the JVM with `-javaagent` set to the plugin JAR. Each OS closes sockets in its own way:

- **Linux.** The bootstrapper reads `/proc/self/fd` and closes each open socket through native `libc` calls. It then calls `execvp`, which replaces the running process, so no orphan stays behind.
- **Windows.** The bootstrapper scans the process handles with `WinSock2` and tests each one with `getsockopt` and `SO_ACCEPTCONN`. It closes the listening sockets, then starts the new JVM with `ProcessBuilder`.
- **macOS.** The bootstrapper lists file descriptors and socket info with `SystemB` and `proc_pidinfo`. It closes the listening sockets, then starts the new JVM with `ProcessBuilder`.

### Agent

`MixinLoader.premain` runs in the relaunched JVM:

1. It opens `jdk.internal.loader`, `java.net`, `java.lang`, and `java.util` from `java.base` to the agent module. It uses `Instrumentation.redefineModule` for this.
2. It defines `java.util.NamespaceRegistry` inside `java.base`. The build compiles that class against a patched `java.base`, and the JAR ships it at `META-INF/mixinloader/java/util/NamespaceRegistry.class`. Because the class lives in `java.base`, every classloader can call it without reflection.
3. ByteBuddy adds a call to `MixinLoader.register` at the end of each plugin loader constructor. `register` scans the JAR URLs of the loader. It records each package with its owning classloader in the registry.
4. ByteBuddy rewrites `findClass` in every classloader. When a loader cannot find a class, injected code asks the registry for it instead of throwing. A Mixin that targets a class of another plugin now resolves.
5. The agent starts the Mixin system: `MixinBootstrap.init()`, the `MixinTransformer` class file transformer, MixinExtras, the plugin scan, then the `INIT` and `DEFAULT` phases.

## Build from source

```bash
git clone https://github.com/Chest-Solutions/MixinLoader.git
cd MixinLoader
./gradlew build
```

Use JDK 17 or later. The shadow JAR lands in `build/libs/`. After R8 minimizes the JAR, one build step replaces the `register` method with the compiler output. R8 rewrites Advice methods during minimization, and the patch restores the correct code.

## Credits

The project bundles or uses:

- [SpongePowered Mixin](https://github.com/SpongePowered/Mixin): the Mixin framework.
- [MixinExtras](https://github.com/LlamaLad7/MixinExtras) by LlamaLad7: Mixin extensions.
- [ByteBuddy](https://bytebuddy.net/): runtime bytecode instrumentation.
- [reflection-remapper](https://github.com/jpenilla/reflectionremapper) by jpenilla: reflection proxies.
- [JNA](https://github.com/java-native-access/jna): OS-level socket and process calls.
- [Access Widener](https://github.com/FabricMC/access-widener): the widener file format.
- [PaperMC](https://papermc.io/): the server software and Paperclip.

## License

MIT. See [LICENSE](LICENSE).
