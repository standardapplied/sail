/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * R1: no class on a run's path, nor any door that reads or attaches to a run (its status, log,
 * report and conversation), addresses a container by its project's name. Every such call of {@code
 * ContainerExec}, {@code AgentSession}, {@code AgentPresence}, {@code SnapshotManager}, {@code
 * ContainerManager}, a git-activity read or an attach's tty takes the run's {@link
 * ai.singlr.sail.engine.RunPlace} or its instance, never {@code project}, {@code name}, {@code
 * run.project()}, {@code s.project()} or a lane's own {@code project()} accessor. The forms looked
 * for are those calls with such a name as their first argument, the receiver written out or held in
 * a field or local named for it. A container named by a variable of another name, a call built by
 * reflection or a helper that takes the name and passes it on under another is beyond what this
 * sweep can see.
 */
class RunPlaceSweepTest {

  private static final String PROJECT =
      "(?:project|name|projectName|run\\.project\\(\\)|s\\.project\\(\\)"
          + "|seat\\.project\\(\\)|invocation\\.project\\(\\)|ctx\\.project\\(\\)"
          + "|session\\.project\\(\\)|container\\.name\\(\\))";

  private static final List<Pattern> FORMS =
      List.of(
          Pattern.compile("ContainerExec\\.as\\w+\\(\\s*" + PROJECT + "\\s*[,)]"),
          Pattern.compile(
              "(?:new AgentSession\\([^)]*\\)|\\bsession|\\bagentSession)\\.\\w+\\(\\s*"
                  + PROJECT
                  + "\\s*[,)]"),
          Pattern.compile(
              "(?:new AgentPresence\\([^)]*\\)|\\bpresence)\\.(?:gone|of)\\(\\s*"
                  + PROJECT
                  + "\\s*[,)]"),
          Pattern.compile(
              "(?:new SnapshotManager\\([^)]*\\)|\\bsnapMgr|\\bsnapshots)\\.\\w+\\(\\s*"
                  + PROJECT
                  + "\\s*[,)]"),
          Pattern.compile(
              "(?:new ContainerManager\\([^)]*\\)|\\bcontainers|\\bmgr)\\.\\w+\\(\\s*"
                  + PROJECT
                  + "\\s*[,)]"),
          Pattern.compile(
              "(?:killAgent|queryStatus|readProcessStartTicks|answeredExitStatus|unitActive"
                  + "|removeLaunchFiles|writeTaskFile|writeSession|ensureDirectory"
                  + "|queryGitActivity|buildIncusExecWithTty|querySession)\\(\\s*"
                  + "(?:agentSession,\\s*)?"
                  + PROJECT
                  + "\\s*,"));

  /**
   * The run-path classes the spec names, each of which a spec's run passes through, and the doors
   * that read or attach to a run where it lives.
   */
  private static final List<Path> RUN_PATH =
      Stream.concat(
              Stream.of(
                      "RunLauncher",
                      "ReviewLaneLauncher",
                      "StopOperations",
                      "MissedStopReconciler",
                      "RunReservation",
                      "RunWatch",
                      "WatcherRearmer",
                      "AgentLogStreamer",
                      "LogAdmission",
                      "RoomCommitGuard",
                      "LiveMembers",
                      "RoomWakeLauncher",
                      "BuildDispatch",
                      "WorkBranch",
                      "RunPlaces",
                      "SailOperations",
                      "MembershipService")
                  .map(name -> Path.of("src/main/java/ai/singlr/sail/api", name + ".java")),
              Stream.of(
                  Path.of("src/main/java/ai/singlr/sail/engine/AgentPresence.java"),
                  Path.of("src/main/java/ai/singlr/sail/engine/WatcherSpawner.java"),
                  Path.of("src/main/java/ai/singlr/sail/commands/AgentWatchCommand.java"),
                  Path.of("src/main/java/ai/singlr/sail/commands/AgentLogCommand.java"),
                  Path.of("src/main/java/ai/singlr/sail/commands/AgentAttachCommand.java"),
                  Path.of("src/main/java/ai/singlr/sail/commands/AgentStatusCommand.java"),
                  Path.of("src/main/java/ai/singlr/sail/commands/AgentReportCommand.java"),
                  Path.of(
                      "..", "sail-harness/src/main/java/ai/singlr/sail/engine/AgentReporter.java"),
                  Path.of(
                      "..", "sail-harness/src/main/java/ai/singlr/sail/engine/RunUsageReader.java"),
                  Path.of(
                      "..",
                      "sail-harness/src/main/java/ai/singlr/sail/engine/GuardrailTrigger.java"),
                  Path.of(
                      "..", "sail-harness/src/main/java/ai/singlr/sail/engine/RunRetention.java")))
          .toList();

  @Test
  void everyFormTheSweepLooksForIsSeenAndARunsPlaceIsNot() {
    var addressedByProject =
        List.of(
            "ContainerExec.asDevUser(project, List.of(\"cat\", path))",
            "ContainerExec.asRoot(run.project())",
            "new AgentSession(shell).killAgent(run.project(), unit)",
            "session.answeredExitStatus(project, unit)",
            "presence.gone(project, unit)",
            "new AgentPresence(shell).of(s.project(), unit)",
            "new SnapshotManager(shell).create(project, label)",
            "containers.queryState(seat.project())",
            "readProcessStartTicks(ctx.project(), pid)",
            "buildIncusExecWithTty(name, command)",
            "checker.queryGitActivity(projectName, repoPath, started)",
            "querySession(agentSession, project, unit)",
            "new ContainerManager(shell).queryState(name)");
    var addressedByPlace =
        List.of(
            "ContainerExec.asDevUser(place.instance(), List.of(\"cat\", path))",
            "new AgentSession(shell).killAgent(run.place().instance(), unit)",
            "presence.gone(place, unit)",
            "new SnapshotManager(shell).create(instance, label)",
            "containers.queryState(container.project())",
            "ContainerExec.asDevUser(projectContainer, List.of())",
            "buildIncusExecWithTty(place.instance(), command)",
            "checker.queryGitActivity(probe.instance(), repoPath, started)");
    for (var line : addressedByProject) {
      assertTrue(FORMS.stream().anyMatch(form -> form.matcher(line).find()), line);
    }
    for (var line : addressedByPlace) {
      assertFalse(FORMS.stream().anyMatch(form -> form.matcher(line).find()), line);
    }
  }

  @Test
  void noRunPathClassAddressesAContainerByItsProjectsName() {
    var found =
        RUN_PATH.stream()
            .flatMap(
                source -> {
                  var text = read(source);
                  return FORMS.stream()
                      .flatMap(form -> form.matcher(text).results())
                      .map(match -> source.getFileName() + ": " + match.group());
                })
            .toList();

    assertEquals(List.of(), found);
  }

  private static String read(Path source) {
    try {
      return Files.readString(source);
    } catch (IOException e) {
      throw new AssertionError("The sweep cannot read " + source + ", a run-path class.", e);
    }
  }
}
