/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The live member's process seam: the argv it starts under, pinned whole, and — run for real with
 * the {@code incus exec} prefix stripped — the wrapper writing its pid and exec'ing the command
 * with its pipes held open.
 */
class ContainerAgentProcessTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  /** Runs the container-side argv where the test is: everything after {@code --}. */
  private static final UnaryOperator<List<String>> LOCALLY =
      argv -> argv.subList(argv.indexOf("--") + 1, argv.size());

  @TempDir Path dir;

  private static Map<String, String> env() {
    var env = new LinkedHashMap<String, String>();
    env.put("SAIL_SPEC_ID", "spec-1");
    env.put("SAIL_RUN_ID", "run-1");
    return env;
  }

  @Test
  void theProcessStartsUnderTheWrapperWithItsPathsAndCommandAsArguments() {
    assertEquals(
        List.of(
            "incus",
            "exec",
            "acme",
            "--user",
            "1000",
            "--group",
            "1000",
            "--cwd",
            "/home/dev/workspace/api",
            "--env",
            "HOME=/home/dev",
            "--env",
            "XDG_RUNTIME_DIR=/run/user/1000",
            "--env",
            "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus",
            "--env",
            "SAIL_SPEC_ID=spec-1",
            "--env",
            "SAIL_RUN_ID=run-1",
            "--",
            "bash",
            "-lc",
            "printf '%s\\n' \"$$\" > \"$1\"; exec \"${@:3}\" 2>> \"$2\"",
            "bash",
            "/home/dev/.sail/runs/r/agent.pid",
            "/home/dev/.sail/runs/r/agent.log",
            "claude",
            "--print",
            "a b; c"),
        ContainerAgentProcess.startCommand(
            "acme",
            "/home/dev/workspace/api",
            env(),
            List.of("claude", "--print", "a b; c"),
            "/home/dev/.sail/runs/r/agent.pid",
            "/home/dev/.sail/runs/r/agent.log"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void theWrapperWritesItsPidAndExecsTheCommandOverPipes() throws Exception {
    var pidFile = dir.resolve("agent.pid");
    var logFile = dir.resolve("agent.log");
    var shell = new ScriptedShellExecutor().onOk("cat " + pidFile, "4242\n");
    var echo =
        "echo \"pid $$\"; echo oops >&2; while IFS= read -r line; do echo \"got $line\"; done;"
            + " exit 7";

    var handle =
        new ContainerAgentProcess(shell, LOCALLY)
            .start(
                "acme",
                dir.toString(),
                Map.of(),
                List.of("bash", "-c", echo),
                pidFile.toString(),
                logFile.toString());

    var announced = handle.stdout().read();
    handle.stdin().write("{\"type\":\"user\"}");
    assertEquals("got {\"type\":\"user\"}", handle.stdout().read());
    handle.stdin().close();
    handle.stdin().close();
    assertNull(handle.stdout().read(), "stdin closed: the command ends and stdout with it");
    assertEquals(OptionalInt.of(7), handle.waitFor(PATIENCE));
    assertEquals(
        "pid " + Files.readString(pidFile).strip(),
        announced,
        "the pid file names the command itself: the wrapper exec'd into it");
    assertEquals("oops\n", Files.readString(logFile), "stderr is the log's, never stdout's");
    assertEquals(OptionalInt.of(4242), handle.pid());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aProcessStillRunningHasNoExitCodeYet() throws Exception {
    var handle =
        new ContainerAgentProcess(new ScriptedShellExecutor(), LOCALLY)
            .start(
                "acme",
                dir.toString(),
                Map.of(),
                List.of("cat"),
                dir.resolve("agent.pid").toString(),
                dir.resolve("agent.log").toString());

    assertEquals(OptionalInt.empty(), handle.waitFor(Duration.ZERO));
    handle.stdin().close();
    assertEquals(OptionalInt.of(0), handle.waitFor(PATIENCE));
  }

  private static AgentProcess.Handle idle(ShellExec shell) throws IOException {
    return new ContainerAgentProcess(shell, argv -> List.of("true"))
        .start("acme", "/w", Map.of(), List.of("claude"), "/p/agent.pid", "/p/agent.log");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aSignalIsAKillInTheContainerOfThePidTheFileNames() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat /p/agent.pid", "4242\n");
    var handle = idle(shell);

    handle.signal(AgentProcess.Signal.INT);
    handle.signal(AgentProcess.Signal.TERM);

    assertEquals(
        List.of(
            ContainerExec.asDevUser("acme", List.of("cat", "/p/agent.pid")),
            ContainerExec.asDevUser("acme", List.of("kill", "-INT", "4242")),
            ContainerExec.asDevUser("acme", List.of("cat", "/p/agent.pid")),
            ContainerExec.asDevUser("acme", List.of("kill", "-TERM", "4242"))),
        shell.arguments());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aPidFileThatNamesNoProcessSignalsNothing() throws Exception {
    for (var content : List.of("", "0", "-1", "12 34", "1; rm -rf /", "9999999999")) {
      var shell =
          new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
              .onOk("cat /p/agent.pid", content);
      var handle = idle(shell);

      handle.signal(AgentProcess.Signal.TERM);

      assertEquals(OptionalInt.empty(), handle.pid(), content);
      assertEquals(
          0,
          shell.arguments().stream().filter(argv -> argv.contains("kill")).count(),
          "a pid file is the container's to write: only digits ever reach kill, got " + content);
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aContainerThatDoesNotAnswerNamesNoPidAndFailsNoSignal() throws Exception {
    var unreadable = idle(new ScriptedShellExecutor());
    var throwing =
        idle(new ScriptedShellExecutor().onThrow("cat", new IOException("incus not found")));
    var undelivered =
        idle(
            new ScriptedShellExecutor()
                .onOk("cat /p/agent.pid", "4242")
                .onThrow("kill", new IOException("incus not found")));

    assertEquals(OptionalInt.empty(), unreadable.pid());
    assertEquals(OptionalInt.empty(), throwing.pid());
    undelivered.signal(AgentProcess.Signal.INT);
    assertEquals(OptionalInt.of(4242), undelivered.pid());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void closingTheStdinOfAProcessThatIsGoneIsQuiet() throws Exception {
    var handle = idle(new ScriptedShellExecutor());
    assertEquals(OptionalInt.of(0), handle.waitFor(PATIENCE));

    assertThrows(IOException.class, () -> handle.stdin().write("too late"));
    handle.stdin().close();
    handle.stdin().close();
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void anInterruptedQuestionNamesNoPidAndKeepsTheInterrupt() throws Exception {
    var interrupted =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) throws InterruptedException {
            throw new InterruptedException("stopping");
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout)
              throws InterruptedException {
            throw new InterruptedException("stopping");
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };

    assertEquals(OptionalInt.empty(), idle(interrupted).pid());
    assertTrue(Thread.interrupted(), "the interrupt is the caller's to see");
  }

  @Test
  void aShellIsRequired() {
    assertThrows(NullPointerException.class, () -> new ContainerAgentProcess(null));
  }
}
