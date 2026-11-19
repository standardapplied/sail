/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyAdmissions;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class PtyHostYieldTest {

  private static final PtyIdentity.Resolver RESOLVER =
      token ->
          switch (token) {
            case "" -> new PtyIdentity.Person(new Actor("box", Role.MEMBER, Actor.Lane.API));
            case "tok-mady" ->
                new PtyIdentity.Person(new Actor("mady", Role.MEMBER, Actor.Lane.API));
            default -> throw new IOException("Session token is not valid or has expired.");
          };

  @TempDir Path dir;

  @Test
  void endsOnlyTheNamedLiveSessionsAndAMissingHostHasNothingToEnd() throws Exception {
    assertDoesNotThrow(
        () -> new PtyHostYield(dir.resolve("absent.sock")).end(List.of("resume-1"), "r"),
        "no socket means no host and no sessions");

    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            RESOLVER,
            PtyAdmissions.NONE,
            PtyEvents.NONE)) {
      try (var client = SessionClient.connect(dir.resolve("h.sock"), "")) {
        client.create("resume-1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
        client.create("shell", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }

      new PtyHostYield(dir.resolve("h.sock"))
          .end(List.of("resume-1", "resume-ghost"), "yielded to dispatch 2");

      try (var client = SessionClient.connect(dir.resolve("h.sock"), "")) {
        assertEquals(
            List.of("resume-1 yielded to dispatch 2", "shell live"),
            client.list().stream()
                .map(info -> info.name() + " " + (info.live() ? "live" : info.endedReason()))
                .toList(),
            "the named live session ended, the unnamed one lives, the absent one is skipped");
      }
      assertEquals(2, host.sessionCount(), "the ended one is kept until its retention passes");
    }
  }

  /**
   * A resume session opened through the gateway belongs to the FDE who attached, not to the box
   * owner the API would be. The seam speaks as the host's dispatch authority, so the claim ends
   * every displaced session whoever opened it — the box owner's, another FDE's, all in one pass.
   */
  @Test
  void theDispatchAuthorityEndsSessionsWhoeverOpenedThem() throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            RESOLVER,
            PtyAdmissions.NONE,
            PtyEvents.NONE)) {
      try (var mady = SessionClient.connect(dir.resolve("h.sock"), "tok-mady")) {
        mady.create("resume-1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }
      try (var box = SessionClient.connect(dir.resolve("h.sock"), "")) {
        box.create("resume-2", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
        assertThrows(
            IOException.class,
            () -> box.yieldSession("resume-1", "r"),
            "an FDE, even the box owner, holds no yield authority");
      }

      var yield = new PtyHostYield(dir.resolve("h.sock"));
      yield.end(List.of("resume-1", "resume-2"), "r");

      assertEquals(
          List.of(false, false),
          List.of(yield.live("resume-1"), yield.live("resume-2")),
          "both ended, mady's included");
    }
  }

  /**
   * A live member asks whether a terminal holds the conversation it would resume. The answer covers
   * every FDE's sessions, since the terminal may be anyone's, and a box with no host holds none.
   */
  @Test
  void aSessionIsLiveWhoeverOpenedItAndNoHostMeansNoSession() throws Exception {
    assertFalse(new PtyHostYield(dir.resolve("absent.sock")).live("resume-1"));

    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            RESOLVER,
            PtyAdmissions.NONE,
            PtyEvents.NONE)) {
      try (var mady = SessionClient.connect(dir.resolve("h.sock"), "tok-mady")) {
        mady.create("resume-1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }
      var yield = new PtyHostYield(dir.resolve("h.sock"));

      assertTrue(yield.live("resume-1"), "another FDE's terminal still holds the conversation");
      assertFalse(yield.live("resume-2"));

      yield.end(List.of("resume-1"), "r");

      assertFalse(yield.live("resume-1"), "a session that ended holds nothing");
    }
  }

  @Test
  void aHostThatMintedNoCredentialIsAnErrorNotASilentSkip() throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            RESOLVER,
            PtyAdmissions.NONE,
            PtyEvents.NONE)) {
      try (var mady = SessionClient.connect(dir.resolve("h.sock"), "tok-mady")) {
        mady.create("resume-1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }
      Files.delete(PtySessionHost.dispatchCredentialOf(dir.resolve("h.sock")));

      var failed =
          assertThrows(
              IOException.class,
              () -> new PtyHostYield(dir.resolve("h.sock")).end(List.of("resume-1"), "r"));
      assertTrue(failed.getMessage().contains("dispatch credential"), failed.getMessage());
      assertEquals(1, host.sessionCount(), "the session it could not end is reported, not hidden");
    }
  }

  /**
   * Review 1's final F-M2: what a stop or a forced delete of workspace {@code try} of acme ends is
   * the live sessions in that workspace alone: not one in acme's own container, in acme's other
   * workspace, in another project's workspace of the same name, or one already ended.
   */
  @Test
  void aWorkspacesTerminalsAreTheLiveSessionsInThatWorkspaceAlone() {
    var sessions =
        List.of(
            session("in-try", true, "acme", "try"),
            session("ended-in-try", false, "acme", "try"),
            session("in-container", true, "acme", ""),
            session("in-spare", true, "acme", "spare"),
            session("beta-try", true, "beta", "try"),
            session("no-project", true, "", ""));

    assertEquals(List.of("in-try"), PtyHostYield.liveIn(sessions, "acme", "try"));
    assertEquals(List.of("in-spare"), PtyHostYield.liveIn(sessions, "acme", "spare"));
  }

  private static PtyMessage.SessionInfo session(
      String name, boolean live, String project, String workspace) {
    return new PtyMessage.SessionInfo(name, "i-" + name, live, 0, "", "", List.of())
        .placed(project, workspace, "ada");
  }
}
