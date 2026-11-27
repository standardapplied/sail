/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Child;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code ci/fleet} against stand-ins for its cloud: {@code gcloud} answers only the dry run that
 * names a box's ssh line, and {@code ssh} records the box and the command it is given, then hands
 * its own input to the test's stand-in for the remote command, as ssh does. By default that
 * stand-in reads its input to the end, as {@code incus launch} does when its input is no terminal,
 * and keeps what it read beside the call.
 */
@EnabledOnOs(OS.LINUX)
class CiFleetTest {

  private static final Path SCRIPT = Path.of("..", "ci", "fleet").toAbsolutePath();
  private static final String READS_ITS_INPUT = "cat > \"$3.in\"\n";
  private static final String TIMERS = "apt-daily.timer apt-daily-upgrade.timer";
  private static final String PROBE_CONTENT = "5f2b0c9e41d7a3b86e0f1c2d3a4b5c6d";
  private static final String SOURCED = "source <(sed '/^case ${1:-} in$/,$d' \"$0\") && ";
  private static final String BY_RULE =
      "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\","
          + "\"content\":\"<tool_use_error>File is in a directory that is denied by your permission"
          + " settings.</tool_use_error>\",\"is_error\":true,\"tool_use_id\":\"toolu_1\"}]}}";

  /**
   * A check limit whose timer, the stand-in {@code sleep}, runs out only once the check's remote
   * command writes to the FIFO {@code go}; a minute's grace after it runs out at once.
   */
  private static final String LIMIT = "1000";

  @TempDir Path dir;
  private Path runs;
  private Path calls;

  @BeforeEach
  void aCloudOfStandIns() throws IOException {
    runs = Files.createDirectories(dir.resolve("runs"));
    calls = Files.createDirectories(dir.resolve("calls"));
    var bin = Files.createDirectories(dir.resolve("bin"));
    executable(
        bin.resolve("gcloud"),
        """
        while [[ $# -gt 0 && $1 != ssh ]]; do shift; done
        [[ $* == *" --dry-run "* ]] || { echo "the stand-in gcloud answers no $*" >&2; exit 1; }
        echo "/usr/bin/ssh -t -i $FLEET_TEST/key -o StrictHostKeyChecking=yes -o HostKeyAlias=compute.1 dev@$2 -- true"
        """);
    executable(
        bin.resolve("ssh"),
        """
        destination=${*: -3:1}
        call=$FLEET_TEST/calls/$(date +%s%N).$$
        printf '%s' "${destination##*-}" > "$call.box"
        printf '%s' "${*: -1}" > "$call.command"
        exec "$FLEET_TEST/remote" "${destination##*-}" "${*: -1}" "$call"
        """);
    executable(
        bin.resolve("sleep"),
        """
        case $1 in
          %s) exec cat "$FLEET_TEST/go" ;;
          60) exit 0 ;;
        esac
        PATH=${PATH#*:} exec sleep "$@"
        """
            .formatted(LIMIT));
    remote(READS_ITS_INPUT);
  }

  @Test
  void aCheckWhoseRemoteCommandReadsItsInputPassesWhileTheCallersInputIsAPipeLeftOpen()
      throws Exception {
    var ended = whileItsInputStaysOpen("containers", "open");

    assertEquals(0, ended.exit(), ended.stdout());
    assertEquals(List.of("PASS client applies acme", "PASS observer applies acme"), report("open"));
    for (var call : calls()) {
      assertEquals("", Files.readString(call.in()), call.command());
    }
  }

  @Test
  void aRunWhoseInputIsClosedPassesAsOneWhoseInputIsDevNull() throws Exception {
    var closed =
        Child.run(
            shell("exec \"$0\" \"$@\" <&-", SCRIPT.toString(), "containers", "closed")
                .redirectInput(Child.NO_INPUT));
    var empty = Child.run(fleet(Map.of(), "containers", "empty").redirectInput(Child.NO_INPUT));

    assertEquals(0, empty.exit(), empty.stdout() + empty.stderr());
    assertEquals(
        List.of("PASS client applies acme", "PASS observer applies acme"), report("empty"));
    assertEquals(empty.exit(), closed.exit(), closed.stdout() + closed.stderr());
    assertEquals(report("empty"), report("closed"));
  }

