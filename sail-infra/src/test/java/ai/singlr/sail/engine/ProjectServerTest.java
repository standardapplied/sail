/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ProjectServer.Kind;
import ai.singlr.sail.engine.ProjectServer.Port;
import ai.singlr.sail.engine.ProjectServer.State;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectServerTest {

  private static final String ACME =
      """
      name: acme
      repos:
        - url: https://github.com/acme/web.git
          path: web
      services:
        postgres:
          image: postgres:16
          ports: [5432]
      processes:
        web:
          command: npm run dev
          port: 3000
          env:
            NODE_ENV: development
        worker:
          command: node worker.js
      """;

  /**
   * The container as {@code serve} sees it: files written are kept, so a second write of the same
   * text is "same", units listed are the ones written, and each look answers the next of the looks
   * queued, the last one again once they run out.
   */
  private static final class Container implements ShellExec {
    final List<List<String>> commands = new ArrayList<>();
    final Map<String, String> files = new HashMap<>();
    final Deque<String> looks = new ArrayDeque<>();
    String lastLine = "Error: Cannot find module 'next'";
    String failing;

    Container looking(String... answers) {
      looks.addAll(List.of(answers));
      return this;
    }

    @Override
    public Result exec(List<String> command) {
      commands.add(List.copyOf(command));
      var inner = command.subList(command.indexOf("--") + 1, command.size());
      var joined = String.join(" ", inner);
      if (failing != null && joined.contains(failing)) {
        return new Result(1, "", "refused");
      }
      if (joined.contains("printf '%s'")) {
        var content = inner.get(inner.size() - 2);
        var path = inner.getLast();
        var same = content.equals(files.get(path));
        files.put(path, content);
        return new Result(0, same ? "same\n" : "written\n", "");
      }
      if (joined.contains("ss -ltnH")) {
        var look = looks.size() > 1 ? looks.poll() : looks.peek();
        return new Result(0, look == null ? "---\n" : look, "");
      }
      if (joined.contains("basename")) {
        var units = new StringBuilder();
        files.keySet().stream()
            .filter(path -> path.startsWith(ProjectServer.UNITS_DIR + "/"))
            .sorted()
            .forEach(
                path ->
                    units
                        .append(
                            path.substring(
                                ProjectServer.UNITS_DIR.length() + 1,
                                path.length() - ".service".length()))
                        .append('\n'));
        return new Result(0, units.toString(), "");
      }
      if (joined.contains("awk -v mark")) {
        return new Result(0, lastLine + "\n", "");
      }
      if (inner.getFirst().equals("rm")) {
        inner.subList(2, inner.size()).forEach(files::remove);
      }
      return new Result(0, "", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }

    List<String> ran(String first) {
      return commands.stream()
          .map(command -> command.subList(command.indexOf("--") + 1, command.size()))
          .filter(inner -> String.join(" ", inner).startsWith(first))
          .map(inner -> String.join(" ", inner))
          .toList();
    }
  }

  private static String look(String ss, String... units) {
    var shown = new StringBuilder(ss).append("---\n");
    for (var unit : units) {
      shown.append(unit).append("\n\n");
    }
    return shown.toString();
  }

  private static String unit(String name, String active, String sub, int status) {
    return "Id=sail-process-%s.service\nActiveState=%s\nSubState=%s\nExecMainStatus=%d"
        .formatted(name, active, sub, status);
  }

  private static final String BOTH_LISTEN =
      look(
          "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\nLISTEN 0 511 [::]:3000 [::]:*\n",
          unit("web", "active", "running", 0),
          unit("worker", "active", "running", 0));

  private final List<Duration> paused = new ArrayList<>();
  private long now;

  private ProjectServer server(Container container) {
    return new ProjectServer(
        container,
        new ProjectApplier(
            container, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)),
        duration -> {
          paused.add(duration);
          now += duration.toNanos();
        },
        () -> now);
  }

  private static ProjectDefinition acme() {
    return TestDefinitions.yaml(ACME);
  }

  @Test
  void serveStartsTheServicesAndEveryProcessAndSaysWhatListensWhere() throws Exception {
    var container = new Container().looking(BOTH_LISTEN);

    var report = server(container).serve("acme", acme(), Duration.ofSeconds(60));

    assertEquals(
        List.of(
            new Port("postgres", 5432, Kind.SERVICE, State.LISTENING, null, ""),
            new Port("web", 3000, Kind.PROCESS, State.LISTENING, null, "")),
        report.ports());
    assertTrue(report.allListening());
    assertEquals(List.of("postgres  5432  listening", "web       3000  listening"), report.lines());
    assertEquals(List.of("podman start postgres"), container.ran("podman start"));
    assertEquals(
        List.of(
            "systemctl --user daemon-reload",
            "systemctl --user enable sail-process-web.service sail-process-worker.service",
            "systemctl --user reset-failed sail-process-web.service sail-process-worker.service",
            "systemctl --user restart sail-process-web.service sail-process-worker.service"),
        container.ran("systemctl"));
    assertEquals(List.of("web", "worker"), report.restarted(), "both were new");
    assertEquals(List.of(), paused, "everything listened at the first look");
  }

  @Test
  void theScriptRunsTheCommandFromItsWorkdirWithItsEnvironmentAndTheUnitLogsIt() throws Exception {
    var container = new Container().looking(BOTH_LISTEN);

    server(container).serve("acme", acme(), Duration.ofSeconds(60));

    assertEquals(
        """
        # Generated by sail: every serve writes it again.
        cd /home/dev/workspace/web || exit 1
        export NODE_ENV=development
        npm run dev
        """,
        container.files.get("/home/dev/.sail/processes/web.sh"));
    assertEquals(
        """
        [Unit]
        Description=sail process web

        [Service]
        ExecStartPre=/bin/echo sail: starting web
        ExecStart=/bin/bash -l /home/dev/.sail/processes/web.sh
        Restart=on-failure
        RestartSec=2
        StandardOutput=append:/home/dev/.sail/processes/web.log
        StandardError=append:/home/dev/.sail/processes/web.log

        [Install]
        WantedBy=default.target
        """,
        container.files.get("/home/dev/.config/systemd/user/sail-process-web.service"));
  }

  @Test
  void aValueThatIsNotOneShellWordIsQuotedAndAWorkdirOfTheWorkspaceIsTheWorkspace() {
    var config =
        TestDefinitions.yaml(
            "name: acme\nprocesses:\n  web:\n    command: x\n    workdir: .\n    env:\n      GREETING: \"it's $HOME\"\n");

    assertEquals(
        "# Generated by sail: every serve writes it again.\n"
            + "cd /home/dev/workspace || exit 1\n"
            + "export GREETING='it'\\''s $HOME'\n"
            + "x\n",
        ProjectServer.script(config, config.processes().get("web")));
    var noRepo = TestDefinitions.yaml("name: acme\nprocesses:\n  web:\n    command: x\n");
    assertEquals(
        "/home/dev/workspace", ProjectServer.workdirOf(noRepo, noRepo.processes().get("web")));
  }

  @Test
  void serveAgainRestartsNothingAndAChangedCommandRestartsOnlyItsProcess() throws Exception {
    var container = new Container().looking(BOTH_LISTEN);
    var server = server(container);
    server.serve("acme", acme(), Duration.ofSeconds(60));
    container.commands.clear();

    var again = server.serve("acme", acme(), Duration.ofSeconds(60));

    assertEquals(
        List.of(
            "systemctl --user daemon-reload",
            "systemctl --user enable sail-process-web.service sail-process-worker.service",
            "systemctl --user reset-failed sail-process-web.service sail-process-worker.service",
            "systemctl --user start sail-process-web.service sail-process-worker.service"),
        container.ran("systemctl"));
    assertEquals(List.of(), again.restarted());
    container.commands.clear();

    var changed =
        server.serve(
            "acme",
            TestDefinitions.yaml(ACME.replace("npm run dev", "npm run start")),
            Duration.ofSeconds(60));

    assertEquals(
        List.of(
            "systemctl --user daemon-reload",
            "systemctl --user enable sail-process-web.service sail-process-worker.service",
            "systemctl --user reset-failed sail-process-web.service sail-process-worker.service",
            "systemctl --user restart sail-process-web.service",
            "systemctl --user start sail-process-worker.service"),
        container.ran("systemctl"));
    assertEquals(List.of("web"), changed.restarted());
  }

  @Test
  void aProcessThatExitsBeforeItListensIsReportedWithItsCodeAndLastLine() throws Exception {
    var container =
        new Container()
            .looking(
                look(
                    "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\n",
                    unit("web", "failed", "failed", 1),
                    unit("worker", "active", "running", 0)));

    var report = server(container).serve("acme", acme(), Duration.ofSeconds(60));

    assertEquals(
        new Port("web", 3000, Kind.PROCESS, State.EXITED, 1, "Error: Cannot find module 'next'"),
        report.ports().getLast());
    assertFalse(report.allListening());
    assertEquals(
        "web       3000  exited 1: Error: Cannot find module 'next'", report.lines().getLast());
  }

  @Test
  void aProcessRestartingAfterItCrashedIsExitedToo() throws Exception {
    var container =
        new Container()
            .looking(
                look(
                    "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\n",
                    unit("web", "activating", "auto-restart", 2),
                    unit("worker", "active", "running", 0)));

    var web = server(container).serve("acme", acme(), Duration.ofSeconds(60)).ports().getLast();

    assertEquals(State.EXITED, web.state());
    assertEquals(2, web.exitCode());
  }

  @Test
  void aPortThatNeverListensWithinTheTimeoutIsTimedOutAfterEveryLookAndItsUnitIsLeftRunning()
      throws Exception {
    var starting =
        look(
            "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\n",
            unit("web", "active", "running", 0),
            unit("worker", "active", "running", 0));
    var container = new Container().looking(starting);

    var report = server(container).serve("acme", acme(), Duration.ofSeconds(5));

    assertEquals(
        new Port("web", 3000, Kind.PROCESS, State.TIMEOUT, null, ""), report.ports().getLast());
    assertEquals(11, container.ran("sh -c command -v ss").size(), "a look every half second");
    assertEquals(10, paused.size(), "no pause past the deadline");
    assertEquals(List.of(), container.ran("systemctl --user disable"), "the unit keeps running");
  }

  @Test
  void aProcessThisServeStartedThatIsNoLongerRunningExitedWhateverItsStatus() throws Exception {
    var container =
        new Container()
            .looking(
                look(
                    "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\n",
                    unit("web", "inactive", "dead", 0),
                    unit("worker", "active", "running", 0)));

    var web = server(container).serve("acme", acme(), Duration.ofSeconds(60)).ports().getLast();

    assertEquals(State.EXITED, web.state());
    assertEquals(0, web.exitCode());
    assertEquals(List.of(), paused, "an exit settles the wait at once");
  }

  @Test
  void aContainerWithoutSsSaysSo() {
    var container = new Container().looking(BOTH_LISTEN);
    container.failing = "command -v ss";

    var failed =
        assertThrows(
            IOException.class,
            () -> server(container).serve("acme", acme(), Duration.ofSeconds(1)));

    assertEquals("Failed to look at the ports of acme: refused", failed.getMessage());
  }

  @Test
  void aPortThatListensAtALaterLookEndsTheWaitThere() throws Exception {
    var starting =
        look(
            "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\n",
            unit("web", "activating", "start", 0),
            unit("worker", "active", "running", 0));
    var container = new Container().looking(starting, starting, BOTH_LISTEN);

    var report = server(container).serve("acme", acme(), Duration.ofSeconds(60));

    assertTrue(report.allListening());
    assertEquals(List.of(ProjectServer.LOOK_EVERY, ProjectServer.LOOK_EVERY), paused);
  }

  @Test
  void aProcessNoLongerDeclaredIsStoppedAndItsUnitAndScriptRemoved() throws Exception {
    var container = new Container().looking(BOTH_LISTEN);
    var server = server(container);
    server.serve("acme", acme(), Duration.ofSeconds(60));
    container.commands.clear();

    server.serve(
        "acme",
        TestDefinitions.yaml(ACME.substring(0, ACME.indexOf("  worker:"))),
        Duration.ofSeconds(60));

    assertEquals(
        List.of("systemctl --user disable --now sail-process-worker.service"),
        container.ran("systemctl --user disable"));
    assertEquals(
        List.of(
            "rm -f /home/dev/.config/systemd/user/sail-process-worker.service"
                + " /home/dev/.sail/processes/worker.sh"),
        container.ran("rm -f"));
    assertFalse(
        container.files.containsKey("/home/dev/.config/systemd/user/sail-process-worker.service"));
  }

  @Test
  void stopDisablesEveryProcessAndLeavesTheServices() throws Exception {
    var container = new Container().looking(BOTH_LISTEN);
    var server = server(container);
    server.serve("acme", acme(), Duration.ofSeconds(60));
    container.commands.clear();

    server.stop("acme");

    assertEquals(
        List.of(
            "systemctl --user disable --now sail-process-web.service"
                + " sail-process-worker.service"),
        container.ran("systemctl"));
    assertEquals(List.of(), container.ran("podman"));
  }

  @Test
  void portsLooksWithoutStartingAnything() throws Exception {
    var container =
        new Container()
            .looking(
                look(
                    "", unit("web", "inactive", "dead", 0), unit("worker", "inactive", "dead", 0)));

    var report = server(container).ports("acme", acme());

    assertEquals(
        List.of(
            new Port("postgres", 5432, Kind.SERVICE, State.STOPPED, null, ""),
            new Port("web", 3000, Kind.PROCESS, State.STOPPED, null, "")),
        report.ports());
    assertEquals(List.of(), container.ran("systemctl"));
    assertEquals(List.of(), container.ran("podman"));
  }

  @Test
  void aStoppedUnitThatOnceFailedIsStoppedNotExited() throws Exception {
    var container =
        new Container()
            .looking(
                look(
                    "", unit("web", "inactive", "dead", 1), unit("worker", "inactive", "dead", 0)));

    var web = server(container).ports("acme", acme()).ports().getLast();

    assertEquals(new Port("web", 3000, Kind.PROCESS, State.STOPPED, null, ""), web);
  }

  @Test
  void aDefinitionWithoutProcessesServesItsServicesAsBefore() throws Exception {
    var container = new Container().looking(look("LISTEN 0 4096 *:5432 *:*\n"));
    var config =
        TestDefinitions.yaml(
            "name: acme\nservices:\n  postgres:\n    image: postgres:16\n    ports: [5432]\n");

    var report = server(container).serve("acme", config, Duration.ofSeconds(60));

    assertEquals(List.of("postgres  5432  listening"), report.lines());
    assertEquals(List.of(), container.ran("systemctl"));
  }

  @Test
  void eachStepThatFailsSaysWhichAndForWhichProject() {
    for (var step :
        Map.of(
                "podman start", "Failed to start the services in acme: refused",
                "printf '%s'", "Failed to write /home/dev/.sail/processes/web.sh in acme: refused",
                "basename", "Failed to list the process units of acme: refused",
                "daemon-reload", "Failed to reload the user units in acme: refused",
                "command -v ss", "Failed to look at the ports of acme: refused")
            .entrySet()) {
      var container = new Container().looking(BOTH_LISTEN);
      container.failing = step.getKey();

      var failed =
          assertThrows(
              IOException.class,
              () -> server(container).serve("acme", acme(), Duration.ofSeconds(60)),
              step.getKey());

      assertEquals(step.getValue(), failed.getMessage(), step.getKey());
    }
  }

  @Test
  void theListeningPortsAreTheLocalAddressesLastField() {
    assertEquals(
        Set.of(5432, 3000, 8080),
        ProjectServer.listening(
            "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\n"
                + "LISTEN 0 511 [::]:3000 [::]:*\n"
                + "LISTEN 0 128 127.0.0.1:8080 0.0.0.0:*\n"
                + "garbage\n"
                + "LISTEN 0 1 0.0.0.0:http 0.0.0.0:*\n"));
  }

  @Test
  void eachUnitsPropertiesAreReadByItsId() {
    assertEquals(
        Map.of(
            "sail-process-web.service",
            Map.of(
                "Id", "sail-process-web.service",
                "ActiveState", "failed",
                "SubState", "failed",
                "ExecMainStatus", "1")),
        ProjectServer.unitStates(unit("web", "failed", "failed", 1) + "\n\nnot a property\n"));
  }
}
