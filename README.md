# 🧩 MixinLoader

A robust, drop-in Java plugin designed to bootstrap [SpongePowered Mixin](https://github.com/SpongePowered/Mixin), [MixinExtras](https://github.com/LlamaLad7/MixinExtras), and **Access Wideners** into modern **PaperMC** server environments.

## 📖 Overview

Modern PaperMC servers utilize a highly isolated, multi-layered classloader architecture. Between `Paperclip`'s custom classloading, legacy Bukkit `PluginClassLoader`s, and modern `PaperPluginClassLoader`s, getting a custom Mixin loader to successfully see, target, and inject into server and plugin classes is notoriously difficult.

**MixinLoader** solves this by acting as a self-bootstrapping plugin. It installs like a normal plugin, but automatically injects itself into the JVM arguments, hijacks the server's root classloader, bridges the isolation gaps between the server and plugin environments, and initializes the Mixin ecosystem before the server fully boots.

## ✨ Features

- 🪄 **Drop-In Installation:** Install it like a normal plugin. No need to edit `start.sh` or `start.bat`. MixinLoader automatically detects if it's running as an agent, and if not, gracefully closes network sockets and relaunches the JVM with itself injected as a `-javaagent`.
- 🤝 **Broad Ecosystem Compatibility:** Natively supports loading and running Mixin plugins built for **Ignite**, **Horizon**, and **Origami**.
- 🚀 **Paperclip Hijacking:** Intercepts `Paperclip`'s initialization to replace the root `URLClassLoader` with a custom `RoutingServerClassLoader`, giving the loader full visibility of the server environment.
- 🔀 **Smart Class Routing:** Automatically maps plugin packages to their respective classloaders. If a Mixin targets a class in a plugin, the loader knows exactly which `PluginClassLoader` to ask.
- 📖 **Access Widener Support:** Fully supports Access Wideners out of the box, allowing your mixins and plugins to bypass Java visibility restrictions seamlessly.
- 🔄 **Universal Plugin Support:** Fully compatible with both legacy (`org.bukkit.plugin.java.PluginClassLoader`) and modern (`io.papermc.paper.plugin...PaperPluginClassLoader`) plugin loading systems.
- 🧰 **MixinExtras Included:** Bootstraps [MixinExtras](https://github.com/LlamaLad7/MixinExtras) automatically for advanced Mixin capabilities.
- 🛡️ **Java 17+ Ready:** Automatically opens required `java.base` internals via `Instrumentation.redefineModule` to ensure deep reflection works on modern JVMs.

## ⚠️ Compatibility & API Limitations

While MixinLoader can successfully load and execute plugins designed for **Ignite**, **Horizon**, and **Origami**, it is important to understand how this compatibility works:

* **What IS supported:** Core Mixin injection, MixinExtras extensions, Access Wideners, and standard classloader routing. If a plugin relies purely on these standard tools, it will work perfectly.
* **What IS NOT supported:** MixinLoader **does not** implement the proprietary public or private Java APIs provided by Ignite, Horizon, or Origami. We focus strictly on the core Mixin ecosystem. If a plugin strictly depends on custom utility classes, proprietary lifecycle events, or specific API methods unique to those other loaders, it will fail to load or function correctly.

## 📦 Usage

Installing MixinLoader is as simple as installing any other Paper plugin. **No modifications to your startup scripts are required.**

1. **Download** the latest `MixinLoader-1.0.0-all.jar`.
2. **Drop it** into your server's `plugins/` folder.
3. **Start your server** exactly as you normally would:
   ```bash
   java -jar server.jar
   ```

Upon startup, the plugin will detect that it is not running as a Java agent. It will cleanly close the server's listening sockets to prevent port-binding conflicts, and seamlessly relaunch the JVM with itself injected as a `-javaagent` before the server fully loads.

## 🛠️ How It Works (Technical Overview)

### 1. The Self-Bootstrapper (`MixinBootstrapper`)
When the server starts, MixinLoader checks the `mixinloader.loaded` system property. If it's missing, it knows it needs to inject itself:
* **On Linux/POSIX:** It reads `/proc/self/fd` to find open network sockets and closes them via native `libc` calls. It then uses `execvp` to replace the current process with the new JVM command, ensuring no orphaned processes are left behind.
* **On Windows:** It iterates through process handles, checking for listening sockets via `WinSock2` (`getsockopt` with `SO_ACCEPTCONN`), closes them, and uses a `ProcessBuilder` to launch the new JVM.
* *Why close sockets?* If the JVM restarts without closing the sockets first, the OS keeps the port (e.g., 25565) in a `TIME_WAIT` state, causing the newly launched JVM to crash with `java.net.BindException: Address already in use`.

### 2. Bytecode Instrumentation (`MixinLoader`)
Once running as an agent, it uses **ByteBuddy** to perform runtime bytecode manipulation:
* **Root Classloader Replacement:** Uses `MemberSubstitution` to intercept the `new URLClassLoader(...)` call inside `Paperclip.main` and replaces it with `RoutingServerClassLoader`.
* **Namespace Registry:** Scans plugin JARs to build a concurrent map of package names to classloaders.
* **LoadClass Interception:** Injects `Advice` into the `loadClass` methods of the server and plugin classloaders to route missing classes to the correct plugin classloader.

## 🏗️ Building from Source

This project uses Gradle for dependency management.

```bash
# Clone the repository
git clone https://github.com/Chest-Solutions/MixinLoader.git
cd MixinLoader

# Build the project
./gradlew build
```

The compiled JAR will be located in the `build/libs/` directory.

## 📚 Dependencies & Credits

Massive thanks to the creators of the following libraries:

- **[SpongePowered Mixin](https://github.com/SpongePowered/Mixin)** - The core mixin framework.
- **[MixinExtras](https://github.com/LlamaLad7/MixinExtras)** by LlamaLad7 - Essential Mixin extensions.
- **[ByteBuddy](https://bytebuddy.net/)** - For powerful runtime bytecode generation.
- **[reflectionremapper](https://github.com/jpenilla/reflectionremapper)** by jpenilla - For clean, type-safe reflection proxies.
- **[JNA (Java Native Access)](https://github.com/java-native-access/jna)** - For OS-level socket and process management.
- **[PaperMC](https://papermc.io/)** - For the amazing server software.

## ⚖️ License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