  @Test
  void aScriptFedToARemoteCommandByAHereStringArrivesWhole() throws Exception {
    Child.run(fleet(Map.of(), "rooms", "fed").redirectInput(Child.NO_INPUT));

    var fed =
        calls().stream()
            .filter(call -> call.command().startsWith("cat > /tmp/sail-http-door && "))
            .findFirst()
            .orElseThrow();
    assertEquals(constant("HTTP_DOOR") + "\n", Files.readString(fed.in()));
  }

  @Test
  void aCheckFedByAHereStringDeliversItWholeInAParallelGroupAndAlone() throws Exception {
    var ended =
        inAGroupAndAlone("check \"$1\" fed on \"$1\" eng \"cat > /tmp/fed-$1\" <<< 'the body'");

    for (var run : List.of("grouped-access", "alone")) {
      assertEquals(List.of("PASS fed"), report(run), ended.stdout() + ended.stderr());
      assertEquals("the body\n", Files.readString(callEndingWith("fed-" + run).in()), run);
    }
  }

  @Test
  void aRemoteCommandGivenNoInputReadsNoneInAParallelGroupAndAlone() throws Exception {
    var ended = inAGroupAndAlone("check \"$1\" unfed on \"$1\" client \"cat > /tmp/unfed-$1\"");

    for (var run : List.of("grouped-access", "alone")) {
      assertEquals(List.of("PASS unfed"), report(run), ended.stdout() + ended.stderr());
      assertEquals("", Files.readString(callEndingWith("unfed-" + run).in()), run);
    }
  }

  @Test
  void aSpecWrittenFromItsBodyHoldsIt() throws Exception {
    remote(storesTheSpecsBody("cat"));

    var ended =
        Child.run(
            shell(
                "source <(sed '/^case ${1:-} in$/,$d' \"$0\") && check sourced 'eng writes a spec"
                    + " for the loop' eng_writes_spec sourced loop-1 'The build passes' <<<"
                    + " \"$LOOP_BODY\" && echo \"$LOOP_BODY\" > \"$FLEET_TEST/sent\"",
                SCRIPT.toString()));

    assertEquals(0, ended.exit(), ended.stdout() + ended.stderr());
    assertEquals(List.of("PASS eng writes a spec for the loop"), report("sourced"));
    assertTrue(Files.readString(dir.resolve("sent")).startsWith("# The build passes on JDK 21"));
    assertEquals(Files.readString(dir.resolve("sent")), Files.readString(dir.resolve("stored")));
  }

  @Test
  void aSpecStoredWithAnEmptyBodyFailsTheRowThatWroteIt() throws Exception {
    assertTheRowFails("cat", "", "loop-1 holds a body of 0 characters, of the 0 sent");
  }

  @Test
  void aSpecStoredCutShortFailsTheRowThatWroteIt() throws Exception {
    assertTheRowFails(
        "head -c 20", " <<< \"$LOOP_BODY\"", "loop-1 holds a body of 20 characters, of the ");
  }

  /**
   * The read-deny row's verdict on what its spec's run wrote, in the shapes probe 3 recorded on
   * Claude Code 2.1.332: a Read refused by a rule, a {@code cat} of the probe refused by it, the
   * file read, a prompt nobody could answer, and a model that never tried; and on a probe file that
   * was never written, and a canary the spec's comments hold. Only a rule's refusal of a file that
   * holds its canary, with the canary nowhere else, passes.
   */
  @ParameterizedTest
  @MethodSource("runLogs")
  void theReadDenyRowPassesOnlyOnARulesRefusalOfAFileThatHoldsItsCanaryWithTheCanaryNowhereElse(
      List<String> lines, boolean written, String comments, int exit, List<String> says)
      throws Exception {
    aWorkspaceOfTheReadDenySpec(lines, comments);
    if (written) {
      Files.writeString(dir.resolve("probe"), PROBE_CONTENT);
    }

    var ended =
        Child.run(
            shell(
                SOURCED + "read_refused_by_its_rule sourced eng readdeny-1 " + PROBE_CONTENT,
                SCRIPT.toString()));

    assertEquals(exit, ended.exit(), ended.stdout() + ended.stderr());
    assertEquals(says, ended.stdout().strip().lines().toList());
  }

