/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.LoginStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

  private final List<AgentProcess.Handle> handles = new ArrayList<>();

  /** {@code handle}, released when the test ends. */
  private AgentProcess.Handle held(AgentProcess.Handle handle) {
    handles.add(handle);
    return handle;
  }

  /** Ends each process's input and reads its output to the end, where the handle closes it. */
  @AfterEach
  void release() throws IOException {
    for (var handle : handles) {
      handle.stdin().close();
      while (handle.stdout().read() != null) {}
    }
  }

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
            "f=/home/dev/.sail/runs/r/launch.env; set +x; while IFS= read -r kv; do export \"$kv\";"
                + " done <"
                + " \"$f\" || exit 1; rm -f \"$f\"; mkdir -p -- \"${1%/*}\" && cat /proc/$$/stat >"
                + " \"$3\" && printf '%s\\n' \"$$\" > \"$1\" && exec \"${@:4}\" 2>> \"$2\"",
            "bash",
            "/home/dev/.sail/runs/r/agent.pid",
            "/home/dev/.sail/runs/r/agent.log",
            "/home/dev/.sail/runs/r/agent.pid.start",
            "claude",
            "--print",
            "a b; c"),
        ContainerAgentProcess.startCommand(
            "acme",
            "/home/dev/workspace/api",
            env(),
            List.of("claude", "--print", "a b; c"),
            "/home/dev/.sail/runs/r/agent.pid",
            "/home/dev/.sail/runs/r/agent.log",
            "/home/dev/.sail/runs/r/launch.env"));
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
        held(
            new ContainerAgentProcess(shell, LOCALLY)
                .start(
                    "acme",
                    dir.toString(),
                    Map.of(),
                    List.of("bash", "-c", echo),
                    pidFile.toString(),
                    logFile.toString(),
                    launchEnv("SAIL_RUN_CREDENTIAL=c1")));

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

  /**
   * The wrapper records, beside the pid, the start of the process the pid names: the command's own,
   * which it exec'd into, read as a halt reads it.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void theWrapperRecordsTheStartOfTheProcessItsPidNames() throws Exception {
    var pidFile = dir.resolve("agent.pid");
    var handle =
        held(
            new ContainerAgentProcess(new ScriptedShellExecutor(), LOCALLY)
                .start(
                    "acme",
                    dir.toString(),
                    Map.of(),
                    List.of("bash", "-c", "echo started; read -r line"),
                    pidFile.toString(),
                    dir.resolve("agent.log").toString(),
                    launchEnv("SAIL_RUN_CREDENTIAL=c1")));

    assertEquals("started", handle.stdout().read());
    var pid = Files.readString(pidFile).strip();
    var recorded =
        AgentSession.parseStartTicks(
            Files.readString(Path.of(AgentUnit.startPath(pidFile.toString()))));
    var live = AgentSession.parseStartTicks(Files.readString(Path.of("/proc", pid, "stat")));

    assertNotNull(recorded);
    assertEquals(live, recorded);
  }

  /** A start the wrapper cannot record starts nothing: no agent runs whose identity is unknown. */
  @Test
  @EnabledOnOs(OS.LINUX)
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aStartTheWrapperCannotRecordStartsNoAgent() throws Exception {
    var pidFile = dir.resolve("agent.pid");
    Files.createDirectory(Path.of(AgentUnit.startPath(pidFile.toString())));
    var handle =
        held(
            new ContainerAgentProcess(new ScriptedShellExecutor(), LOCALLY)
                .start(
                    "acme",
                    dir.toString(),
                    Map.of(),
                    List.of("bash", "-c", "echo started"),
                    pidFile.toString(),
                    dir.resolve("agent.log").toString(),
                    launchEnv("SAIL_RUN_CREDENTIAL=c1")));

    assertNull(handle.stdout().read(), "the agent never started");
    var exit = handle.waitFor(PATIENCE);
    assertTrue(exit.isPresent());
    assertNotEquals(0, exit.getAsInt(), "the wrapper failed");
    assertFalse(Files.exists(pidFile), "and wrote no pid");
  }

  /**
   * A chat's process has no run's folder: its wrapper makes the folder its pid file is in. A chat
   * signed in inside the workspace is handed an empty login file, as the hand-over writes one.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void theWrapperMakesTheFolderItsPidFileIsIn() throws Exception {
    var pidFile = dir.resolve("chats/c1/agent.pid");
    var nothing = Files.writeString(dir.resolve("login.env"), "").toString();
    var handle =
        held(
            new ContainerAgentProcess(new ScriptedShellExecutor(), LOCALLY)
                .start(
                    "acme",
                    dir.toString(),
                    Map.of(),
                    List.of("bash", "-c", "echo started"),
                    pidFile.toString(),
                    dir.resolve("chats/c1/agent.log").toString(),
                    nothing));

    assertEquals("started", handle.stdout().read());
    assertNull(handle.stdout().read());
    assertEquals(OptionalInt.of(0), handle.waitFor(PATIENCE));
    assertTrue(Files.readString(pidFile).strip().matches("[0-9]+"), "its pid is written there");
  }

  /** The live member's secrets reach it from its launch.env, removed before it starts. */
  @Test
  @EnabledOnOs(OS.LINUX)
  void theCommandStartsWithTheLaunchFilesValuesAndTheFileGone() throws Exception {
    var file = launchEnv("SAIL_RUN_CREDENTIAL=c1\nCLAUDE_CODE_OAUTH_TOKEN=t-1");
    var handle =
        held(
            new ContainerAgentProcess(new ScriptedShellExecutor(), LOCALLY)
                .start(
                    "acme",
                    dir.toString(),
                    Map.of(),
                    List.of(
                        "bash",
                        "-c",
                        "echo \"$SAIL_RUN_CREDENTIAL $CLAUDE_CODE_OAUTH_TOKEN\"; test -e "
                            + file
                            + " && echo present || echo gone"),
                    dir.resolve("agent.pid").toString(),
                    dir.resolve("agent.log").toString(),
                    file));

    assertEquals("c1 t-1", handle.stdout().read());
    assertEquals("gone", handle.stdout().read());
    assertEquals(OptionalInt.of(0), handle.waitFor(PATIENCE));
  }

  /**
   * L2 in the live member's shape: a container whose login profile exports another address, a
   * socket, other credentials and cloud switches starts the member with its stored key, its kind's
   * own address and no socket, since the launch file is read after every profile.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void aProfilesAddressSocketAndCredentialsNeverReachALiveMember() throws Exception {
    var home = Files.createDirectories(dir.resolve("home"));
    Files.writeString(
        home.resolve(".profile"),
        "export ANTHROPIC_BASE_URL=http://127.0.0.1:9 ANTHROPIC_UNIX_SOCKET=/tmp/elsewhere.sock"
            + " CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST= ANTHROPIC_API_KEY=fake-container-key"
            + " ANTHROPIC_AUTH_TOKEN=fake-gateway CLAUDE_CODE_USE_BEDROCK=1"
            + " CLAUDE_CODE_USE_MANTLE=1"
            + " CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR=3"
            + " 'ANTHROPIC_CUSTOM_HEADERS=x-api-key: fake-container-key'\n");
    var stored =
        new LoginStore.Login(
            new LoginStore.Row("claude-code", "work", "api-key", true, Instant.EPOCH, "0123abcd"),
            Harnesses.of("claude-code").loginKind("api-key"),
            "fake-stored-key");
    var file = dir.resolve("launch.env");
    Files.writeString(file, new LaunchEnv("c1", stored).lines());
    UnaryOperator<List<String>> profiled =
        argv ->
            Stream.concat(Stream.of("env", "HOME=" + home), LOCALLY.apply(argv).stream()).toList();

    var handle =
        held(
            new ContainerAgentProcess(new ScriptedShellExecutor(), profiled)
                .start(
                    "acme",
                    dir.toString(),
                    Map.of(),
                    List.of(
                        "bash",
                        "-c",
                        "echo \"[$ANTHROPIC_BASE_URL][$ANTHROPIC_UNIX_SOCKET]"
                            + "[$CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST][$ANTHROPIC_API_KEY]"
                            + "[$ANTHROPIC_AUTH_TOKEN][$CLAUDE_CODE_USE_BEDROCK"
                            + "$CLAUDE_CODE_USE_MANTLE"
                            + "$CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR$ANTHROPIC_CUSTOM_HEADERS]\""),
                    dir.resolve("agent.pid").toString(),
                    dir.resolve("agent.log").toString(),
                    file.toString()));

    assertEquals("[https://api.anthropic.com][][1][fake-stored-key][][]", handle.stdout().read());
    assertEquals(OptionalInt.of(0), handle.waitFor(PATIENCE));
  }

  private String launchEnv(String lines) throws IOException {
    var file = dir.resolve("launch.env");
    Files.writeString(file, lines + "\n");
    return file.toString();
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aProcessStillRunningHasNoExitCodeYet() throws Exception {
    var handle =
        held(
            new ContainerAgentProcess(new ScriptedShellExecutor(), LOCALLY)
                .start(
                    "acme",
                    dir.toString(),
                    Map.of(),
                    List.of("cat"),
                    dir.resolve("agent.pid").toString(),
                    dir.resolve("agent.log").toString(),
                    launchEnv("SAIL_RUN_CREDENTIAL=c1")));

    assertEquals(OptionalInt.empty(), handle.waitFor(Duration.ZERO));
    handle.stdin().close();
    assertEquals(OptionalInt.of(0), handle.waitFor(PATIENCE));
  }

  private AgentProcess.Handle idle(ShellExec shell) throws IOException {
    return held(
        new ContainerAgentProcess(shell, argv -> List.of("true"))
            .start(
                "acme",
                "/w",
                Map.of(),
                List.of("claude"),
                "/p/agent.pid",
                "/p/agent.log",
                "/p/launch.env"));
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

  /**
   * The end of stdout closes its pipe and stdin's at once, the process still running on: a process
   * done speaking takes no more input, and neither pipe waits for the JDK's reaper, which closes a
   * child's pipes only after it has woken those waiting for it.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void theEndOfStdoutClosesBothPipesWhileTheProcessRunsOn() throws Exception {
    var before = OpenDescriptors.list();
    var handle =
        held(
            new ContainerAgentProcess(
                    new ScriptedShellExecutor(),
                    argv -> List.of("sh", "-c", "exec >&-; read line || exit 3; exit 0"))
                .start(
                    "acme",
                    "/w",
                    Map.of(),
                    List.of("claude"),
                    "/p/agent.pid",
                    "/p/agent.log",
                    "/p/launch.env"));

    assertNull(handle.stdout().read());
    assertNull(handle.stdout().read(), "an ended stdout stays ended");

    assertEquals(Map.of(), OpenDescriptors.opened(before, OpenDescriptors.list()));
    assertEquals(OptionalInt.of(3), handle.waitFor(PATIENCE), "its input ended with its output");
  }
}
