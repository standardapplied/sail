/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The form a session of a harness takes, a terminal or a chat ({@link Harness#forms}), and the form
 * a person starts in, either of them or {@code both}, where they choose each time.
 */
public enum SessionForm {
  TERMINAL,
  CHAT,
  BOTH;

  /** The form as the API says it. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The form {@code wire} says, exactly as {@link #wire} writes it; empty for any other word. */
  public static Optional<SessionForm> of(String wire) {
    return Arrays.stream(values()).filter(form -> form.wire().equals(wire)).findFirst();
  }
}
