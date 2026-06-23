/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContextMergeTest {

  private static final String BODY = "# CLAUDE.md\n\n## Project\nAcme\n";

  @Test
  void renderEmitsBodyThenAnEmptyPersonalRegion() {
    var out = ContextMerge.render(BODY);

    assertTrue(out.contains("## Project"));
    assertTrue(out.contains(ContextMerge.MARKER_TOKEN), "fresh files carry the marker");
    assertTrue(out.contains("My Notes"));
  }

  @Test
  void mergeWithNoExistingFileEqualsRender() {
    assertEquals(ContextMerge.render(BODY), ContextMerge.merge(null, BODY));
    assertEquals(ContextMerge.render(BODY), ContextMerge.merge("   \n  ", BODY));
  }

  @Test
  void mergePreservesThePersonalRegionAndRefreshesTheBody() {
    var existing =
        "# CLAUDE.md\n\n## Project\nOLD\n\n"
            + ContextMerge.MARKER
            + "\n\n## My Notes\n\nremember the staging password is in 1Password\n";

    var merged = ContextMerge.merge(existing, BODY);

    assertTrue(merged.contains("Acme"), "the body is refreshed");
    assertFalse(merged.contains("OLD"), "the stale body is dropped");
    assertTrue(
        merged.contains("remember the staging password is in 1Password"),
        "the personal region is preserved verbatim");
  }

  @Test
  void mergePreservesALegacyFileWithoutAMarkerInFull() {
    var legacy = "# CLAUDE.md\n\nMy own hand-written guidance that predates the split.\n";

    var merged = ContextMerge.merge(legacy, BODY);

    assertTrue(merged.contains("Acme"), "the fresh body is on top");
    assertTrue(
        merged.contains("My own hand-written guidance that predates the split."),
        "legacy content is never lost");
    assertTrue(merged.contains(ContextMerge.MARKER_TOKEN), "a marker is introduced");
    assertTrue(merged.contains("Carried over"), "and a note explains the carry-over");
  }

  @Test
  void mergeIsLocatedByTheStableTokenEvenIfTheWordingChanged() {
    var existing = "BODY\n\n<!-- sail:personal (some future wording) -->\n\nkeep me\n";

    var merged = ContextMerge.merge(existing, BODY);

    assertTrue(merged.contains("keep me"));
    assertTrue(merged.contains("(some future wording)"), "the exact personal line is preserved");
  }

  @Test
  void mergeIsIdempotent() {
    var legacy = "# CLAUDE.md\n\nlegacy notes\n";
    var once = ContextMerge.merge(legacy, BODY);
    var twice = ContextMerge.merge(once, BODY);
    assertEquals(once, twice, "re-merging the same body changes nothing");

    var withMarker = "old body\n\n" + ContextMerge.MARKER + "\n\nmy note\n";
    var a = ContextMerge.merge(withMarker, BODY);
    var b = ContextMerge.merge(a, BODY);
    assertEquals(a, b);
  }

  @Test
  void mergeNormalizesBodyTrailingWhitespace() {
    var merged = ContextMerge.merge(null, "body\n\n\n");
    assertEquals(ContextMerge.merge(null, "body"), merged, "trailing blank lines do not drift");
  }
}
