/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * The account this process runs as, as the system's account database records it ({@code
 * getpwuid(getuid())}): its login name, its home and its login shell. A terminal emulator's login
 * reads the same entry, so a session started from it runs the shell its person chose, where {@code
 * $SHELL} is only what this process happened to inherit, and a service may inherit none.
 */
public record Account(String name, Path home, Path shell) {

  /** What {@code passwd(5)} says an empty login shell means. */
  static final Path DEFAULT_SHELL = Path.of("/bin/sh");

  private static final long FIELD_BOUND = 4096;

  private static final MethodHandle GETUID =
      Native.downcall("getuid", FunctionDescriptor.of(ValueLayout.JAVA_INT));

  private static final MethodHandle GETPWUID =
      Native.downcall("getpwuid", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

  /**
   * This process's account. Serialized, since {@code getpwuid} answers in storage libc reuses for
   * its next answer.
   */
  public static synchronized Account current() throws IOException {
    int uid;
    MemorySegment entry;
    try {
      uid = (int) GETUID.invokeExact();
      entry = (MemorySegment) GETPWUID.invokeExact(uid);
    } catch (Throwable t) {
      throw new IOException("Reading this account from the account database failed.", t);
    }
    if (entry.equals(MemorySegment.NULL)) {
      throw new IOException(
          "Account "
              + uid
              + " has no entry in this system's account database, so its home and login shell"
              + " are unknown.");
    }
    var passwd = entry.reinterpret(Native.PLATFORM.pwShell() + ValueLayout.ADDRESS.byteSize());
    var home = field(passwd, Native.PLATFORM.pwDir());
    if (!home.startsWith("/")) {
      throw new IOException(
          "Account " + uid + " has no home directory in this system's account database.");
    }
    var shell = field(passwd, Native.PLATFORM.pwShell());
    return new Account(
        field(passwd, 0), Path.of(home), shell.isEmpty() ? DEFAULT_SHELL : Path.of(shell));
  }

  private static String field(MemorySegment passwd, long offset) {
    var value = passwd.get(ValueLayout.ADDRESS, offset);
    return value.equals(MemorySegment.NULL) ? "" : value.reinterpret(FIELD_BOUND).getString(0);
  }
}
