/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConflictMergeTest {

  private static Map<String, Object> map(Object... kv) {
    var m = new LinkedHashMap<String, Object>();
    for (var i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void diffReportsOnlyChangedFieldsAndFlagsTheClash() {
    var base = map("title", "Auth", "status", "pending", "branch", "x");
    var mine = map("title", "Mine", "status", "in_progress", "branch", "x");
    var theirs = map("title", "Theirs", "status", "pending", "branch", "x");

    var diff = ConflictMerge.diff(base, mine, theirs, List.of("title"));

    assertEquals(2, diff.size());
    var title = diff.stream().filter(c -> c.field().equals("title")).findFirst().orElseThrow();
    assertTrue(title.clash());
    assertEquals("Auth", title.base());
    assertEquals("Mine", title.mine());
    assertEquals("Theirs", title.theirs());

    var status = diff.stream().filter(c -> c.field().equals("status")).findFirst().orElseThrow();
    assertFalse(status.clash());
  }

  @Test
  void mergeTemplateAutoMergesDisjointEditsAndDefaultsClashesToMine() {
    var base = map("title", "Auth", "status", "pending", "body", "old");
    var mine = map("title", "Mine", "status", "pending", "body", "old");
    var theirs = map("title", "Theirs", "status", "in_progress", "body", "old");

    var template = ConflictMerge.mergeTemplate(base, mine, theirs, List.of("title"));
    var parsed = ConflictMerge.parseTemplate(template);

    assertEquals("Mine", parsed.get("title"), "clash defaults to mine");
    assertEquals("in_progress", parsed.get("status"), "disjoint remote edit auto-merged");
    assertTrue(template.contains("theirs = Theirs"), "theirs shown for reference");
  }

  @Test
  void mergeTemplateToleratesAnAbsentBase() {
    var mine = map("title", "Mine");
    var theirs = map("title", "Theirs");
    var parsed =
        ConflictMerge.parseTemplate(
            ConflictMerge.mergeTemplate(null, mine, theirs, List.of("title")));
    assertEquals("Mine", parsed.get("title"));
  }

  @Test
  void mergeTemplateRoundTripsAMultiLineBody() {
    var base = map("title", "Auth", "body", "one\ntwo");
    var mine = map("title", "Mine", "body", "one\ntwo");
    var theirs = map("title", "Theirs", "body", "one\ntwo");

    var parsed =
        ConflictMerge.parseTemplate(
            ConflictMerge.mergeTemplate(base, mine, theirs, List.of("title")));

    assertEquals("one\ntwo", parsed.get("body"));
  }

  @Test
  void editedTemplateParsesBackToTheChosenValues() {
    var edited =
        """
        # Resolve this conflict, then save and close the editor.
        title: My final answer
        status: done
        """;
    var parsed = ConflictMerge.parseTemplate(edited);
    assertEquals("My final answer", parsed.get("title"));
    assertEquals("done", parsed.get("status"));
  }

  @Test
  void diffToleratesADeletedSideAndAbsentBase() {
    var theirs = map("title", "Theirs", "status", "pending");
    var diff = ConflictMerge.diff(null, null, theirs, List.of("<deleted>"));

    var title = diff.stream().filter(c -> c.field().equals("title")).findFirst().orElseThrow();
    assertNull(title.base());
    assertNull(title.mine());
    assertEquals("Theirs", title.theirs());
    assertFalse(title.clash());
  }

  @Test
  void renderHandlesNullAndLists() {
    assertEquals("∅", ConflictMerge.render(null));
    assertEquals("[]", ConflictMerge.render(List.of()));
    assertEquals("a, b", ConflictMerge.render(List.of("a", "b")));
    assertEquals("3", ConflictMerge.render(3));
  }
}
