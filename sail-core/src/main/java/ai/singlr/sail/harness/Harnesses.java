/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import java.util.List;

/** The harnesses sail knows, and the one it assumes when a project names none. */
public final class Harnesses {

  /** The harness a project gets when its {@code sail.yaml} names none. */
  public static final Harness DEFAULT = new ClaudeCode();

  private static final List<Harness> ALL = List.of(DEFAULT, new Codex());

  private Harnesses() {}

  /** Every known harness, in the order sail lists them. */
  public static List<Harness> all() {
    return ALL;
  }

  /** The {@code sail.yaml} names of every known harness, in the same order. */
  public static List<String> names() {
    return ALL.stream().map(Harness::yamlName).toList();
  }

  /**
   * The harness named {@code yamlName} in {@code sail.yaml}.
   *
   * @throws IllegalArgumentException if no harness has that name
   */
  public static Harness of(String yamlName) {
    return ALL.stream()
        .filter(harness -> harness.yamlName().equals(yamlName))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Unknown agent CLI: '"
                        + yamlName
                        + "'. Known agents: "
                        + String.join(", ", names())
                        + ".\n  Check the 'install' list in your sail.yaml agent section."));
  }
}
