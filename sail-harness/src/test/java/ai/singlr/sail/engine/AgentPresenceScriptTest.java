/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The question "is this run's agent there" as the container answers it: the real script, run here
 * against the test's own processes and files.
 */
class AgentPresenceScriptTest {

  @TempDir Path dir;
  private Process agent;

  @AfterEach
  void endTheAgent() {
    if (agent != null) {
      agent.destroyForcibly();
    }
  }

  private AgentUnit unitWithPidFile(String content) throws Exception {
    var pidFile = Files.writeString(dir.resolve("agent.pid"), content);
    return new AgentUnit("", "agent.log", pidFile.toString(), "agent-session.json", "task.txt");
  }

  private AgentSession.Presence presenceOf(AgentUnit unit) throws Exception {
    return new AgentSession(new Here()).presence("acme-health", unit);
  }

  @Test
  void aPidFileNamingALiveProcessIsARunningAgent() throws Exception {
    agent = new ProcessBuilder("sleep", "300").start();

    assertInstanceOf(
        AgentSession.Presence.Running.class, presenceOf(unitWithPidFile(agent.pid() + "\n")));
  }

  @Test
  void aPidFileNamingAProcessThatEndedIsAnAgentGone() throws Exception {
    agent = new ProcessBuilder("true").start();
    agent.waitFor();

    assertInstanceOf(
        AgentSession.Presence.Gone.class, presenceOf(unitWithPidFile(agent.pid() + "\n")));
  }

  @Test
  void aPidFileThatNamesNoProcessOrIsNotThereIsAnAgentGone() throws Exception {
    assertInstanceOf(AgentSession.Presence.Gone.class, presenceOf(unitWithPidFile("not a pid\n")));
    assertInstanceOf(
        AgentSession.Presence.Gone.class,
        presenceOf(
            new AgentUnit(
                "",
                "agent.log",
                dir.resolve("absent.pid").toString(),
                "agent-session.json",
                "task.txt")));
  }

  private AgentUnit unitWithNoPidFile() {
    return new AgentUnit(
        "sail-agent-test",
        "agent.log",
        dir.resolve("absent.pid").toString(),
        dir.resolve("agent-session.json").toString(),
        "task.txt");
  }

  /** A user manager that answers every {@code systemctl} with {@code body}. */
  private Here managerThat(String body) throws Exception {
    var bin = Files.createDirectories(dir.resolve("bin"));
    var systemctl = Files.writeString(bin.resolve("systemctl"), "#!/usr/bin/env bash\n" + body);
    Files.setPosixFilePermissions(systemctl, PosixFilePermissions.fromString("rwx------"));
    return new Here(bin);
  }

  @Test
  void aUnitWhoseManagerDoesNotAnswerAndWhosePidFileNamesNothingIsNotKnownGone() throws Exception {
    var silent = managerThat("echo 'Failed to connect to bus' >&2\nexit 1\n");

    assertInstanceOf(
        AgentSession.Presence.Unanswered.class,
        new AgentSession(silent).presence("acme-health", unitWithNoPidFile()),
        "the container answers, but the one thing that could name the unit's process did not");
  }

  @Test
  void aUnitItsManagerSaysHasNoProcessIsGone() throws Exception {
    var collected = managerThat("echo 0\n");

    assertInstanceOf(
        AgentSession.Presence.Gone.class,
        new AgentSession(collected).presence("acme-health", unitWithNoPidFile()));
  }

  @Test
  void aUnitItsManagerNamesALiveProcessForIsRunning() throws Exception {
    agent = new ProcessBuilder("sleep", "300").start();
    var live = managerThat("echo " + agent.pid() + "\n");

    assertInstanceOf(
        AgentSession.Presence.Running.class,
        new AgentSession(live).presence("acme-health", unitWithNoPidFile()));
  }

  @Test
  void aPidFileIsReadAsItsStatusReadsItSoThePidZeroAndAPaddedPidAreNotMisread() throws Exception {
    agent = new ProcessBuilder("true").start();
    agent.waitFor();
    var silent = managerThat("exit 1\n");
    var padded =
        new AgentUnit(
            "sail-agent-test",
            "agent.log",
            Files.writeString(dir.resolve("padded.pid"), "  " + agent.pid() + " \r\n").toString(),
            "agent-session.json",
            "task.txt");
    var zero =
        new AgentUnit(
            "sail-agent-test",
            "agent.log",
            Files.writeString(dir.resolve("zero.pid"), "0\n").toString(),
            "agent-session.json",
            "task.txt");

    assertInstanceOf(
        AgentSession.Presence.Gone.class,
        new AgentSession(silent).presence("acme-health", padded),
        "the pid is the file's content less its whitespace: it names a process that ended, so"
            + " the manager, which does not answer, is never asked");
    assertInstanceOf(
        AgentSession.Presence.Unanswered.class,
        new AgentSession(silent).presence("acme-health", zero),
        "pid 0 names no process, so the unit's manager is asked, and it did not answer");
  }
}