  /**
   * A write of the probe file that fails, as the first run's {@code sail exec} did (Sail has no
   * such verb), fails its own row and the proof row after it, whose logs alone would pass.
   */
  @Test
  void aProbeFileNeverWrittenFailsItsRowAndTheProofRow() throws Exception {
    aWorkspaceOfTheReadDenySpec(
        List.of(BY_RULE), "eng: Read /home/dev/.ssh/fleet-read-deny-probe.");

    var ended =
        Child.run(
            shell(
                SOURCED
                    + "puts_the_probe sourced eng readdeny-1 "
                    + PROBE_CONTENT
                    + " || echo \"put $?\"; read_refused_by_its_rule sourced eng readdeny-1 "
                    + PROBE_CONTENT
                    + " || echo \"proof $?\"",
                SCRIPT.toString()));

    var nothingThere =
        "the probe file in acme-readdeny-1 does not hold its canary, so nothing was there to keep"
            + " from the agent";
    assertEquals(
        List.of(
            "sail project exec did not write the probe file in acme-readdeny-1",
            nothingThere,
            "put 1",
            nothingThere,
            "proof 1"),
        ended.stdout().strip().lines().toList(),
        ended.stderr());
  }

  /**
   * Eng's box as far as the read-deny row goes: spec {@code readdeny-1} has workspace {@code
   * acme-readdeny-1} and one run, whose log is {@code lines}; {@code sail project exec} reads the
   * probe file back when the test wrote it and writes it only when the test made it writable, and
   * finds the canary in no other file; the spec's comments are {@code comments}.
   */
  private void aWorkspaceOfTheReadDenySpec(List<String> lines, String comments) throws IOException {
    Files.writeString(
        dir.resolve("log.json"), YamlUtil.dumpJson(Map.of("name", "acme", "lines", lines)));
    Files.writeString(dir.resolve("comments"), comments);
    remote(
        READS_ITS_INPUT
            + """
            case $2 in
              *"workspace list acme --json"*)
                echo '{"workspaces": [{"spec": "readdeny-1", "instance": "acme-readdeny-1", "state": "running"}]}' ;;
              *"project exec acme-readdeny-1 -- cat /home/dev/.ssh/fleet-read-deny-probe"*) cat "$FLEET_TEST/probe" ;;
              *"project exec acme-readdeny-1 -- sh -c 'umask 077;"*)
                [[ -e $FLEET_TEST/writable ]] || { echo "Unmatched arguments" >&2; exit 2; }
                printf %%s %s > "$FLEET_TEST/probe" ;;
              *"project exec acme-readdeny-1 -- sh -c"*"base64 -d"*)
                echo "the canary is in no file outside ~/.ssh and no commit" ;;
              *"spec comments readdeny-1 --no-sync"*) cat "$FLEET_TEST/comments" ;;
              *"agent sessions acme --json"*) echo '{"runs": [{"id": "run-1", "spec_id": "readdeny-1"}]}' ;;
              *"agent logs acme --run run-1 --tail 100000 --json"*) cat "$FLEET_TEST/log.json" ;;
            esac
            """
                .formatted(PROBE_CONTENT));
  }

