/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

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
 * errno} capture, so {@link Pty} and {@link SdNotify} agree on how a failed call is read. It is
 * also the one place that knows which system it runs on: {@link #PLATFORM} is chosen once, and
 * every other class reads the system's values from it rather than asking.
 */
final class Native {

  /**
   * What differs between the systems the pty layer runs on: the open flag that keeps a terminal
   * from becoming the opener's controlling terminal, the {@code ioctl} requests that set a
   * terminal's size and claim it as the controlling terminal, the width of {@code poll}'s {@code
   * nfds_t}, and whether the service manager keeps descriptors across a restart (systemd's store).
   */
  record Platform(
      int oNoctty, long tiocswinsz, long tiocsctty, ValueLayout nfdsT, boolean descriptorStore) {}

  static final Platform LINUX = new Platform(0x100, 0x5414L, 0x540EL, ValueLayout.JAVA_LONG, true);

  /** XNU's {@code fcntl.h} and {@code ttycom.h}: {@code _IOW('t', 103, struct winsize)}. */
  static final Platform DARWIN =
      new Platform(0x20000, 0x80087467L, 0x20007461L, ValueLayout.JAVA_INT, false);

  static final Platform PLATFORM = platform(System.getProperty("os.name", ""));

  static final int O_RDWR = 2;

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

  static MethodHandle downcallCapturingErrno(String symbol, FunctionDescriptor descriptor) {
    return downcall(symbol, descriptor, Linker.Option.captureCallState("errno"));
  }

  static int errno(MemorySegment state) {
    return (int) ERRNO.get(state, 0L);
  }
}
