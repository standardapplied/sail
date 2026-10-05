/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class AgentPresenceTest {

  private static final String RUN_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final AgentUnit UNIT = AgentUnit.forRun(RUN_ID);

  private static ScriptedShellExecutor containerListedAs(String status) {
    return new ScriptedShellExecutor()
        .onOk(
            "incus list", "[{\"name\": \"acme\", \"status\": \"" + status + "\", \"state\": {}}]");
  }

  private static AgentSession.Presence presenceIn(ShellExec shell) throws Exception {
    return new AgentPresence(shell).of("acme", UNIT);
  }

  @Test
  void whatTheContainerAnswersIsTheAnswer() throws Exception {
    var live =
        containerListedAs("Stopped")
            .onOk("cat " + UNIT.pidPath(), "12345\n")
            .onOk("kill -0 12345", "");
    var gone = containerListedAs("Running").onOk("printf gone", "gone");

    assertInstanceOf(AgentSession.Presence.Running.class, presenceIn(live));
    assertInstanceOf(AgentSession.Presence.Gone.class, presenceIn(gone));
    assertEquals(
        0,
        live.invocations().stream().filter(command -> command.startsWith("incus list")).count(),
        "incus is asked about the container only when the container itself said nothing");
  }

  @Test
  void aContainerThatIsStoppedRunsNoAgent() throws Exception {
    assertInstanceOf(AgentSession.Presence.Gone.class, presenceIn(containerListedAs("Stopped")));
  }

  @Test
  void aContainerThatDoesNotExistRunsNoAgent() throws Exception {
    assertInstanceOf(
        AgentSession.Presence.Gone.class,
        presenceIn(new ScriptedShellExecutor().onOk("incus list", "[]")));
  }

  @Test
  void aRunningContainerThatRanNoCommandLeavesTheQuestionOpen() throws Exception {
    assertInstanceOf(
        AgentSession.Presence.Unanswered.class, presenceIn(containerListedAs("Running")));
  }

  @Test
  void aContainerIncusCannotReportOnLeavesTheQuestionOpen() throws Exception {
    assertInstanceOf(
        AgentSession.Presence.Unanswered.class,
        presenceIn(containerListedAs("Frozen")),
        "an incus outage, or a state it does not name, never reads as every agent gone");
  }
}
