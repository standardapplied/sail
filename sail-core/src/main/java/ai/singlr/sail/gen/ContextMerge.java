/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

/**
 * Splits an agent context file (CLAUDE.md / AGENTS.md) into a sail-generated body and a personal
 * region the engineer owns, joined by a stable marker. The body above the marker is regenerated on
 * every sail run and — because it derives from the synced {@code sail.yaml} — shared with the team;
 * the region from the marker down is local to the box and preserved verbatim across regeneration.
 *
 * <p>Pure string transforms, no I/O: the installer reads the existing file, calls {@link #merge},
 * and writes the result. The split is located by {@link #MARKER_TOKEN} so the marker's
 * human-readable wording can change without breaking older files.
 */
public final class ContextMerge {

  private ContextMerge() {}

  /** Stable token that locates the personal region; the surrounding wording may evolve. */
  public static final String MARKER_TOKEN = "<!-- sail:personal";

  /** The full marker line written into fresh files. */
  public static final String MARKER =
      MARKER_TOKEN
          + " — Everything below is yours: local to this box, never synced to teammates, and"
          + " preserved when sail regenerates this file. -->";

  private static final String DEFAULT_PERSONAL =
      MARKER
          + "\n\n## My Notes\n\n"
          + "_Personal, project-specific notes for this box. Put anything you want the agent to know"
          + " that is not shared with the team here._\n";

  private static final String CARRIED_OVER =
      "<!-- Carried over from your previous context file when sail split it into a shared body"
          + " (above) and your personal notes (below). Trim anything now covered above. -->";

  /** A fresh file: the generated {@code body} followed by an empty personal region. */
  public static String render(String body) {
    return join(body, DEFAULT_PERSONAL);
  }

  /**
   * Merges a freshly generated {@code body} with whatever already exists in the container,
   * preserving the engineer's personal region. With no existing file this is {@link
   * #render(String)}. A legacy file with no marker is preserved in full below a fresh marker so
   * nothing is ever lost. Idempotent: re-merging the same body is a no-op.
   */
  public static String merge(String existing, String body) {
    if (existing == null || existing.isBlank()) {
      return render(body);
    }
    var personalStart = personalRegionStart(existing);
    if (personalStart >= 0) {
      return join(body, existing.substring(personalStart));
    }
    return join(body, MARKER + "\n\n" + CARRIED_OVER + "\n\n" + existing.strip() + "\n");
  }

  /** Index where the line holding {@link #MARKER_TOKEN} begins, or {@code -1} if absent. */
  private static int personalRegionStart(String text) {
    var idx = text.indexOf(MARKER_TOKEN);
    if (idx < 0) {
      return -1;
    }
    return text.lastIndexOf('\n', idx) + 1;
  }

  private static String join(String body, String personal) {
    return body.stripTrailing() + "\n\n" + personal;
  }
}