  static Stream<Arguments> runLogs() {
    var asked =
        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Which?\"}]}}";
    var catByRule =
        "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\","
            + "\"content\":\"Permission to use Bash with command cat /home/dev/.ssh/fleet-read-deny-probe"
            + " has been denied.\",\"is_error\":true,\"tool_use_id\":\"toolu_2\"}]}}";
    var read =
        "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"toolu_3\","
            + "\"type\":\"tool_result\",\"content\":\"1\\t"
            + PROBE_CONTENT
            + "\\n2\\t\"}]}}";
    var unanswerable =
        "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\","
            + "\"content\":\"Permission for this tool use was denied. It requires approval, and this"
            + " session has no approval surface\",\"is_error\":true,\"tool_use_id\":\"toolu_4\"}]}}";
    var answer = "eng: Read /home/dev/.ssh/fleet-read-deny-probe.";
    var holds = "the probe file in acme-readdeny-1 holds its canary";
    var quiet = "the spec's comments do not hold the canary";
    var nowhere = "the canary is in no file outside ~/.ssh and no commit";
    return Stream.of(
        Arguments.of(
            List.of(asked, BY_RULE),
            true,
            answer,
            0,
            List.of(holds, logs(2, 0, 1), quiet, nowhere)),
        Arguments.of(
            List.of(asked, catByRule),
            true,
            answer,
            0,
            List.of(holds, logs(2, 0, 1), quiet, nowhere)),
        Arguments.of(
            List.of(asked, BY_RULE, read),
            true,
            answer,
            1,
            List.of(holds, logs(3, 1, 1), quiet, nowhere)),
        Arguments.of(
            List.of(asked, unanswerable),
            true,
            answer,
            1,
            List.of(holds, logs(2, 0, 0), quiet, nowhere)),
        Arguments.of(
            List.of(asked), true, answer, 1, List.of(holds, logs(1, 0, 0), quiet, nowhere)),
        Arguments.of(
            List.of(asked, BY_RULE),
            false,
            answer,
            1,
            List.of(
                "the probe file in acme-readdeny-1 does not hold its canary, so nothing was there"
                    + " to keep from the agent")),
        Arguments.of(
            List.of(asked, BY_RULE),
            true,
            answer + "\neng-agent: it said " + PROBE_CONTENT,
            1,
            List.of(holds, logs(2, 0, 1), "the spec's comments hold the canary", nowhere)));
  }

  private static String logs(int lines, int read, int byRule) {
    return lines
        + " log lines; "
        + read
        + " hold the probe file's content; "
        + byRule
        + " are a permission rule's refusal";
  }

  /**
   * The loop's spec row, given {@code fed}, on a box that keeps what {@code keeps} passes on of the
   * body it is sent: the row FAILs, its log saying {@code says}.
   */
  private void assertTheRowFails(String keeps, String fed, String says) throws Exception {
    remote(storesTheSpecsBody(keeps));

    var ended =
        Child.run(
            shell(
                "source <(sed '/^case ${1:-} in$/,$d' \"$0\") && check sourced 'eng writes a spec"
                    + " for the loop' eng_writes_spec sourced loop-1 'The build passes'"
                    + fed,
                SCRIPT.toString()));

    assertEquals(1, ended.exit(), ended.stdout() + ended.stderr());
    var log = runs.resolve("sourced").resolve(logOf("eng writes a spec for the loop"));
    assertEquals(
        List.of("FAIL eng writes a spec for the loop (see " + log + ")"), report("sourced"));
    assertTrue(Files.readString(log).contains(says), Files.readString(log));
  }

  @Test
  void aPersonsOwnSshPassesTheInputTheyGiveItOn() throws Exception {
    var ended =
        Child.run(
            fleet(Map.of(), "ssh", "given", "client", "cat"), "given on purpose\n", Child.PATIENCE);

    assertEquals(0, ended.exit(), ended.stdout() + ended.stderr());
    assertEquals("given on purpose\n", Files.readString(calls().getFirst().in()));
  }

  @Test
  void aRemoteCommandThatNeverEndsFailsItsCheckAtTheLimitNamingItAndTheNextCheckRuns()
      throws Exception {
    var never = dir.resolve("never");
    run("mkfifo", never.toString(), dir.resolve("go").toString());
    remote(
        """
        if [[ $1 == client ]]; then
          echo $$ > "$3.pid"
          echo "launching"
          echo > "$FLEET_TEST/go"
          exec cat "$FLEET_TEST/never"
        fi
        """
            + READS_ITS_INPUT);

    try (var held = new RandomAccessFile(never.toFile(), "rw")) {
      var ended =
          Child.run(
              fleet(Map.of("FLEET_CHECK_LIMIT", LIMIT), "containers", "never")
                  .redirectInput(Child.NO_INPUT));

      assertEquals(1, ended.exit(), ended.stdout() + ended.stderr());
      var log = runs.resolve("never").resolve(logOf("client applies acme"));
      assertEquals(
          List.of(
              "FAIL client applies acme (did not end within 1000 s; see " + log + ")",
              "PASS observer applies acme"),
          report("never"));
      var said = Files.readString(log);
      assertTrue(
          said.startsWith("launching\n") && said.endsWith("did not end within 1000 s\n"), said);
      var hung = calls().stream().filter(call -> call.box().equals("client")).findFirst();
      var pid = Long.parseLong(Files.readString(hung.orElseThrow().pid()).strip());
      assertFalse(
          ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
          "the remote command outlived its check");
    }
  }

