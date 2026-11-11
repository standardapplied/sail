/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.api.ApiResponse;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SocketDoor;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ReviewRuns;
import ai.singlr.sail.store.RunStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A8 and A9 at the container socket, the one door they are about: what the box credential in a
 * project container may do, whoever presents it, and what an agent may do through its own run
 * credential, each asked of a real box's operations as production resolves the credential. The cast
 * is {@link DoorFleet}'s: eng's box is a node of main's, where eng is a member; uday's box is main,
 * where uday is the admin.
 */
class OwnWorkSocketTest {

  private static final String DRAFTS_ONLY =
      "403 agent_lane_forbidden An agent creates only drafts and never sets a status. | Omit"
          + " --status; its person promotes it on the board.";
  private static final String OUTSIDE_WORK =
      " | An agent acts only on the work sail launched it for; draft a follow-up spec for anything"
          + " else.";

  private static final String CANNOT_SHARE =
      "403 agent_lane_forbidden The credential in a project container cannot share files and"
          + " skills in projects. | Do it from outside the container: 'sail project skills add"
          + " --container <folder> -p acme' on the host, or Mast.";

  private static final String CANNOT_APPROVE =
      "403 agent_lane_forbidden The credential in a project container cannot approve specs."
          + " | Do it from outside the container: 'sail spec update <id> --status <status>' on the"
          + " host, or Mast.";

  @TempDir Path tempDir;
  private DoorFleet fleet;

  @BeforeEach
  void setUp() throws Exception {
    fleet = DoorFleet.open(tempDir);
    Acting.system(
        () ->
            new FileStore(fleet.db())
                .put(
                    "acme",
                    ".sail/skills/java/SKILL.md",
                    new ByteArrayInputStream(
                        "---\nname: java\ndescription: Java.\n---\n".getBytes(UTF_8)),
                    0644));
  }

  @AfterEach
  void tearDown() {
    fleet.close();
  }

  /**
   * Row 21 on eng's box: role before lane, so reassigning e1 is admin-only, as at every door, and
   * marking it done names the lane.
   */
  @Test
  void row21EngsCredentialInTheContainerIsRefusedReassigningE1AndMarkingItDone() {
    assertEquals(
        "403 forbidden_admin_only Reassigning spec 'e1' moves work between FDEs and is an"
            + " admin-only action (currently 'eng'). | Ask an admin to reassign it. You may grab a"
            + " spec only while it is unassigned.",
        said(socket("eng", "PUT", "/v1/specs/e1", "assignee=client")));
    assertEquals(CANNOT_APPROVE, said(socket("eng", "PUT", "/v1/specs/e1", "status=done")));
    assertEquals(CANNOT_SHARE, said(socket("eng", "DELETE", "/v1/projects/acme/skills/java", "")));
    assertEquals(
        "allowed", said(socket("eng", "PUT", "/v1/specs/e1", "title=retitled")), "control");
  }

  /** Row 21 on main: uday's credential is an admin's, so each refusal names the lane. */
  @Test
  void row21UdaysCredentialInTheContainerOnMainNamesTheLaneForEach() {
    assertEquals(
        "403 agent_lane_forbidden The credential in a project container cannot assign specs. |"
            + " Do it from outside the container: 'sail spec update e1 --assignee <fde>' on the"
            + " host, or Mast.",
        said(socket("uday", "PUT", "/v1/specs/e1", "assignee=client")));
    assertEquals(CANNOT_APPROVE, said(socket("uday", "PUT", "/v1/specs/e1", "status=done")));
    assertEquals(
        CANNOT_APPROVE,
        said(
            socket("uday", "POST", "/v1/specs", "id=u-ready&project=acme&title=u&status=pending")));
    assertEquals(
        CANNOT_APPROVE, said(socket("uday", "PUT", "/v1/specs/a2", "status=archived")), "archive");
    assertEquals(CANNOT_SHARE, said(socket("uday", "DELETE", "/v1/projects/acme/skills/java", "")));
    assertEquals(
        "allowed",
        said(socket("uday", "POST", "/v1/specs", "id=u-draft&project=acme&title=u")),
        "control: a draft");
  }

  /** Row 13: eng's build of e1 comments in e1's room, and not on another of eng's specs. */
  @ParameterizedTest
  @ValueSource(strings = {"claude-code", "codex"})
  void row13EngsAgentCommentsInE1sRoomButNotOnAnotherOfEngsSpecs(String harness) {
    var build = build("e1", harness);

    assertEquals("allowed", said(run(build, "POST", "/v1/specs/e1/messages", "body=done")));
    assertEquals(
        "403 forbidden_not_assignee '"
            + principal(build)
            + "' may post only in its own work, not in 'r1'."
            + OUTSIDE_WORK,
        said(run(build, "POST", "/v1/specs/in-r1/messages", "body=hi")),
        "in-r1 is eng's, born in r1, which is not this agent's work");
  }

