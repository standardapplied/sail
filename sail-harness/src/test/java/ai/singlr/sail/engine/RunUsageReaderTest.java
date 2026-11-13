/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * What a run's container says it cost, as the reader asks it: the report its harness wrote, read
 * from the last bytes of the one file that harness reports in, under a bounded wait. Only the
 * file's own absence, said by the command that read it, is none; a container that cannot be asked,
 * a last report that does not parse and a long file whose last bytes hold none are unread (M3).
 */
class RunUsageReaderTest {

  private static final String RUN = "01a17c6f-7c50-7000-8f79-8f9294e27ea5";
  private static final String LOG = AgentUnit.forRun(RUN).logPath();
  private static final String TRANSCRIPT = "/home/dev/.codex/sessions/rollout.jsonl";
  private static final String CLAUDE_LOG = resource("claude-code-agent.jsonl");
  private static final String CODEX_ROLLOUT = resource("codex-rollout.jsonl");
  private static final ShellExec.Result GONE =
      new ShellExec.Result(
          1, "", "tail: cannot open '" + LOG + "' for reading: No such file or directory\n");

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

  @Test
  void aClaudeCodeRunsLogIsReadFromItsEndInTheCLocaleWithinTenSeconds() {
    var container = new Container(command -> printed(CLAUDE_LOG));

    var usage = new RunUsageReader(container).read(run("claude-code", TRANSCRIPT));

    assertEquals(14.762978600000006, usage.costUsd());
    assertEquals(
        List.of("--env", "LC_ALL=C", "--", "tail", "-c", "4194304", "--", LOG),
        container
            .asked
            .getFirst()
            .subList(
                container.asked.getFirst().indexOf("LC_ALL=C") - 1,
                container.asked.getFirst().size()));
    assertEquals(List.of(Duration.ofSeconds(10)), container.waited);
    assertEquals(1, container.asked.size(), "Claude Code's transcript is never read");
  }

  @Test
  void aCodexRunsTranscriptIsItsReportAndARunWithNoTranscriptReportedNone() {
    var container = new Container(command -> printed(CODEX_ROLLOUT));
    var reader = new RunUsageReader(container);

    assertEquals(RunUsage.Source.TRANSCRIPT, reader.read(run("codex", TRANSCRIPT)).source());
    assertEquals(TRANSCRIPT, container.asked.getFirst().getLast());
    assertEquals(RunUsage.NONE, reader.read(run("codex", null)));
    assertEquals(RunUsage.NONE, reader.read(run("gemini", TRANSCRIPT)), "a harness not known");
  }

  /** Only tail's own word that the file is not there is none. */
  @Test
  void aReportFileThatIsNotThereIsNone() {
    assertEquals(
        RunUsage.NONE,
        new RunUsageReader(new Container(command -> GONE)).read(run("claude-code", null)));
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

  /** Review 1, minor 1: a last result line that does not parse is unread, not an earlier total. */
  @Test
  void aLastReportThatDoesNotParseIsUnread() {
    var cut = CLAUDE_LOG.strip().substring(0, CLAUDE_LOG.strip().length() - 40);

    assertNull(
        new RunUsageReader(new Container(command -> printed(cut))).read(run("claude-code", null)));
  }

  /**
   * Review 1, minor 2: a window full to the brim with no report in it is unread when the file is
   * longer than the window, so its report may lie before it, and none when the window is the file.
   */
  @Test
  void aFullWindowWithNoReportIsUnreadForALongerFileAndNoneForAFileThatSize() {
    var filler =
        "{\"type\":\"system\",\"subtype\":\"hook_response\"}\n"
            .repeat(RunUsageReader.TAIL_BYTES / 44 + 1)
            .substring(0, RunUsageReader.TAIL_BYTES);
    Function<String, Container> sized =
        size ->
            new Container(
                command -> command.contains("stat") ? printed(size + "\n") : printed(filler));

    var longer = sized.apply("6291456");
    assertNull(new RunUsageReader(longer).read(run("claude-code", null)));
    assertEquals(
        List.of("stat", "-c", "%s", "--", LOG),
        longer
            .asked
            .getLast()
            .subList(longer.asked.getLast().size() - 5, longer.asked.getLast().size()));
    assertEquals(
        RunUsage.NONE,
        new RunUsageReader(sized.apply(String.valueOf(RunUsageReader.TAIL_BYTES)))
            .read(run("claude-code", null)));
    var unsized = new Container(command -> command.contains("stat") ? GONE : printed(filler));
    assertNull(new RunUsageReader(unsized).read(run("claude-code", null)));
    var small = new Container(command -> printed("{\"type\":\"system\"}\n"));
    assertEquals(RunUsage.NONE, new RunUsageReader(small).read(run("claude-code", null)));
    assertEquals(1, small.asked.size(), "a window shorter than its size is the whole file");
  }
}
