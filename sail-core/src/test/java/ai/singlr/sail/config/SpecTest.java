/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecTest {

  @Test
  void parsesCompleteSpec() {
    var map =
        Map.<String, Object>of(
            "id", "oauth-flow",
            "title", "Implement OAuth",
            "status", "in_progress",
            "assignee", "alice",
            "depends_on", List.of("setup-db"),
            "repo", "chorus",
            "branch", "feat/oauth");

    var spec = Spec.fromMap(map);

    assertEquals("oauth-flow", spec.id());
    assertEquals("Implement OAuth", spec.title());
    assertEquals(SpecStatus.IN_PROGRESS, spec.status());
    assertEquals("alice", spec.assignee());
    assertEquals(List.of("setup-db"), spec.dependsOn());
    assertEquals(List.of("chorus"), spec.repos());
    assertEquals("feat/oauth", spec.branch());
  }

  @Test
  void defaultsStatusToPending() {
    var spec = Spec.fromMap(Map.of("id", "task1"));

    assertEquals(SpecStatus.PENDING, spec.status());
  }

  @Test
  void defaultsTitleToEmpty() {
    var spec = Spec.fromMap(Map.of("id", "task1"));

    assertEquals("", spec.title());
  }

  @Test
  void defaultsDependsOnToEmptyList() {
    var spec = Spec.fromMap(Map.of("id", "task1"));

    assertTrue(spec.dependsOn().isEmpty());
  }

  @Test
  void defaultsReposToEmptyList() {
    var spec = Spec.fromMap(Map.of("id", "task1"));

    assertTrue(spec.repos().isEmpty());
  }

  @Test
  void nullableFieldsAreNull() {
    var spec = Spec.fromMap(Map.of("id", "task1"));

    assertNull(spec.assignee());
    assertNull(spec.branch());
  }

  @Test
  void throwsOnMissingId() {
    assertThrows(IllegalArgumentException.class, () -> Spec.fromMap(Map.of("title", "No ID")));
  }

  @Test
  void throwsOnBlankId() {
    assertThrows(
        IllegalArgumentException.class, () -> Spec.fromMap(Map.of("id", "  ", "title", "Blank")));
  }

  @Test
  void throwsOnNullId() {
    var map = new HashMap<String, Object>();
    map.put("id", null);
    assertThrows(IllegalArgumentException.class, () -> Spec.fromMap(map));
  }

  @Test
  void toMapContainsAllFields() {
    var spec =
        new Spec("auth", "Implement Auth", SpecStatus.DONE, "bob", List.of("setup"), "feat/auth");

    var map = spec.toMap();

    assertEquals("auth", map.get("id"));
    assertEquals("Implement Auth", map.get("title"));
    assertEquals("done", map.get("status"));
    assertEquals("bob", map.get("assignee"));
    assertEquals(List.of("setup"), map.get("depends_on"));
    assertEquals("feat/auth", map.get("branch"));
  }

  @Test
  void parsesAgent() {
    var spec = Spec.fromMap(Map.of("id", "auth", "agent", "codex"));

    assertEquals("codex", spec.agent());
  }

  @Test
  void rejectsUnknownAgent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Spec.fromMap(Map.of("id", "auth", "agent", "unknown-agent")));
  }

  @Test
  void parsesModelAndReasoningEffort() {
    var spec = Spec.fromMap(Map.of("id", "auth", "model", "gpt-5.5", "reasoning_effort", "high"));

    assertEquals("gpt-5.5", spec.model());
    assertEquals("high", spec.reasoningEffort());
  }

  @Test
  void rejectsUnsafeModel() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Spec.fromMap(Map.of("id", "auth", "model", "gpt-5.5; rm -rf /")));
  }

  @Test
  void rejectsUnknownReasoningEffort() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Spec.fromMap(Map.of("id", "auth", "reasoning_effort", "huge")));
  }

  @Test
  void toMapWritesSingleRepoAsRepo() {
    var spec =
        new Spec(
            "auth",
            "Implement Auth",
            SpecStatus.DONE,
            "bob",
            List.of("setup"),
            List.of("chorus"),
            "feat/auth");

    var map = spec.toMap();

    assertEquals("chorus", map.get("repo"));
    assertFalse(map.containsKey("repos"));
  }

  @Test
  void parsesMultipleRepos() {
    var spec = Spec.fromMap(Map.of("id", "auth", "repos", List.of("sing", "chorus")));

    assertEquals(List.of("sing", "chorus"), spec.repos());
  }

  @Test
  void rejectsRepoAndReposTogether() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Spec.fromMap(Map.of("id", "auth", "repo", "sing", "repos", List.of("chorus"))));
  }

  @Test
  void toMapOmitsNullAndEmptyFields() {
    var spec = new Spec("auth", "", SpecStatus.PENDING, null, List.of(), null);

    var map = spec.toMap();

    assertEquals("auth", map.get("id"));
    assertEquals("pending", map.get("status"));
    assertFalse(map.containsKey("title"));
    assertFalse(map.containsKey("assignee"));
    assertFalse(map.containsKey("depends_on"));
    assertFalse(map.containsKey("branch"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void roundTrips() {
    var spec =
        new Spec("auth", "Implement Auth", SpecStatus.REVIEW, "alice", List.of("db"), "feat/auth");

    var parsed = Spec.fromMap(spec.toMap());

    assertEquals(spec.id(), parsed.id());
    assertEquals(spec.title(), parsed.title());
    assertEquals(spec.status(), parsed.status());
    assertEquals(spec.assignee(), parsed.assignee());
    assertEquals(spec.dependsOn(), parsed.dependsOn());
    assertEquals(spec.repos(), parsed.repos());
    assertEquals(spec.agent(), parsed.agent());
    assertEquals(spec.model(), parsed.model());
    assertEquals(spec.reasoningEffort(), parsed.reasoningEffort());
    assertEquals(spec.branch(), parsed.branch());
  }

  @Test
  void dependsOnListIsImmutable() {
    var map = new HashMap<String, Object>();
    map.put("id", "test");
    map.put("depends_on", new java.util.ArrayList<>(List.of("a", "b")));

    var spec = Spec.fromMap(map);

    assertThrows(UnsupportedOperationException.class, () -> spec.dependsOn().add("c"));
  }

  @Test
  void multipleDependencies() {
    var spec =
        Spec.fromMap(Map.of("id", "final", "depends_on", List.of("step1", "step2", "step3")));

    assertEquals(3, spec.dependsOn().size());
    assertEquals(List.of("step1", "step2", "step3"), spec.dependsOn());
  }
}
