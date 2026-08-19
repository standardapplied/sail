/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GlobalSpecViewTest {

  private static SpecStore.SpecRow row(String engagement) {
    return new SpecStore.SpecRow(
        "auth",
        "acme",
        "OAuth",
        SpecStatus.DRAFT,
        "uday",
        null,
        null,
        null,
        null,
        0,
        "uday",
        "t0",
        "t1",
        "uday",
        List.of(),
        List.of(),
        null,
        engagement);
  }

  @Test
  void anEngagedRowCarriesTheEngagementIntoTheViewAndItsMap() {
    var engagement = Engagement.of("claude-code", "full", "opus-x", "t0");

    var view = GlobalSpecView.from(row(engagement.toJson()));

    assertEquals("claude-code", view.engagement().get("agent"));
    assertEquals("full", view.engagement().get("mode"));
    assertEquals("opus-x", view.engagement().get("model"));
    assertEquals("t0", view.engagement().get("engaged_at"));
    @SuppressWarnings("unchecked")
    var mapped = (Map<String, Object>) view.toMap().get("engagement");
    assertEquals("claude-code", mapped.get("agent"));
  }

  @Test
  void aModelLessEngagementOmitsTheModelKey() {
    var engagement = Engagement.of("codex", "full", null, "t0");

    var view = GlobalSpecView.from(row(engagement.toJson()));

    assertFalse(view.engagement().containsKey("model"));
  }

  @Test
  void anUnengagedOrCorruptRowRendersNoEngagement() {
    assertNull(GlobalSpecView.from(row(null)).engagement());
    assertNull(GlobalSpecView.from(row("garbage {{{")).engagement());
    assertFalse(GlobalSpecView.from(row(null)).toMap().containsKey("engagement"));
  }
}
