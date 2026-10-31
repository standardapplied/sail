/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ProjectServer.State;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code serve} against a real container's {@code systemd --user}: the unit and script it writes
 * run, a process's port is seen listening, a second serve restarts nothing, a changed command
 * restarts only its process, one that fails is reported with its last line, and stop stops them.
 */
class ProjectServeIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-project-serve";

  private static SailYaml definition(String processes) {
    return SailYaml.fromMap(YamlUtil.parseMap("name: " + CONTAINER + "\nprocesses:\n" + processes));
  }

  private String mainPid(String process) throws Exception {
    return shell
        .exec(
            ContainerExec.asDevUser(
                CONTAINER,
                List.of(
                    "systemctl",
                    "--user",
                    "show",
                    "-p",
                    "MainPID",
                    "--value",
                    ProjectServer.unitOf(process) + ".service")))
        .stdout()
        .strip();
  }

  @Test
  void aProcessComesUpRestartsOnlyWhenChangedReportsAFailureAndStops() throws Exception {
    ensureIncusOrSkip();
    try {
      launchPrepared(CONTAINER);
      var user =
          exec(
              CONTAINER,
              List.of(
                  "bash",
                  "-c",
                  "userdel -r ubuntu 2>/dev/null || true;"
                      + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev;"
                      + " mkdir -p /home/dev/workspace && chown dev:dev /home/dev/workspace;"
                      + " loginctl enable-linger dev"));
      assertTrue(user.ok(), "the dev user and its manager: " + user.stderr());
      var manager =
          shell.exec(
              ContainerExec.asDevUser(
                  CONTAINER,
                  List.of(
                      "timeout",
                      "60",
                      "sh",
                      "-c",
                      "until systemctl --user show-environment >/dev/null 2>&1; do sleep 0.2;"
                          + " done")));
      assertTrue(manager.ok(), "the dev user's systemd answers: " + manager.stderr());
      var server =
          new ProjectServer(
              shell,
              new ProjectApplier(
                  shell,
                  new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)),
              duration -> Thread.sleep(duration));
      var web =
          "  web:\n    command: python3 -m http.server 8000\n    workdir: .\n    port: 8000\n";

      var first = server.serve(CONTAINER, definition(web), Duration.ofSeconds(30));

      assertEquals(List.of("web  8000  listening"), first.lines());
      var pid = mainPid("web");

      server.serve(CONTAINER, definition(web), Duration.ofSeconds(30));
      assertEquals(pid, mainPid("web"), "an unchanged process keeps running");

      var changed =
          "  web:\n    command: python3 -m http.server $PORT\n    workdir: .\n    port: 8000\n"
              + "    env:\n      PORT: \"8000\"\n";
      var restarted = server.serve(CONTAINER, definition(changed), Duration.ofSeconds(30));
      assertTrue(restarted.allListening(), restarted.lines().toString());
      assertNotEquals(pid, mainPid("web"), "a changed command restarts its process");

      var broken =
          changed
              + "  broken:\n    command: python3 -c 'import nothing_by_this_name'\n    port: 8001\n";
      var report = server.serve(CONTAINER, definition(broken), Duration.ofSeconds(30));
      var failed = report.ports().getLast();
      assertEquals(State.EXITED, failed.state(), report.lines().toString());
      assertEquals(1, failed.exitCode());
      assertEquals("ModuleNotFoundError: No module named 'nothing_by_this_name'", failed.detail());

      server.stop(CONTAINER);
      var stopped = server.ports(CONTAINER, definition(broken));
      assertEquals(
          List.of(State.STOPPED, State.STOPPED),
          stopped.ports().stream().map(ProjectServer.Port::state).toList(),
          stopped.lines().toString());
    } finally {
      deleteContainerQuietly(CONTAINER);
    }
  }
}
