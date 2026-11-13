/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.store.RunStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a run's container says it cost, as the reader asks it: the report its harness wrote, read
 * from the last lines of the one file that harness reports in that hold its markers, under one
 * bounded wait. A file holding no such line, whatever its size, or not there is none; a last report
 * that does not parse is none, said once; only a container that cannot be asked leaves the report
 * unread (M3).
 */
class RunUsageReaderTest {

  private static final String RUN = "01a17c6f-7c50-7000-8f79-8f9294e27ea5";
  private static final String LOG = AgentUnit.forRun(RUN).logPath();
  private static final String TRANSCRIPT = "/home/dev/.codex/sessions/rollout.jsonl";
  private static final String CLAUDE_LOG = resource("claude-code-agent.jsonl");
  private static final String CODEX_ROLLOUT = resource("codex-rollout.jsonl");

  private static String resource(String name) {
    try (var in = RunUsageReaderTest.class.getResourceAsStream("/ai/singlr/sail/harness/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A container answering each command as {@code answer} says, keeping what it was asked. */
  private static final class Container implements ShellExec {
    final List<List<String>> asked = new ArrayList<>();
    final List<Duration> waited = new ArrayList<>();
    private final Function<List<String>, Object> answer;

    Container(Function<List<String>, Object> answer) {
      this.answer = answer;
    }

    @Override
    public Result exec(List<String> command) {
      throw new AssertionError("every read is bounded");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws IOException, TimeoutException {
      asked.add(command);
      waited.add(timeout);
      return switch (answer.apply(command)) {
        case Result result -> result;
        case IOException failed -> throw failed;
        case TimeoutException late -> throw late;
        default -> throw new AssertionError("no answer");
      };
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  private static RunStore.RunRow run(String agent, String transcript) {
    return new RunStore.RunRow(
        RUN,
        "acme",
        "auth",
        "node-a",
        "build",
        agent,
        "b",
        "t",
        null,
        null,
        "completed",
        0,
        LOG,
        "sail-agent-" + RUN,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        transcript);
  }

  private static ShellExec.Result printed(String stdout) {
    return new ShellExec.Result(0, stdout, "");
  }

  /** A container holding {@code file} as every report file. */
  private static Container holding(String file) {
    return new Container(command -> ReportLines.answer(file, command));
  }

  private static String stderrWhile(Runnable read) {
    var original = System.err;
    var captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      read.run();
    } finally {
      System.setErr(original);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  @Test
  void aClaudeCodeRunsLogIsReadFromItsLastResultLinesInTheCLocaleInOneTenSecondWait() {
    var container = holding(CLAUDE_LOG);

    var usage = new RunUsageReader(container).read(run("claude-code", TRANSCRIPT));

    assertEquals(Harnesses.of("claude-code").usage(CLAUDE_LOG), usage);
    var asked = container.asked.getFirst();
    assertEquals(
        List.of(
            "--env",
            "LC_ALL=C",
            "--",
            "sh",
            "-c",
            "f=$1; n=$2; shift 2; for m in \"$@\"; do tac -- \"$f\" | grep -m \"$n\" -a -F -e \"$m\";"
                + " done; exit 0",
            "sh",
            LOG,
            "16",
            "\"type\":\"result\""),
        asked.subList(asked.indexOf("LC_ALL=C") - 1, asked.size()));
    assertEquals(List.of(Duration.ofSeconds(10)), container.waited);
  }

  @Test
  void aCodexRunsTranscriptIsItsReportAndARunWithNoTranscriptReportedNone() {
    var container = holding(CODEX_ROLLOUT);
    var reader = new RunUsageReader(container);

    assertEquals(Harnesses.of("codex").usage(CODEX_ROLLOUT), reader.read(run("codex", TRANSCRIPT)));
    var asked = container.asked.getFirst();
    assertEquals(
        List.of(TRANSCRIPT, "16", "\"type\":\"token_count\"", "\"type\":\"turn_context\""),
        asked.subList(asked.size() - 4, asked.size()));
    assertEquals(RunUsage.NONE, reader.read(run("codex", null)));
    assertEquals(RunUsage.NONE, reader.read(run("gemini", TRANSCRIPT)), "a harness not known");
  }

  @Test
  void aReportFileThatIsNotThereIsNone() {
    assertEquals(RunUsage.NONE, new RunUsageReader(holding(null)).read(run("claude-code", null)));
  }

  /** Review 1, major 3: a container that could not be asked leaves the report unread. */
  @Test
  void aContainerStoppedMissingOrSilentLeavesTheReportUnread() {
    for (var answer :
        List.<Object>of(
            new ShellExec.Result(1, "", "Error: Instance is not running\n"),
            new ShellExec.Result(
                1, "", "Error: Failed to fetch instance \"acme\": Instance not found\n"),
            new ShellExec.Result(
                1,
                "",
                "Error: Get \"http://unix.socket/1.0\": dial unix /var/lib/incus/unix.socket:"
                    + " connect: No such file or directory\n"),
            new ShellExec.Result(255, "", ""),
            new IOException("incus is not installed"),
            new TimeoutException("no answer in 10s"))) {
      assertNull(
          new RunUsageReader(new Container(command -> answer)).read(run("claude-code", null)),
          String.valueOf(answer));
    }
  }

  /**
   * Review 2, finding 1: a last result line that does not parse is none, never an earlier total and
   * never unread, since its file says the same on every read; it is said once, naming the run.
   */
  @Test
  void aLastReportThatDoesNotParseIsNoneSaidOnce() {
    var cut = CLAUDE_LOG.strip().substring(0, CLAUDE_LOG.strip().length() - 40);
    var read = new AtomicReference<RunUsage>();

    var said =
        stderrWhile(
            () -> read.set(new RunUsageReader(holding(cut)).read(run("claude-code", null))));

    assertEquals(RunUsage.NONE, read.get());
    assertEquals(
        "  [usage] the report of run " + RUN + " could not be parsed: recorded none\n", said);
  }

  /**
   * Review 2, finding 1: a log holding no result line is none whatever its size, read in one
   * command, so a long run killed before its result is never asked about again.
   */
  @Test
  void aLogHoldingNoResultLineIsNoneWhateverItsSizeInOneCommand() {
    var filler = "{\"type\":\"system\",\"subtype\":\"hook_response\"}\n".repeat((6 << 20) / 44 + 1);
    var container = holding(filler);

    assertEquals(RunUsage.NONE, new RunUsageReader(container).read(run("claude-code", null)));
    assertEquals(1, container.asked.size());
  }

  /** Review 2, finding 1: a raw C1 control in the last result, which JSON takes, is read. */
  @Test
  void aLastResultHoldingARawC1ControlIsRead() {
    var log = CLAUDE_LOG.replace("\"result\":\"", "\"result\":\"\u009b");

    assertEquals(
        Harnesses.of("claude-code").usage(CLAUDE_LOG),
        new RunUsageReader(holding(log)).read(run("claude-code", null)));
  }

  /**
   * The reader's own command run on this machine, the container's part dropped: its script meets
   * the real {@code sh}, {@code tac} and {@code grep}, over files under {@link #dir}. Only on
   * Linux, where a run's container is: macOS has no {@code tac}.
   */
  private static final class Local implements ShellExec {
    private final ShellExecutor shell = new ShellExecutor(false);

    @Override
    public Result exec(List<String> command) {
      throw new AssertionError("every read is bounded");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws IOException, InterruptedException, TimeoutException {
      var inner = new ArrayList<>(List.of("env", "LC_ALL=C"));
      inner.addAll(command.subList(command.indexOf("--") + 1, command.size()));
      return shell.exec(inner, workDir, timeout);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  @TempDir Path dir;

  private RunUsage readLocally(String transcript) throws IOException {
    var path = dir.resolve("rollout.jsonl");
    if (transcript != null) {
      Files.writeString(path, transcript);
    }
    return new RunUsageReader(new Local()).read(run("codex", path.toString()));
  }

  /**
   * Coordinator, round 3: Codex writes one {@code turn_context} a turn and a {@code token_count}
   * after every model call, so the last lines holding either are all counts in a long turn; each
   * marker has its own last lines, and the model is still read.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void aCodexTurnWithMoreCountsThanTheLinesReadStillGivesItsModel() throws IOException {
    var count =
        CODEX_ROLLOUT.lines().filter(line -> line.contains("\"type\":\"token_count\"")).toList();
    var longTurn = CODEX_ROLLOUT + (count.getLast() + "\n").repeat(RunUsageReader.LINES + 4);

    assertEquals(Harnesses.of("codex").usage(longTurn), readLocally(longTurn));
    assertEquals("gpt-6-astra", readLocally(longTurn).model());
  }

  /**
   * The script itself, through the real shell: a file not there and a file holding no marker are
   * none; a file holding the first marker and not the second is read from the first; a NUL byte in
   * the file does not hide its lines.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void theReadersScriptAnswersEveryFileItMeetsWithExitZero() throws IOException {
    var counts =
        CODEX_ROLLOUT.lines().filter(line -> !line.contains("\"type\":\"turn_context\"")).toList();
    var noModel = String.join("\n", counts) + "\n";

    assertEquals(RunUsage.NONE, readLocally(null));
    assertEquals(RunUsage.NONE, readLocally("{\"type\":\"session_meta\"}\n"));
    assertEquals(Harnesses.of("codex").usage(noModel), readLocally(noModel));
    assertNull(readLocally(noModel).model());
    assertEquals(
        Harnesses.of("codex").usage(CODEX_ROLLOUT),
        readLocally("{\"type\":\"x\",\"blob\":\"\u0000\"}\n" + CODEX_ROLLOUT));
  }
}
