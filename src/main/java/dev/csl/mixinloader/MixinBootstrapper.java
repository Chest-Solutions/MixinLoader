package dev.csl.mixinloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.NotNull;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.StringArray;
import com.sun.jna.platform.mac.SystemB;

import dev.csl.mixinloader.util.SneakyExceptions;

public final class MixinBootstrapper {
	public record LaunchArguments(List<String> jvmArgs, List<String> programArgs, Path jar){}

	public static class MacFields {
		private static final int PROC_FD_INFO_SIZE = new SystemB.ProcFdInfo().size();
		private static final int SOCKET_INFO_SIZE = new SystemB.SocketInfo().size();
	}

	@SuppressWarnings("UnusedReturnValue")
    public interface LibC extends Library {
		LibC INSTANCE = Native.load("c", LibC.class);
		int execvp(String file, StringArray argv);
		int close(int fd);
	}

	@SuppressWarnings("UnusedReturnValue")
	public interface WinSock extends Library {
		WinSock INSTANCE = Native.load("ws2_32", WinSock.class);
		int getsockopt(long s, int level, int optname, byte[] optval, int[] optlen);
		int closesocket(long s);
	}

	@SuppressWarnings("unused")
	public static void checkBootAndLoadMixinLoader() {
		if (Boolean.getBoolean("mixinloader.loaded")) return;

		if (Platform.isWindows()) {
			closeAllWindowsListeningSockets();
		} else if (Platform.isMac()) {
			closeAllMacListeningSockets();
		} else if (Platform.isLinux()) {
			closeAllLinuxListeningSockets();
		}

		final ProcessBuilder pb = getProcessBuilder();

		try {
			final Process process = pb.start();
			Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
			System.exit(process.waitFor());
		} catch (final IOException e) {
			throw new RuntimeException("Failed to relaunch server", e);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Failed to relaunch server", e);
		}
	}

	public static @NotNull LaunchArguments getArgs() {
		final List<String> jvm = new ArrayList<>();
		final List<String> prog = new ArrayList<>();
		Path jar = null;
		final Optional<String[]> allArgs = ProcessHandle.current().info().arguments();
		if (allArgs.isPresent()) {
			boolean nextJar = false;
			boolean pastJar = false;
			for (final String arg : allArgs.get()) {
				if (pastJar) {
					prog.add(arg);
				} else if (arg.equals("-jar")) {
					nextJar = true;
                } else if (arg.endsWith(".jar") && !arg.startsWith("-") && nextJar) {
					pastJar = true;
					nextJar = false;
					jar = Path.of(arg).toAbsolutePath();
				} else {
					jvm.add(arg);
				}
			}
		}

		return new LaunchArguments(jvm, prog, jar);
	}

	private static void closeAllWindowsListeningSockets() {
		final int SOL_SOCKET = 0xFFFF;
		final int SO_ACCEPTCONN = 0x0002;

		final byte[] optval = new byte[4];
		final int[] optlen = new int[]{ 4 };

		for (long h = 4; h < 65536; h++) {
			optlen[0] = 4;
			final int res = WinSock.INSTANCE.getsockopt(h, SOL_SOCKET, SO_ACCEPTCONN, optval, optlen);
			if (res == 0 && (optval[0] != 0 || optval[1] != 0 || optval[2] != 0 || optval[3] != 0)) {
				WinSock.INSTANCE.closesocket(h);
				System.out.println("[MixinLoader] Released Windows listening socket handle: " + h);
			}
		}
	}

	private static void closeAllMacListeningSockets() {
		final SystemB sysB = SystemB.INSTANCE;
		final int pid = (int) ProcessHandle.current().pid();

		final int bufSize = sysB.proc_pidinfo(pid, SystemB.PROC_PIDLISTFDS, 0, null, 0);
		if (bufSize <= 0) return;

		final int count = bufSize / MacFields.PROC_FD_INFO_SIZE;
		final SystemB.ProcFdInfo[] fdArray = (SystemB.ProcFdInfo[]) new SystemB.ProcFdInfo().toArray(count);

		int r = sysB.proc_pidinfo(pid, SystemB.PROC_PIDLISTFDS, 0, fdArray[0], bufSize);
		if (r <= 0) return;

		final SystemB.SocketInfo sockInfo = new SystemB.SocketInfo();

		for (int i = 0; i < count; i++) {
			fdArray[i].read();

			if (fdArray[i].proc_fdtype != SystemB.PROX_FDTYPE_SOCKET || fdArray[i].proc_fd <= 2)
				continue;

			r = sysB.proc_pidfdinfo(pid, fdArray[i].proc_fd,
				SystemB.PROC_PIDFDSOCKETINFO, sockInfo, MacFields.SOCKET_INFO_SIZE);
			if (r <= 0) continue;

			if (sockInfo.soi_kind != SystemB.SOCKINFO_TCP) continue;
			if (sockInfo.soi_proto.pri_in.insi_fport != 0) continue;

			final int lport = sockInfo.soi_proto.pri_in.insi_lport;
			final int port = (lport & 0xFF) << 8 | (lport >> 8 & 0xFF);
			System.out.println("[MixinLoader] Closed listening socket FD: " + fdArray[i].proc_fd + " port=" + port);
			sysB.close(fdArray[i].proc_fd);
		}
	}

	private static void closeAllLinuxListeningSockets() {
		final Path fdPath = Path.of("/proc/self/fd");
		if (!Files.exists(fdPath)) return;

		try (var stream = Files.list(fdPath)) {
			stream.forEach(path -> {
				try {
					final int fd = Integer.parseInt(path.getFileName().toString());
					if (fd <= 2) return;

					final String target = Files.readSymbolicLink(path).toString();
					if (target.startsWith("socket:")) {
						LibC.INSTANCE.close(fd);
						System.out.println("[MixinLoader] Safely closed socket FD: " + fd + " (" + target + ")");
					}
				} catch (final Throwable ignored) {}
			});
		} catch (final Throwable ignored) {}

		LibC.INSTANCE.execvp(getJavaExecutable(), new StringArray(getCommand().toArray(new String[0])));
		System.err.println("execv failed with result, errno=" + Native.getLastError());
	}

	private static @NotNull ProcessBuilder getProcessBuilder() {
		return new ProcessBuilder(getCommand()).directory(Path.of(".").toAbsolutePath().toFile()).inheritIO();
	}

	@SuppressWarnings("DataFlowIssue")
    private static @NotNull List<String> getCommand() {
		final Path pluginJar = SneakyExceptions.get(() ->
				Path.of(MixinBootstrapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()));

		final LaunchArguments launchArguments = getArgs();
		final List<String> command = new ArrayList<>();
		command.add(getJavaExecutable());

		command.add("-javaagent:" + pluginJar.toAbsolutePath());
		command.addAll(launchArguments.jvmArgs);

		command.add("-jar");
		command.add(launchArguments.jar.toString());

		command.addAll(launchArguments.programArgs);
		return command;
	}

	private static @NotNull String getJavaExecutable() {
		final String javaHome = System.getProperty("java.home");
		final String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";

		final Path javaBin = Path.of(javaHome, "bin", exe);
		return Files.exists(javaBin) ? javaBin.toAbsolutePath().toString() : "java";
	}
}
