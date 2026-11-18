/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.pty.PtyMessage.PutRefusal;

/**
 * The bounds an upload meets on the host, and one session's tally against them: the files and bytes
 * it holds or has on their way. Taking from it and giving back are each one step, so two
 * connections to one session cannot both pass its last file.
 */
final class UploadAllowance {

  /** The largest file one upload carries. */
  static final long ITEM = 64L << 20;

  /** The most files one session keeps. */
  static final int FILES = 256;

  /** The most bytes one session keeps. */
  static final long SESSION = 1L << 30;

  /** The most bytes one chunk carries. */
  static final int CHUNK = 64 << 10;

  /** The most uploads under way at once in one session. */
  static final int OPEN = 4;

  private int files;
  private long bytes;
  private int open;

  /** Why a file of {@code size} bytes is never taken, whatever a session holds; else null. */
  static PutRefusal sizeRefusal(long size) {
    return size < 0 || size > ITEM ? PutRefusal.TOO_LARGE : null;
  }

  /** The bound a refusal of {@code kind} met, in files or bytes; zero for one that met none. */
  static long limit(PutRefusal kind) {
    return switch (kind) {
      case TOO_LARGE -> ITEM;
      case TOO_MANY -> FILES;
      case SESSION_FULL -> SESSION;
      case BUSY -> OPEN;
      default -> 0;
    };
  }

  /**
   * Takes one file of {@code size} bytes, under way: null, or the refusal when the session has no
   * room or {@link #OPEN} uploads under way already.
   */
  synchronized PutRefusal take(long size) {
    if (open >= OPEN) {
      return PutRefusal.BUSY;
    }
    if (files >= FILES) {
      return PutRefusal.TOO_MANY;
    }
    if (bytes + size > SESSION) {
      return PutRefusal.SESSION_FULL;
    }
    files++;
    bytes += size;
    open++;
    return null;
  }

  /**
   * One upload of {@code size} bytes is no longer under way: its file and bytes are kept when it
   * was {@code stored}, else handed back.
   */
  synchronized void done(long size, boolean stored) {
    open--;
    if (!stored) {
      files--;
      bytes -= size;
    }
  }

  /** Whether nothing is taken. */
  synchronized boolean empty() {
    return files == 0;
  }
}
