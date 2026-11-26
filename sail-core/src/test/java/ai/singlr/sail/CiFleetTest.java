/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.Child;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

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
    run("mkfifo", never.toString());
    remote(
        """
        if [[ $1 == client ]]; then
          echo $$ > "$3.pid"
          echo "launching"
          exec cat "$FLEET_TEST/never"
        fi
        """
            + READS_ITS_INPUT);

    try (var held = new RandomAccessFile(never.toFile(), "rw")) {
      var ended =
          Child.run(
              fleet(Map.of("FLEET_CHECK_LIMIT", "3"), "containers", "never")
                  .redirectInput(Child.NO_INPUT));

      assertEquals(1, ended.exit(), ended.stdout() + ended.stderr());
      var log = runs.resolve("never").resolve(logOf("client applies acme"));
      assertEquals(
          List.of(
              "FAIL client applies acme (did not end within 3 s; see " + log + ")",
              "PASS observer applies acme"),
          report("never"));
      var said = Files.readString(log);
      assertTrue(said.startsWith("launching\n") && said.endsWith("did not end within 3 s\n"), said);
      var hung = calls().stream().filter(call -> call.box().equals("client")).findFirst();
      var pid = Long.parseLong(Files.readString(hung.orElseThrow().pid()).strip());
      assertFalse(
          ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
          "the remote command outlived its check");
    }
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
  void provisioningTurnsOffUbuntusDailyUpgradeOnEveryBoxBeforeSailIsInstalled() throws Exception {
    remote(READS_ITS_INPUT + answersTimers());

    var ended = Child.run(fleet(Map.of(), "install", "quiet", "0.76.0"));

    assertEquals(0, ended.exit(), ended.stdout() + ended.stderr());
    for (var box : List.of("main", "eng", "client", "observer")) {
      var sent = calls().stream().filter(call -> call.box().equals(box)).toList();
      var quieted = indexOf(sent, "sudo systemctl disable --now " + TIMERS);
      var installed = indexOf(sent, "install.sh | bash -s 0.76.0");
      assertTrue(quieted >= 0 && quieted < installed, box + " was sent " + commands(sent));
      assertTrue(
          sent.get(quieted)
              .command()
              .contains("systemctl is-active --quiet apt-daily.service apt-daily-upgrade.service"),
          "an upgrade already running is not waited for: " + sent.get(quieted).command());
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
   * {@code workspace_stops} on eng for a spec whose last run ended 539 s before eng is asked, its
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
                ended=$(($(date +%s) - 539))
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
            "source <(sed '/^case ${1:-} in$/,$d' \"$0\") && { workspace_stops sourced eng"
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

  private static int indexOf(List<Call> sent, String part) {
    for (var i = 0; i < sent.size(); i++) {
      if (sent.get(i).command().contains(part)) {
        return i;
      }
    }
    return -1;
  }

  private static List<String> commands(List<Call> sent) {
    return sent.stream().map(Call::command).toList();
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
