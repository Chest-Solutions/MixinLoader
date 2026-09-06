package dev.csl.mixinloader;

import com.sun.jna.*;
import com.sun.jna.platform.mac.SystemB;
import dev.csl.mixinloader.util.SneakyExceptions;
import org.apache.commons.lang3.SystemUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MixinBootstrapper {
	public record LaunchArguments(List<String> jvmArgs, List<String> programArgs, Path jar){}

	private static final int PROC_FD_INFO_SIZE = new SystemB.ProcFdInfo().size();
	private static final int SOCKET_INFO_SIZE = new SystemB.SocketInfo().size();

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

	public static void checkBootAndLoadMixinLoader() {
		if (Boolean.getBoolean("mixinloader.loaded")) return;

		if (SystemUtils.IS_OS_WINDOWS) {
			closeAllWindowsListeningSockets();
		} else if (SystemUtils.IS_OS_MAC) {
			closeAllMacListeningSockets();
		} else if (SystemUtils.IS_OS_LINUX) {
			closeAllLinuxListeningSockets();
		}

		ProcessBuilder pb = getProcessBuilder();

		try {
			Process process = pb.start();
			Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
			System.exit(process.waitFor());
		} catch (IOException e) {
			throw new RuntimeException("Failed to relaunch server", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Failed to relaunch server", e);
		}
	}

	private static void closeAllWindowsListeningSockets() {
		final int SOL_SOCKET = 0xFFFF;
		final int SO_ACCEPTCONN = 0x0002;

		byte[] optval = new byte[4];
		int[] optlen = new int[]{ 4 };

		for (long h = 4; h < 65536; h++) {
			optlen[0] = 4;
			int res = WinSock.INSTANCE.getsockopt(h, SOL_SOCKET, SO_ACCEPTCONN, optval, optlen);
			if (res == 0 && (optval[0] != 0 || optval[1] != 0 || optval[2] != 0 || optval[3] != 0)) {
				WinSock.INSTANCE.closesocket(h);
				System.out.println("[MixinLoader] Released Windows listening socket handle: " + h);
			}
		}
	}

	private static void closeAllMacListeningSockets() {
		SystemB sysB = SystemB.INSTANCE;
		int pid = (int) ProcessHandle.current().pid();

		int bufSize = sysB.proc_pidinfo(pid, SystemB.PROC_PIDLISTFDS, 0, null, 0);
		if (bufSize <= 0) return;

		int count = bufSize / PROC_FD_INFO_SIZE;
		SystemB.ProcFdInfo[] fdArray = (SystemB.ProcFdInfo[]) new SystemB.ProcFdInfo().toArray(count);

		int r = sysB.proc_pidinfo(pid, SystemB.PROC_PIDLISTFDS, 0, fdArray[0], bufSize);
		if (r <= 0) return;

		SystemB.SocketInfo sockInfo = new SystemB.SocketInfo();

		for (int i = 0; i < count; i++) {
			fdArray[i].read();

			if (fdArray[i].proc_fdtype != SystemB.PROX_FDTYPE_SOCKET || fdArray[i].proc_fd <= 2)
				continue;

			r = sysB.proc_pidfdinfo(pid, fdArray[i].proc_fd,
				SystemB.PROC_PIDFDSOCKETINFO, sockInfo, SOCKET_INFO_SIZE);
			if (r <= 0) continue;

			if (sockInfo.soi_kind != SystemB.SOCKINFO_TCP) continue;
			if (sockInfo.soi_proto.pri_in.insi_fport != 0) continue;

			int lport = sockInfo.soi_proto.pri_in.insi_lport;
			int port = (lport & 0xFF) << 8 | (lport >> 8 & 0xFF);
			System.out.println("[MixinLoader] Closed listening socket FD: " + fdArray[i].proc_fd + " port=" + port);
			sysB.close(fdArray[i].proc_fd);
		}
	}

	private static void closeAllLinuxListeningSockets() {
		Path fdPath = Path.of("/proc/self/fd");
		if (!Files.exists(fdPath)) return;

		try (var stream = Files.list(fdPath)) {
			stream.forEach(path -> {
				try {
					int fd = Integer.parseInt(path.getFileName().toString());
					if (fd <= 2) return; // Skip stdin, stdout, stderr

					// Read the symlink destination (e.g., "socket:[123456]")
					String target = Files.readSymbolicLink(path).toString();
					if (target.startsWith("socket:")) {
						LibC.INSTANCE.close(fd);
						System.out.println("[MixinLoader] Safely closed socket FD: " + fd + " (" + target + ")");
					}
				} catch (Throwable ignored) {}
			});
		} catch (Throwable ignored) {}

		LibC.INSTANCE.execvp(
			getJavaExecutable(),
			new StringArray(
				getCommand()
					.toArray(new String[0])
			)
		);
		System.err.println("execv failed with result, errno=" + Native.getLastError());
	}

	private static @NotNull ProcessBuilder getProcessBuilder() {
		return new ProcessBuilder(getCommand())
			.directory(Path.of(".").toAbsolutePath().toFile())
				.inheritIO();
	}

	@SuppressWarnings("DataFlowIssue")
    private static @NotNull List<String> getCommand() {
		String javaExe = getJavaExecutable();

		Path pluginJar = SneakyExceptions.get(() ->
				Path.of(MixinBootstrapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()));

		LaunchArguments launchArguments = getArgs();
		List<String> command = new ArrayList<>();
		command.add(javaExe);

		command.add("-javaagent:" + pluginJar.toAbsolutePath());
		command.addAll(launchArguments.jvmArgs);

		command.add("-jar");
		command.add(launchArguments.jar.toString());

		command.addAll(launchArguments.programArgs);
		return command;
	}

	private static @NotNull String getJavaExecutable() {
		String javaHome = System.getProperty("java.home");
		String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";

		Path javaBin = Path.of(javaHome, "bin", exe);
		return Files.exists(javaBin) ? javaBin.toAbsolutePath().toString() : "java";
	}

	public static @NotNull LaunchArguments getArgs() {
		List<String> jvm = new ArrayList<>();
		List<String> prog = new ArrayList<>();
		Path jar = null;
		Optional<String[]> allArgs = ProcessHandle.current().info().arguments();
		if (allArgs.isPresent()) {
			boolean nextJar = false;
			boolean pastJar = false;
			for (String arg : allArgs.get()) {
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
}
