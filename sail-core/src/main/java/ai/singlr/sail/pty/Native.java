/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.Locale;

/**
 * The one libc lookup the pty layer shares: every downcall is bound here, with or without {@code
 * errno} capture, so {@link Pty}, {@link PtyChildShim} and {@link SdNotify} agree on how a failed
 * call is read, and so are the two calls both sides of a terminal make, opening it and {@code
 * ioctl}. It is also the one place that knows which system it runs on: {@link #PLATFORM} is chosen
 * once, and every other class reads the system's values from it rather than asking.
 */
final class Native {

  /**
   * What differs between the systems the pty layer runs on: the open flag that keeps a terminal
   * from becoming the opener's controlling terminal, the {@code ioctl} requests that set a
   * terminal's size and claim it as the controlling terminal, the width of {@code poll}'s {@code
   * nfds_t}, whether the service manager keeps descriptors across a restart (systemd's store), and
   * where {@code struct passwd} keeps an account's home and login shell.
   */
  record Platform(
      int oNoctty,
      long tiocswinsz,
      long tiocsctty,
      ValueLayout nfdsT,
      boolean descriptorStore,
      long pwDir,
      long pwShell) {}

  /** glibc's {@code pwd.h}: name, passwd, uid, gid, gecos, dir, shell. */
  static final Platform LINUX =
      new Platform(0x100, 0x5414L, 0x540EL, ValueLayout.JAVA_LONG, true, 32, 40);

  /**
   * XNU's {@code fcntl.h} and {@code ttycom.h}: {@code _IOW('t', 103, struct winsize)}; Apple's
   * {@code pwd.h} keeps a change time and a class before the gecos.
   */
  static final Platform DARWIN =
      new Platform(0x20000, 0x80087467L, 0x20007461L, ValueLayout.JAVA_INT, false, 48, 56);

  static final Platform PLATFORM = platform(System.getProperty("os.name", ""));

  static final int O_RDWR = 2;

  static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
  static final StructLayout ERRNO_STATE = Linker.Option.captureStateLayout();
  private static final VarHandle ERRNO =
      ERRNO_STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = LINKER.defaultLookup();

  private Native() {}

  static Platform platform(String osName) {
    var os = osName.toLowerCase(Locale.ROOT);
    if (os.startsWith("linux")) {
      return LINUX;
    }
    if (os.startsWith("mac") || os.startsWith("darwin")) {
      return DARWIN;
    }
    throw new IllegalStateException(
        "The pty layer runs on Linux and macOS, not on '" + osName + "'.");
  }

  /**
   * A variadic function, {@code ioctl} among them, is bound with {@link
   * Linker.Option#firstVariadicArg}: Apple's arm64 convention passes its variadic arguments on the
   * stack, where a binding as fixed arguments would never put them.
   */
  static MethodHandle downcall(
      String symbol, FunctionDescriptor descriptor, Linker.Option... options) {
    return LINKER.downcallHandle(LOOKUP.find(symbol).orElseThrow(), descriptor, options);
  }

  static int errno(MemorySegment state) {
    return (int) ERRNO.get(state, 0L);
  }

  private static final MethodHandle OPEN =
      downcall(
          "open",
          FunctionDescriptor.of(
              ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS,
              ValueLayout.JAVA_INT,
              ValueLayout.JAVA_INT),
          Linker.Option.firstVariadicArg(2),
          CAPTURE_ERRNO);

  private static final MethodHandle IOCTL =
      downcall(
          "ioctl",
          FunctionDescriptor.of(
              ValueLayout.JAVA_INT,
              ValueLayout.JAVA_INT,
              ValueLayout.JAVA_LONG,
              ValueLayout.ADDRESS),
          Linker.Option.firstVariadicArg(2),
          CAPTURE_ERRNO);

  /** Opens {@code terminal} read-write, never as the opener's controlling terminal. */
  static int openTerminal(String terminal) throws IOException {
    try (var arena = Arena.ofConfined()) {
      var errno = arena.allocate(ERRNO_STATE);
      var fd =
          (int)
              OPEN.invokeExact(errno, arena.allocateFrom(terminal), O_RDWR | PLATFORM.oNoctty(), 0);
      if (fd < 0) {
        throw new IOException(
            "Could not open the terminal " + terminal + " (errno " + errno(errno) + ").");
      }
      return fd;
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("opening the terminal " + terminal + " failed", t);
    }
  }

  /** {@code ioctl(fd, request, argument)}, refused as {@code refusal} with the call's errno. */
  static void ioctl(int fd, long request, MemorySegment argument, String refusal)
      throws IOException {
    try (var arena = Arena.ofConfined()) {
      var errno = arena.allocate(ERRNO_STATE);
      if ((int) IOCTL.invokeExact(errno, fd, request, argument) != 0) {
        throw new IOException(refusal + " (errno " + errno(errno) + ").");
      }
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("ioctl failed: " + refusal, t);
    }
  }
}