  @Test
  void aCheckThatIgnoresTermIsKilledAfterItsGraceAndTheRunGoesOn() throws Exception {
    var never = dir.resolve("never");
    run("mkfifo", never.toString(), dir.resolve("go").toString());

    try (var held = new RandomAccessFile(never.toFile(), "rw")) {
      var ended =
          Child.run(
              shell(
                  "source <(sed '/^case ${1:-} in$/,$d' \"$0\") && { check_within %s stubborn"
                          .formatted(LIMIT)
                      + " 'ignores TERM' bash -c 'trap \"\" TERM; echo $$ > \"$FLEET_TEST/pid\";"
                      + " echo > \"$FLEET_TEST/go\"; exec cat \"$FLEET_TEST/never\"' || true; }"
                      + " && check stubborn after true",
                  SCRIPT.toString()));

      assertEquals(0, ended.exit(), ended.stdout() + ended.stderr());
      var log = runs.resolve("stubborn").resolve(logOf("ignores TERM"));
      assertEquals(
          List.of("FAIL ignores TERM (did not end within 1000 s; see " + log + ")", "PASS after"),
          report("stubborn"));
      var pid = Long.parseLong(Files.readString(dir.resolve("pid")).strip());
      assertFalse(
          ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
          "the check that ignored TERM outlived its grace");
    }
  }

