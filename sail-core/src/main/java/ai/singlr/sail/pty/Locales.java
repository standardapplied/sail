/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Which locales this system has, as its C library answers: {@code newlocale} builds a locale for
 * the character type without touching this process's own, and fails for one that is not installed
 * ({@code C.UTF-8} is built into glibc, and a minimal image has no {@code en_US.UTF-8}).
 */
public final class Locales {

  private static final MethodHandle NEWLOCALE =
      Native.downcall(
          "newlocale",
          FunctionDescriptor.of(
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

  private static final MethodHandle FREELOCALE =
      Native.downcall("freelocale", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

  private Locales() {}

  /** Whether a program here can run in {@code name}'s character type. */
  public static boolean installed(String name) {
    try (var arena = Arena.ofConfined()) {
      var locale =
          (MemorySegment)
              NEWLOCALE.invokeExact(
                  Native.PLATFORM.lcCtypeMask(), arena.allocateFrom(name), MemorySegment.NULL);
      if (locale.equals(MemorySegment.NULL)) {
        return false;
      }
      FREELOCALE.invokeExact(locale);
      return true;
    } catch (Throwable t) {
      throw new IllegalStateException("newlocale failed for '" + name + "'", t);
    }
  }
}