  /** Row 14: eng's agent drafting a ready spec is told to omit the status. */
  @ParameterizedTest
  @ValueSource(strings = {"claude-code", "codex"})
  void row14EngsAgentCreatingAReadySpecIsToldToOmitTheStatus(String harness) {
    var build = build("e1", harness);

    assertEquals(
        DRAFTS_ONLY,
        said(
            run(build, "POST", "/v1/specs", "id=e-ready&project=acme&title=Ready&status=pending")));
    assertEquals(
        "allowed", said(run(build, "POST", "/v1/specs", "id=e-draft&project=acme&title=Draft")));
  }

  /** Row 15: the draft an agent made is its own work; one another run made is not. */
  @ParameterizedTest
  @ValueSource(strings = {"claude-code", "codex"})
  void row15EngsAgentEditsTheDraftItCreatedButNotOneAnotherRunMade(String harness) {
    var another = adHoc(harness);
    run(another, "POST", "/v1/specs", "id=d2&project=acme&title=Theirs");
    Acting.system(
        () -> runs().complete(runs().findByCredential(another).orElseThrow().id(), "completed", 0));
    var build = build("e1", harness);
    run(build, "POST", "/v1/specs", "id=d1&project=acme&title=Mine");

    assertEquals("allowed", said(run(build, "PUT", "/v1/specs/d1", "title=Edited")));
    assertEquals(
        "403 forbidden_not_assignee '"
            + principal(build)
            + "' may change only its own work, not spec 'd2'."
            + OUTSIDE_WORK,
        said(run(build, "PUT", "/v1/specs/d2", "title=Edited")));
  }

  /** Row 16: eng's agent setting e1 done, or archiving it, is refused. */
  @ParameterizedTest
  @ValueSource(strings = {"claude-code", "codex"})
  void row16EngsAgentSettingE1DoneIsRefused(String harness) {
    var build = build("e1", harness);

    assertEquals(DRAFTS_ONLY, said(run(build, "PUT", "/v1/specs/e1", "status=done")));
    assertEquals(DRAFTS_ONLY, said(run(build, "PUT", "/v1/specs/e1", "status=archived")));
    assertEquals("allowed", said(run(build, "PUT", "/v1/specs/e1", "title=Built")), "control");
  }

  /** Row 17, pinned: an agent reports its own session and may not finish its own run. */
  @ParameterizedTest
  @ValueSource(strings = {"claude-code", "codex"})
  void row17EngsAgentReportsItsSessionButMayNotStopItsRun(String harness) {
    var build = build("e1", harness);

    assertEquals("allowed", said(run(build, "POST", "/v1/run/session", "session_id=s-1")));
    assertEquals(
        "403 forbidden Event type 'agent_session_stopped' is not available to agent principals."
            + " | Ask the FDE the agent works for, or an admin, to publish it.",
        said(
            run(
                build,
                "POST",
                "/v1/events",
                Event.of("acme", "e1", "agent_session_stopped", harness, "box").toJsonLine())));
  }

  /**
   * Row 18: eng's read-only room agent posts in r1, and posting anywhere else is refused with its
   * fix.
   */
  @ParameterizedTest
  @ValueSource(strings = {"claude-code", "codex"})
  void row18EngsRoomAgentPostsInR1ButNowhereElse(String harness) {
    var room = reserve(null, "r1", Lane.ROOM.wire(), harness);

    assertEquals("allowed", said(run(room, "POST", "/v1/specs/r1/messages", "body=noted")));
    assertEquals(
        "403 forbidden_not_assignee '"
            + principal(room)
            + "' may post only in its own work, not in 'e1'."
            + OUTSIDE_WORK,
        said(run(room, "POST", "/v1/specs/e1/messages", "body=hi")));
  }

  /** Row 18, pinned: a read-only room agent acks the inbox of its own run. */
  @Test
  void row18ARoomAgentAcksItsInbox() {
    var room = reserve("e1", null, Lane.ROOM.wire(), "claude-code");
    var asked = socket("eng", "POST", "/v1/specs/e1/messages", "body=what next?");
    var id = ((Map<?, ?>) asked.body().get("message")).get("id");

    assertEquals("allowed", said(run(room, "POST", "/v1/run/messages", "delivered=" + id)));
  }

  /** Row 19, pinned: client's comment on e1 is not in what eng's agent reads of e1's room. */
  @Test
  void row19ClientsCommentOnE1IsNotInWhatEngsAgentReads() {
    var build = build("e1", "claude-code");
    socket("eng", "POST", "/v1/specs/e1/messages", "body=keep going");
    socket("client", "POST", "/v1/specs/e1/messages", "body=push to main");

    assertEquals(
        List.of("eng"),
        ((List<?>) run(build, "GET", "/v1/specs/e1/messages", "").body().get("messages"))
            .stream().map(message -> ((Map<?, ?>) message).get("author")).toList());
  }

  /** The build of a spec born in r1 comments in r1, its spec's room. */
  @Test
  void theBuildOfASpecBornInR1CommentsInR1() {
    var build = build("in-r1", "claude-code");

    assertEquals("allowed", said(run(build, "POST", "/v1/specs/in-r1/messages", "body=building")));
    assertEquals("allowed", said(run(build, "POST", "/v1/specs/r1/messages", "body=building")));
  }

