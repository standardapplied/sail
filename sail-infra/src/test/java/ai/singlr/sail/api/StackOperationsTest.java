/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ProjectServer;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StackOperationsTest {

  static final String ACME =
      "name: acme\nservices:\n  postgres:\n    image: postgres:16\n    ports: [5432]\n"
          + "processes:\n  web:\n    command: npm run dev\n    port: 3000\n";
  static final String RUNNING = "[{\"name\": \"acme\", \"status\": \"Running\"}]";
  static final String LISTENING =
      "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\nLISTEN 0 511 0.0.0.0:3000 0.0.0.0:*\n---\n"
          + "Id=sail-process-web.service\nActiveState=active\nSubState=running\nExecMainStatus=0\n";

  private final ScriptedShellExecutor shell =
      new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
          .onOk("incus list", RUNNING)
          .onOk("ss -ltnH", LISTENING);
  private final List<Duration> paused = new ArrayList<>();

  static StackOperations stack(ShellExec shell, ProjectServer.Pause pause) {
    var definition = TestDefinitions.yaml(ACME);
    return new StackOperations(
        shell,
        new ProjectLoader(
            shell, project -> "acme".equals(project) ? Optional.of(definition) : Optional.empty()),
        pause,
        System::nanoTime);
  }

  private StackOperations stack() {
    return stack(shell, paused::add);
  }

  private static Actor member(ProjectScope scope) {
    return new Actor("uday", Role.MEMBER, Actor.Lane.CLI).withScope(scope);
  }

  @Test
  void serveBringsTheStackUpAndAnswersEveryPort() throws Exception {
    var ports =
        Acting.by(member(ProjectScope.all()), () -> stack().serve("acme", Duration.ofSeconds(60)));

    assertEquals(true, ports.allListening());
    assertEquals(
        Map.of(
            "project",
            "acme",
            "all_listening",
            true,
            "restarted",
            List.of(),
            "ports",
            List.of(
                port("postgres", 5432, "service", "listening"),
                port("web", 3000, "process", "listening"))),
        ports.toMap());
  }

  private static Map<String, Object> port(String name, int port, String kind, String state) {
    var row = new LinkedHashMap<String, Object>();
    row.put("name", name);
    row.put("port", port);
    row.put("kind", kind);
    row.put("state", state);
    row.put("exit_code", null);
    row.put("detail", null);
    return row;
  }

  @Test
  void aReadOnlyCallerMayReadThePortsButNotServeOrStop() throws Exception {
    var viewer = new Actor("vic", Role.VIEWER, Actor.Lane.CLI);

    assertEquals(2, Acting.by(viewer, () -> stack().ports("acme")).ports().size());
    var served =
        assertThrows(
            ApiException.class,
            () -> Acting.by(viewer, () -> stack().serve("acme", Duration.ofSeconds(60))));
    var stopped =
        assertThrows(ApiException.class, () -> Acting.by(viewer, () -> stack().stop("acme")));

    assertEquals(403, served.status());
    assertEquals(403, stopped.status());
    assertEquals(
        List.of(),
        shell.invocations().stream()
            .filter(c -> c.matches("(?s).*systemctl --user (enable|start|restart|disable).*"))
            .toList(),
        "nothing was started or stopped");
  }

  @Test
  void aProjectOutsideTheCallersScopeIsOneTheCatalogDoesNotHold() {
    var scoped = member(ProjectScope.of(List.of("zeta")));

    for (var call :
        List.<StackCall>of(
            stack -> stack.serve("acme", Duration.ofSeconds(60)),
            stack -> stack.ports("acme"),
            stack -> stack.stop("acme"))) {
      var refused =
          assertThrows(ApiException.class, () -> Acting.by(scoped, () -> call.on(stack())));
      assertEquals("Project 'acme' is not in the catalog.", refused.getMessage());
    }
    assertEquals(List.of(), shell.invocations());
  }

  private interface StackCall {
    StackOperations.Ports on(StackOperations stack) throws Exception;
  }

  @Test
  void aTimeoutIsOneSecondToTenMinutes() {
    for (var timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(601))) {
      var refused =
          assertThrows(
              IllegalArgumentException.class,
              () -> Acting.by(member(ProjectScope.all()), () -> stack().serve("acme", timeout)));
      assertEquals(
          "A serve's timeout is 1 to 600 seconds, got: " + timeout.toSeconds(),
          refused.getMessage());
    }
  }

  @Test
  void aStoppedContainerIsRefusedSayingHowToStartIt() {
    shell.onOk("incus list", "[{\"name\": \"acme\", \"status\": \"Stopped\"}]");

    var refused =
        assertThrows(
            ApiException.class,
            () -> Acting.by(member(ProjectScope.all()), () -> stack().ports("acme")));

    assertEquals("Project 'acme' is stopped.", refused.getMessage());
  }

  @Test
  void stopStopsTheProcessesAndAnswersThePortsAfter() throws Exception {
    shell.onOk("basename", "sail-process-web\n");

    var ports = Acting.by(member(ProjectScope.all()), () -> stack().stop("acme"));

    assertEquals(
        List.of("systemctl --user disable --now sail-process-web.service"),
        shell.invocations().stream()
            .filter(c -> c.contains("systemctl --user disable"))
            .map(c -> c.substring(c.indexOf("systemctl")))
            .toList());
    assertEquals(2, ports.ports().size());
  }
}