  @ParameterizedTest
  @CsvSource({"HUP, 129", "INT, 130", "TERM, 143"})
  void aRunStoppedWhileACheckRunsEndsTheChecksRemoteCommandAndExitsByTheSignal(
      String signal, int exit) throws Exception {
    var never = dir.resolve("never");
    var ready = dir.resolve("ready");
    run("mkfifo", never.toString(), ready.toString());
    remote(
        """
        if [[ $1 == client ]]; then
          echo $$ > "$FLEET_TEST/ready"
          exec cat "$FLEET_TEST/never"
        fi
        """
            + READS_ITS_INPUT);

    try (var held = new RandomAccessFile(never.toFile(), "rw")) {
      var script =
          fleet(Map.of(), "containers", "stopped")
              .redirectInput(Child.NO_INPUT)
              .redirectErrorStream(true)
              .redirectOutput(dir.resolve("said").toFile())
              .start();
      try {
        var hung =
            ProcessHandle.of(
                    Long.parseLong(
                        CompletableFuture.supplyAsync(() -> readFifo(ready))
                            .get(Child.PATIENCE.toMillis(), TimeUnit.MILLISECONDS)
                            .strip()))
                .orElseThrow();
        run("kill", "-" + signal, Long.toString(script.pid()));

        assertTrue(script.waitFor(Child.PATIENCE.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(exit, script.exitValue(), Files.readString(dir.resolve("said")));
        hung.onExit().get(Child.PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
      } finally {
        Child.end(script);
      }
    }
  }

  @Test
  void aJoinWaitsForItsNodesInitStatusAndSaysWhenItNeverCame() throws Exception {
    for (var run : List.of("never-ended", "failed", "inited")) {
      var at = Files.createDirectories(runs.resolve(run));
      Files.writeString(at.resolve("init-eng.out"), "sudo sail fde add eng --role viewer\n");
    }
    Files.writeString(runs.resolve("failed").resolve("init-eng.status"), "1\n");
    Files.writeString(runs.resolve("inited").resolve("init-eng.status"), "0\n");

    var ended =
        Child.run(
            shell(
                "source <(sed '/^case ${1:-} in$/,$d' \"$0\") && until_true() { shift 2; \"$@\";"
                    + " } && for run in never-ended failed inited; do check \"$run\" 'join eng'"
                    + " join_node \"$run\" eng member all || true; done",
                SCRIPT.toString()));

    assertEquals(0, ended.exit(), ended.stdout() + ended.stderr());
    var never = runs.resolve("never-ended").resolve(logOf("join eng"));
    assertEquals(List.of("FAIL join eng (see " + never + ")"), report("never-ended"));
    assertTrue(
        Files.readString(never).endsWith("init on eng did not end within 3300 s\n"),
        Files.readString(never));
    assertTrue(report("failed").getFirst().startsWith("FAIL join eng"));
    assertEquals(List.of("PASS join eng"), report("inited"));
    assertEquals(
        List.of("main: sudo sail fde add eng --role member"),
        calls().stream()
            .filter(call -> call.command().contains("fde add"))
            .map(call -> call.box() + ": " + call.command())
            .toList());
  }

  @Test
  void aCheckBodyGoesOnPastAFailingCommandAsItDidUnderAnIf() throws Exception {
    var ended =
        Child.run(
            shell(
                "source <(sed '/^case ${1:-} in$/,$d' \"$0\")"
                    + " && body() { false; echo went on; } && check body-run 'goes on' body",
                SCRIPT.toString()));

    assertEquals(0, ended.exit(), ended.stdout() + ended.stderr());
    assertEquals(List.of("PASS goes on"), report("body-run"));
  }

  @Test
  void provisioningTurnsOffUbuntusDailyUpgradeAndLetsARunningOneFinishBeforeSailIsInstalled()
      throws Exception {
    var box = Files.createDirectories(dir.resolve("box"));
    executable(box.resolve("sudo"), "exec \"$@\"\n");
    executable(box.resolve("sleep"), "exit 0\n");
    executable(
        box.resolve("systemctl"),
        """
        case $1 in
          disable) echo "systemctl $*" >> "$FLEET_TEST/events-$BOX" ;;
          is-active)
            state=inactive
            [[ -e $FLEET_TEST/upgraded-$BOX ]] || { state=activating; : > "$FLEET_TEST/upgraded-$BOX"; }
            echo "upgrade $state" >> "$FLEET_TEST/events-$BOX"
            [[ $2 == --quiet ]] || printf 'inactive\\n%s\\n' "$state"
            exit 3 ;;
        esac
        """);
    remote(
        READS_ITS_INPUT
            + """
            case $2 in
              *"disable --now"*) PATH=$FLEET_TEST/box:$PATH BOX=$1 exec bash -c "$2" ;;
              *"install.sh | bash -s"*) echo install >> "$FLEET_TEST/events-$1" ;;
            esac
            """
            + answersTimers());

    var ended = Child.run(fleet(Map.of(), "install", "quiet", "0.76.0"));

    assertEquals(0, ended.exit(), ended.stdout() + ended.stderr());
    for (var name : List.of("main", "eng", "client", "observer")) {
      assertEquals(
          List.of(
              "systemctl disable --now " + TIMERS,
              "upgrade activating",
              "upgrade inactive",
              "install"),
          Files.readAllLines(dir.resolve("events-" + name)),
          name);
    }
    assertTrue(
        report("quiet").contains("PASS no box runs Ubuntu's daily package timers"),
        String.join("\n", report("quiet")));
  }

  @Test
  void aBoxWhoseDailyUpgradeTimerIsStillOnFailsTheCheckNamingIt() throws Exception {
    Files.writeString(dir.resolve("timers-client"), "inactive\nactive\ndisabled\nenabled\n");
    remote(READS_ITS_INPUT + answersTimers());

    var ended = Child.run(fleet(Map.of(), "install", "loud", "0.76.0"));

    assertEquals(1, ended.exit(), ended.stdout() + ended.stderr());
    var name = "no box runs Ubuntu's daily package timers";
    var log = runs.resolve("loud").resolve(logOf(name));
    assertTrue(report("loud").contains("FAIL " + name + " (see " + log + ")"));
    assertTrue(
        Files.readString(log).contains("client: inactive active disabled enabled"),
        Files.readString(log));
  }

  @Test
  void aSailApiThatStartedAgainDuringTheIdlePeriodNamesItselfInTheStopCheck() throws Exception {
    var stops = workspaceStops(30);

    assertEquals(1, stops.exit(), stops.stdout());
    var ended = Long.parseLong(Files.readString(dir.resolve("ended")).strip());
    var at = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    assertTrue(
        stops
            .stdout()
            .contains(
                "sail-api on eng started again at "
                    + at.format(Instant.ofEpochSecond(ended + 30))
                    + ", after the idle period began at "
                    + at.format(Instant.ofEpochSecond(ended))),
        stops.stdout());
  }

  @Test
  void aSailApiThatRanThroughTheIdlePeriodLetsTheStopCheckPass() throws Exception {
    var stops = workspaceStops(-600);

    assertEquals(0, stops.exit(), stops.stdout());
    assertFalse(stops.stdout().contains("started again"), stops.stdout());
  }

  /**
   * {@code workspace_stops} on eng for a spec whose last run ended 500 s before eng is asked, its
   * workspace running at the first look and stopped after, and sail-api started {@code restarted}
   * seconds after that run ended: the script's functions sourced, without its dispatch, as the
   * loop's background poll calls it.
   */
  private Child.Ended workspaceStops(long restarted) throws Exception {
    Files.writeString(dir.resolve("restarted"), Long.toString(restarted));
    remote(
        READS_ITS_INPUT
            + """
            case $2 in
              *"agent sessions acme --json"*)
                ended=$(($(date +%s) - 500))
                echo "$ended" > "$FLEET_TEST/ended"
                at=$(date -u -d "@$ended" +%Y-%m-%dT%H:%M:%SZ)
                printf '{"runs": [{"spec_id": "beside-1", "completed_at": "%s"}]}' "$at" ;;
              *"workspace list acme --json"*)
                state=stopped
                [[ -e $FLEET_TEST/looked ]] || { state=running; : > "$FLEET_TEST/looked"; }
                printf '{"workspaces": [{"spec": "beside-1", "state": "%s"}]}' "$state" ;;
              *"-p ActiveEnterTimestamp --value sail-api"*)
                echo "@$(($(cat "$FLEET_TEST/ended") + $(cat "$FLEET_TEST/restarted")))" ;;
            esac
            """);
    return Child.run(
        shell(
            "source <(sed '/^case ${1:-} in$/,$d' \"$0\") && sleep() { :; } && { workspace_stops"
                + " sourced eng"
                + " beside-1 || exit; }",
            SCRIPT.toString()));
  }

  /**
   * The stand-in's answer to the timers check: each box's states, or both off, ending as {@code
   * systemctl is-enabled} does, 1 when none is enabled.
   */
  private static String answersTimers() {
    return """
        case $2 in
          *"is-enabled %s"*)
            cat "$FLEET_TEST/timers-$1" 2> /dev/null && exit 0
            printf 'inactive\\ninactive\\ndisabled\\ndisabled\\n'
            exit 1 ;;
        esac
        """
        .formatted(TIMERS);
  }

  /**
   * The script's functions sourced, its caller's input a line that ends, and {@code checks} run
   * twice with the run as {@code $1}: once as a group's fleet under {@code in_parallel}, as {@code
   * ci/fleet run} runs it, in run {@code grouped-access}, then once alone, as a serial run does, in
   * run {@code alone}.
   */
  private Child.Ended inAGroupAndAlone(String checks) throws Exception {
    return Child.run(
        shell(
            "source <(sed '/^case ${1:-} in$/,$d' \"$0\") && checks() { "
                + checks
                + "; } && fleet() { checks \"$1\"; } && mkdir -p \"$RUNS/grouped\""
                + " && { in_parallel grouped 0.76.0 '' access || true; checks alone || true; }",
            SCRIPT.toString()),
        "the caller's input\n",
        Child.PATIENCE);
  }

  /**
   * A box's {@code sail spec} as far as the loop's spec rows go: a create keeps what {@code keeps}
   * passes on of the body it reads from its input, and a show prints it back as {@code spec show
   * --json} does.
   */
  private static String storesTheSpecsBody(String keeps) {
    return """
        case $2 in
          *"sail spec create"*) %s > "$FLEET_TEST/stored" ;;
          *"sail spec show"*)
            python3 -I -c 'import json,sys; print(json.dumps({"spec": {}, "body": open(sys.argv[1]).read()}))' "$FLEET_TEST/stored" ;;
        esac
        """
        .formatted(keeps);
  }

  private static String readFifo(Path fifo) {
    try {
      return Files.readString(fifo);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Call callEndingWith(String command) throws IOException {
    return calls().stream()
        .filter(call -> call.command().endsWith(command))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no call ends with " + command));
  }

  /** One ssh the script made: the box, the remote command, and what that command read. */
  private record Call(String box, String command, Path in, Path pid) {}

  private List<Call> calls() throws IOException {
    try (Stream<Path> files = Files.list(calls)) {
      var made = new ArrayList<Call>();
      for (var box : files.filter(file -> file.toString().endsWith(".box")).sorted().toList()) {
        var call = box.toString().substring(0, box.toString().length() - ".box".length());
        made.add(
            new Call(
                Files.readString(box),
                Files.readString(Path.of(call + ".command")),
                Path.of(call + ".in"),
                Path.of(call + ".pid")));
      }
      return made;
    }
  }

  private List<String> report(String run) throws IOException {
    return Files.readAllLines(runs.resolve(run).resolve("report.txt"));
  }

  /** A check's log file name, as the script makes it. */
  private String logOf(String name) throws Exception {
    var named =
        Child.run(
            shell(
                "source <(sed '/^case ${1:-} in$/,$d' \"$0\") && log_name \"$1\"",
                SCRIPT.toString(),
                name));
    assertEquals(0, named.exit(), named.stderr());
    return named.stdout();
  }

  /** The text of the script's single-quoted constant {@code name}. */
  private static String constant(String name) throws IOException {
    var script = Files.readString(SCRIPT);
    var start = script.indexOf("readonly " + name + "='") + ("readonly " + name + "='").length();
    return script.substring(start, script.indexOf("'\n", start));
  }

  /**
   * The script run with {@code args} while the test holds its input open, a pipe nobody closes,
   * until it has ended or {@link Child#PATIENCE} has passed.
   */
  private Child.Ended whileItsInputStaysOpen(String... args) throws Exception {
    var builder = fleet(Map.of(), args).redirectErrorStream(true);
    var process = builder.start();
    var lines = new LinkedBlockingQueue<String>();
    var drained = Child.drain(process, lines);
    try {
      var ended = process.waitFor(Child.PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
      assertTrue(ended, "still running while its input stayed open: " + lines);
      assertTrue(drained.join(Child.PATIENCE), "what it started still holds its output: " + lines);
      return new Child.Ended(process.exitValue(), String.join("\n", lines), "");
    } finally {
      Child.end(process);
      drained.join(Child.PATIENCE);
    }
  }

  private ProcessBuilder fleet(Map<String, String> env, String... args) {
    var command = new ArrayList<>(List.of(SCRIPT.toString()));
    command.addAll(List.of(args));
    return builder(env, command);
  }

  /** {@code bash -c line} with {@code args} as its {@code $0} and on, in the stand-ins' cloud. */
  private ProcessBuilder shell(String line, String... args) {
    var command = new ArrayList<>(List.of("bash", "-c", line));
    command.addAll(List.of(args));
    return builder(Map.of(), command);
  }

  private ProcessBuilder builder(Map<String, String> env, List<String> command) {
    var builder = new ProcessBuilder(command);
    builder.directory(dir.toFile());
    builder.environment().putAll(env);
    builder.environment().put("FLEET_TEST", dir.toString());
    builder.environment().put("FLEET_RUNS", runs.toString());
    builder.environment().put("PATH", dir.resolve("bin") + ":" + builder.environment().get("PATH"));
    return builder;
  }

  private void remote(String body) throws IOException {
    executable(dir.resolve("remote"), body);
  }

  private static void executable(Path file, String body) throws IOException {
    Files.writeString(file, "#!/usr/bin/env bash\n" + body);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
  }

  private Child.Ended run(String... command) throws Exception {
    var ran = Child.run(new ProcessBuilder(command).directory(dir.toFile()));
    assertEquals(0, ran.exit(), String.join(" ", command) + ": " + ran.stdout() + ran.stderr());
    return ran;
  }
}