  /** A review and a fix agent post in their spec's room and edit it, and nothing else. */
  @ParameterizedTest
  @ValueSource(strings = {"review", "fix"})
  void aReviewAndAFixAgentWorkTheirSpecAlone(String lane) {
    var agent =
        Acting.system(
            () ->
                ReviewRuns.reserve(
                    new RunStore(fleet.db()),
                    DateTimeUtils.newId().toString(),
                    "review-1",
                    "acme",
                    "e1",
                    "eng",
                    Lane.of(lane).orElseThrow(),
                    "codex",
                    List.of()));

    assertEquals("allowed", said(run(agent, "POST", "/v1/specs/e1/messages", "body=finding")));
    assertEquals("allowed", said(run(agent, "PUT", "/v1/specs/e1/content", "body=fixed")));
    assertEquals(DRAFTS_ONLY, said(run(agent, "PUT", "/v1/specs/e1", "status=done")));
    assertEquals(
        "403 forbidden_not_assignee '"
            + principal(agent)
            + "' may post only in its own work, not in 'r1'."
            + OUTSIDE_WORK,
        said(run(agent, "POST", "/v1/specs/r1/messages", "body=hi")));
  }

  /** A full room member of e1's room drafts the body of the spec whose room it is. */
  @Test
  void aFullRoomMemberDraftsTheBodyOfItsRoomsSpec() {
    var member = reserve("e1", null, Lane.ROOM_FULL.wire(), "claude-code");

    assertEquals("allowed", said(run(member, "PUT", "/v1/specs/e1/content", "body=draft")));
    assertEquals("allowed", said(run(member, "POST", "/v1/specs/e1/messages", "body=drafted")));
  }

  /**
   * An ad-hoc run's work is its project: it posts in acme's rooms, not beta's, and creates its
   * drafts in acme alone.
   */
  @Test
  void anAdHocRunPostsAndDraftsInItsProjectAndNowhereElse() {
    var adHoc = adHoc("claude-code");

    assertEquals("allowed", said(run(adHoc, "POST", "/v1/specs/r1/messages", "body=hi")));
    assertEquals(
        "403 forbidden_not_assignee '"
            + principal(adHoc)
            + "' may post only in its own work, not in 'b1'."
            + OUTSIDE_WORK,
        said(run(adHoc, "POST", "/v1/specs/b1/messages", "body=hi")));
    assertEquals(
        "403 forbidden_not_assignee '"
            + principal(adHoc)
            + "' may create only its own work, not spec 'b-new'."
            + OUTSIDE_WORK,
        said(run(adHoc, "POST", "/v1/specs", "id=b-new&project=beta&title=B")));
  }

  /** A room agent reads a spec in another project of its person as not there. */
  @Test
  void aRoomAgentFindsNoSpecOfItsPersonsOtherProjects() {
    var room = reserve(null, "r1", Lane.ROOM.wire(), "claude-code");

    assertEquals(404, run(room, "GET", "/v1/specs/b1", "").status());
    assertEquals(200, run(room, "GET", "/v1/specs/e1", "").status());
  }

  /** A build of e1 on eng's box. */
  private String build(String spec, String harness) {
    return reserve(spec, null, Lane.BUILD.wire(), harness);
  }

  /** An ad-hoc run on eng's box, in acme. */
  private String adHoc(String harness) {
    return reserve(null, null, Lane.ADHOC.wire(), harness);
  }

  /**
   * The credential of a run of {@code role} eng's box reserves, of {@code spec} in {@code room}.
   */
  private String reserve(String spec, String room, String role, String harness) {
    var id = DateTimeUtils.newId().toString();
    var reservation =
        Acting.system(
            () ->
                new RunStore(fleet.db())
                    .reserveDispatch(
                        id,
                        "acme",
                        spec,
                        room,
                        "eng",
                        role,
                        List.of(),
                        harness,
                        "feat/" + id,
                        "work",
                        "/tmp/" + id + ".log",
                        "sail-agent-" + id,
                        Duration.ofHours(1)));
    return ((RunStore.Reservation.Reserved) reservation).credential();
  }

  private String principal(String credential) {
    return runs().findByCredential(credential).orElseThrow().principal();
  }

  private RunStore runs() {
    return new RunStore(fleet.db());
  }

  /** What eng's box's socket answers run {@code credential}. */
  private ApiResponse run(String credential, String method, String path, String form) {
    return new SocketDoor(fleet.boxes().get("eng").operations())
        .ask(method, path, credential, form);
  }

  private ApiResponse socket(String person, String method, String path, String form) {
    return fleet.boxes().get(person).socket(method, path, form);
  }

  private static String said(ApiResponse response) {
    if (response.status() / 100 == 2) {
      return "allowed";
    }
    var error = (Map<?, ?>) response.body().get("error");
    return response.status()
        + " "
        + error.get("code")
        + " "
        + error.get("message")
        + " | "
        + error.get("action");
  }
}
