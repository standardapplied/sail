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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * The one libc lookup the pty layer shares: every downcall is bound here, with or without {@code
 * errno} capture, so {@link Pty} and {@link SdNotify} agree on how a failed call is read.
 */
final class Native {

  static final StructLayout ERRNO_STATE = Linker.Option.captureStateLayout();
  private static final VarHandle ERRNO =
      ERRNO_STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = LINKER.defaultLookup();

  private Native() {}

  static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(LOOKUP.find(symbol).orElseThrow(), descriptor);
  }

  static MethodHandle downcallCapturingErrno(String symbol, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(
        LOOKUP.find(symbol).orElseThrow(), descriptor, Linker.Option.captureCallState("errno"));
  }

  static int errno(MemorySegment state) {
    return (int) ERRNO.get(state, 0L);
  }
}
