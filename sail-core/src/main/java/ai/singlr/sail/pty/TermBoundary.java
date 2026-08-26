/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

/**
 * A streaming boundary tracker for a raw terminal byte stream: it knows, at any point, whether the
 * stream is at a safe replay boundary — not inside an escape sequence (ESC/CSI/OSC/DCS/SOS/PM/APC)
 * and not between the bytes of a UTF-8 character. It is a framer, deliberately not an emulator: it
 * never interprets what sequences mean, only where they end.
 *
 * <p>Fed every byte from the session's birth, its state is exact — the session host records a safe
 * watermark whenever a fed chunk ends in ground state at a line start, and attach-replay begins at
 * such a watermark so a late client's parser never wakes up mid-sequence or mid-character.
 */
public final class TermBoundary {

  private enum State {
    GROUND,
    ESC,
    CSI,
    STRING,
    STRING_ESC
  }

  private State state = State.GROUND;
  private int utf8Pending;
  private boolean atLineStart = true;

  /** Advances over {@code buf[0..len)}. */
  public void feed(byte[] buf, int len) {
    for (var i = 0; i < len; i++) {
      var b = buf[i] & 0xFF;
      switch (state) {
        case GROUND -> {
          if (utf8Pending > 0) {
            utf8Pending = (b & 0xC0) == 0x80 ? utf8Pending - 1 : 0;
          }
          if (utf8Pending == 0) {
            if (b == 0x1B) {
              state = State.ESC;
            } else if (b >= 0xC2 && b <= 0xDF) {
              utf8Pending = 1;
            } else if (b >= 0xE0 && b <= 0xEF) {
              utf8Pending = 2;
            } else if (b >= 0xF0 && b <= 0xF4) {
              utf8Pending = 3;
            }
          }
          atLineStart = b == '\n';
        }
        case ESC ->
            state =
                switch (b) {
                  case '[' -> State.CSI;
                  case ']', 'P', 'X', '^', '_' -> State.STRING;
                  case 0x1B -> State.ESC;
                  default -> State.GROUND;
                };
        case CSI -> {
          if (b >= 0x40 && b <= 0x7E) {
            state = State.GROUND;
          }
        }
        case STRING -> {
          if (b == 0x07) {
            state = State.GROUND;
          } else if (b == 0x1B) {
            state = State.STRING_ESC;
          }
        }
        case STRING_ESC -> state = b == '\\' ? State.GROUND : State.STRING;
      }
    }
  }

  /** Whether the stream is at a boundary a fresh parser can safely start from. */
  public boolean atSafeBoundary() {
    return state == State.GROUND && utf8Pending == 0;
  }

  /** A safe boundary that is also the start of a line — the preferred replay point. */
  public boolean atSafeLineStart() {
    return atSafeBoundary() && atLineStart;
  }
}
