/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import java.util.Locale;

/** The form a session of a harness takes, a terminal or a chat ({@link Harness#forms}). */
public enum SessionForm {
  TERMINAL,
  CHAT;

  /** The form as the API says it. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
