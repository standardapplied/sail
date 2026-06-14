/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecLifecycleReactorTest {

  private static final String PROJECT = "light-grid";
  private static final String SPECS_DIR = "/home/dev/workspace/specs";
  private static final String SPEC = "oauth-flow";

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new SpecLifecycleReactor(null, p -> SPECS_DIR));
  }

  @Test
  void constructorRejectsNullResolver() {
    assertThrows(
        NullPointerException.class,
        () -> new SpecLifecycleReactor(new ScriptedShellExecutor(), null));
  }

  @Test
  void nameIsStable() {
    var reactor = new SpecLifecycleReactor(new ScriptedShellExecutor(), p -> SPECS_DIR);
    assertEquals("spec-lifecycle", reactor.name());
  }

  @Test
  void filterAcceptsHandledTypesWithSpec() {
    var reactor = new SpecLifecycleReactor(new ScriptedShellExecutor(), p -> SPECS_DIR);
    assertTrue(
        reactor
            .filter()
            .test(Event.of(PROJECT, SPEC, "agent_session_started", "claude-code", "h")));
    assertTrue(
        reactor
            .filter()
            .test(Event.of(PROJECT, SPEC, "agent_session_stopped", "claude-code", "h")));
    assertTrue(
        reactor
            .filter()
            .test(Event.of(PROJECT, SPEC, "agent_session_completed", "claude-code", "h")));
  }

  @Test
  void filterRejectsEventsWithoutSpec() {
    var reactor = new SpecLifecycleReactor(new ScriptedShellExecutor(), p -> SPECS_DIR);
    assertFalse(reactor.filter().test(Event.of(PROJECT, null, "agent_session_started", "a", "h")));
  }

  @Test
  void filterRejectsUnhandledTypes() {
    var reactor = new SpecLifecycleReactor(new ScriptedShellExecutor(), p -> SPECS_DIR);
    assertFalse(reactor.filter().test(Event.of(PROJECT, SPEC, "spec_dispatched", "sail", "h")));
    assertFalse(reactor.filter().test(Event.of(PROJECT, SPEC, "snapshot_created", "sail", "h")));
  }

  @Test
  void unknownProjectSkipped() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var reactor = new SpecLifecycleReactor(shell, p -> null);

    reactor.onEvent(
        Event.of(PROJECT, SPEC, "agent_session_started", "claude-code", "h").withId(1L));

    assertTrue(shell.invocations().isEmpty());
  }

  @Test
  void startedAppendsAudit() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    reactor.onEvent(
        Event.of(
                PROJECT, SPEC, "agent_session_started", "claude-code", "host-01", Map.of("pid", 42))
            .withId(1L));

    var sawAuditAppend =
        shell.invocations().stream()
            .anyMatch(c -> c.contains("audit.jsonl") && c.contains("\"event\": \"started\""));
    assertTrue(sawAuditAppend, "should append started audit; saw: " + shell.invocations());
    var sawPid = shell.invocations().stream().anyMatch(c -> c.contains("\"pid\": 42"));
    assertTrue(sawPid, "audit line should include pid");
  }

  @Test
  void stoppedTransitionsInProgressToReview() {
    var stored = "{id: " + SPEC + ", title: OAuth, status: in_progress}";
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat " + SPECS_DIR + "/" + SPEC + "/spec.yaml", stored);
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    reactor.onEvent(
        Event.of(PROJECT, SPEC, "agent_session_stopped", "claude-code", "host-01").withId(1L));

    var sawStatusWrite =
        shell.invocations().stream()
            .anyMatch(c -> c.contains("printf '%s' ") && c.contains("spec.yaml"));
    var sawAuditAppend =
        shell.invocations().stream()
            .anyMatch(c -> c.contains("audit.jsonl") && c.contains("\"event\": \"stopped\""));
    assertTrue(sawStatusWrite, "should write spec.yaml; saw: " + shell.invocations());
    assertTrue(sawAuditAppend, "should append stopped audit; saw: " + shell.invocations());
  }

  @Test
  void stoppedLeavesNonProgressStatusAlone() {
    var stored = "{id: " + SPEC + ", title: OAuth, status: done}";
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat " + SPECS_DIR + "/" + SPEC + "/spec.yaml", stored);
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    reactor.onEvent(
        Event.of(PROJECT, SPEC, "agent_session_stopped", "claude-code", "host-01").withId(1L));

    var sawStatusWrite =
        shell.invocations().stream()
            .anyMatch(c -> c.contains("printf '%s' ") && c.contains("spec.yaml"));
    assertFalse(sawStatusWrite, "status was not in_progress; should not be overwritten");
    var sawAuditAppend = shell.invocations().stream().anyMatch(c -> c.contains("audit.jsonl"));
    assertTrue(sawAuditAppend, "audit append should still happen");
  }

  @Test
  void completedAppendsAudit() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    reactor.onEvent(
        Event.of(
                PROJECT,
                SPEC,
                "agent_session_completed",
                "claude-code",
                "host-01",
                Map.of("note", "graceful"))
            .withId(1L));

    var sawAuditAppend =
        shell.invocations().stream()
            .anyMatch(c -> c.contains("audit.jsonl") && c.contains("\"event\": \"completed\""));
    var sawNote = shell.invocations().stream().anyMatch(c -> c.contains("\"note\": \"graceful\""));
    assertTrue(sawAuditAppend);
    assertTrue(sawNote);
  }

  @Test
  void failuresAreSwallowed() {
    var shell = new ScriptedShellExecutor().onFail("audit.jsonl", "denied");
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    assertDoesNotThrow(
        () ->
            reactor.onEvent(
                Event.of(PROJECT, SPEC, "agent_session_started", "claude-code", "h").withId(1L)));
  }

  @Test
  void blankSpecsDirSkipped() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var reactor = new SpecLifecycleReactor(shell, p -> "");

    reactor.onEvent(
        Event.of(PROJECT, SPEC, "agent_session_completed", "claude-code", "h").withId(1L));

    assertTrue(shell.invocations().isEmpty());
  }

  @Test
  void withDefaultsConstructs() {
    assertNotNull(SpecLifecycleReactor.withDefaults());
    assertNotNull(SpecLifecycleReactor.defaultSpecsDirLookup());
    assertEquals(0, List.of().size());
  }

  @Test
  void lookupSpecsDirReturnsNullForUnknownProject() {
    assertNull(SpecLifecycleReactor.lookupSpecsDir("definitely-does-not-exist-anywhere"));
  }

  @Test
  void lookupSpecsDirAtReturnsNullForNullPath() {
    assertNull(SpecLifecycleReactor.lookupSpecsDirAt("p", null));
  }

  @Test
  void lookupSpecsDirAtReturnsNullForMissingPath(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
    assertNull(SpecLifecycleReactor.lookupSpecsDirAt("p", dir.resolve("missing.yaml")));
  }

  @Test
  void lookupSpecsDirAtReturnsContainerPathForValidYaml(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    var yaml =
        """
        name: light-grid
        ssh:
          user: dev
        agent:
          type: claude-code
          specs_dir: specs
        """;
    var sailYaml = dir.resolve("sail.yaml");
    java.nio.file.Files.writeString(sailYaml, yaml);

    assertEquals(
        "/home/dev/workspace/specs", SpecLifecycleReactor.lookupSpecsDirAt("light-grid", sailYaml));
  }

  @Test
  void lookupSpecsDirAtReturnsNullWhenAgentBlockMissing(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    var sailYaml = dir.resolve("sail.yaml");
    java.nio.file.Files.writeString(sailYaml, "name: bare\n");
    assertNull(SpecLifecycleReactor.lookupSpecsDirAt("bare", sailYaml));
  }

  @Test
  void lookupSpecsDirAtReturnsNullOnParseFailure(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    var sailYaml = dir.resolve("sail.yaml");
    java.nio.file.Files.writeString(sailYaml, "not: valid: yaml: here:");
    assertNull(SpecLifecycleReactor.lookupSpecsDirAt("p", sailYaml));
  }

  @Test
  void pidOfHandlesDoubleValues() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    reactor.onEvent(
        Event.of(PROJECT, SPEC, "agent_session_started", "claude-code", "h", Map.of("pid", 1234.0d))
            .withId(1L));

    var sawPid = shell.invocations().stream().anyMatch(c -> c.contains("\"pid\": 1234"));
    assertTrue(sawPid, "Number/Double pid should be coerced to int");
  }

  @Test
  void pidOfHandlesLongValues() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    reactor.onEvent(
        Event.of(PROJECT, SPEC, "agent_session_started", "claude-code", "h", Map.of("pid", 9999L))
            .withId(1L));

    var sawPid = shell.invocations().stream().anyMatch(c -> c.contains("\"pid\": 9999"));
    assertTrue(sawPid, "Long pid should be coerced to int");
  }

  @Test
  void pidOfHandlesStringValues() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    reactor.onEvent(
        Event.of(PROJECT, SPEC, "agent_session_started", "claude-code", "h", Map.of("pid", "7777"))
            .withId(1L));

    var sawPid = shell.invocations().stream().anyMatch(c -> c.contains("\"pid\": 7777"));
    assertTrue(sawPid, "pid encoded as string should be coerced to int");
  }

  @Test
  void pidOfFallsBackForUnrecognizedType() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    reactor.onEvent(
        Event.of(
                PROJECT,
                SPEC,
                "agent_session_started",
                "claude-code",
                "h",
                Map.of("pid", List.of(1, 2)))
            .withId(1L));

    var sawPidKey = shell.invocations().stream().anyMatch(c -> c.contains("\"pid\""));
    assertFalse(sawPidKey, "unrecognized pid type should produce no pid field");
  }

  @Test
  void pidOfRejectsNegativeString() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    reactor.onEvent(
        Event.of(PROJECT, SPEC, "agent_session_started", "claude-code", "h", Map.of("pid", "-1"))
            .withId(1L));

    var sawPidKey = shell.invocations().stream().anyMatch(c -> c.contains("\"pid\""));
    assertFalse(sawPidKey, "non-positive pid should be skipped");
  }

  @Test
  void pidOfRejectsGarbageString() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var reactor = new SpecLifecycleReactor(shell, p -> SPECS_DIR);

    reactor.onEvent(
        Event.of(
                PROJECT,
                SPEC,
                "agent_session_started",
                "claude-code",
                "h",
                Map.of("pid", "not-a-number"))
            .withId(1L));

    var sawPidKey = shell.invocations().stream().anyMatch(c -> c.contains("\"pid\""));
    assertFalse(sawPidKey);
  }
}
