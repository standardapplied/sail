/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ConflictOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.ConflictMerge;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncEngine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocalApiRouterTest {

  private final RecordingOps ops = new RecordingOps();
  private final LocalApiRouter router = new LocalApiRouter(ops);

  private static Map<String, String> auth() {
    return Map.of("authorization", "Bearer " + TestOperations.RUN_CREDENTIAL);
  }

  private static Map<String, String> boxAuth() {
    return Map.of("authorization", "Bearer " + TestOperations.BOX_CREDENTIAL);
  }

  private static Map<String, String> roomAuth() {
    return Map.of("authorization", "Bearer " + TestOperations.ROOM_RUN_CREDENTIAL);
  }

  private static Map<String, String> inviteAuth() {
    return Map.of("authorization", "Bearer " + TestOperations.INVITE_RUN_CREDENTIAL);
  }

  private static LocalApiRequest get(String path, Map<String, String> query) {
    return new LocalApiRequest("GET", path, query, auth(), new byte[0]);
  }

  private static LocalApiRequest form(String method, String path, String body) {
    return new LocalApiRequest(
        method, path, Map.of(), auth(), body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void agentsCanReadSyncAndConflictsButCannotTriggerSync() {
    var operations =
        new TestOperations() {
          @Override
          public SyncStatus syncStatus() {
            return new SyncStatus(
                "node",
                "main",
                new SyncEngine.Report(1, 2, 3, 4),
                "in_sync",
                null,
                null,
                0,
                null,
                null,
                null);
          }

          @Override
          public List<SyncConflicts.Conflict> conflicts() {
            return List.of();
          }
        };
    var local = new LocalApiRouter(operations);
    assertEquals("main", local.handle(get("/v1/sync", Map.of())).body().get("main"));
    assertEquals(List.of(), local.handle(get("/v1/conflicts", Map.of())).body().get("conflicts"));
    assertEquals(405, local.handle(form("POST", "/v1/sync", "")).status());
    assertEquals(405, local.handle(form("POST", "/v1/conflicts", "")).status());
    assertEquals(400, local.handle(form("POST", "/v1/conflicts/spec/resolve", "")).status());
    assertEquals(
        404, local.handle(form("POST", "/v1/conflicts/resolve", "strategy=mine")).status());
    assertEquals(
        404, local.handle(form("POST", "/v1/conflicts//resolve", "strategy=mine")).status());
    assertEquals(
        401, local.handle(new LocalApiRequest("GET", "/v1/sync", Map.of(), new byte[0])).status());
    assertEquals(
        401,
        local.handle(new LocalApiRequest("GET", "/v1/conflicts", Map.of(), new byte[0])).status());
  }

  @Test
  void aResolveCarriesTheTypeAndKeepsTheEnginesRefusalStatus() {
    var asked = new ArrayList<String>();
    var operations =
        new TestOperations() {
          @Override
          public SyncConflicts.Conflict resolveConflict(
              String type, String id, Resolution resolution) {
            asked.add(type + ":" + id);
            throw new ApiException(
                type == null ? ErrorCode.BAD_REQUEST : ErrorCode.CONFLICT, "refused " + id);
          }
        };
    var local = new LocalApiRouter(operations);
    var body = "strategy=mine".getBytes(StandardCharsets.UTF_8);

    var stale =
        local.handle(
            new LocalApiRequest(
                "POST", "/v1/conflicts/auth/resolve", Map.of("type", "room"), auth(), body));
    var ambiguous =
        local.handle(
            new LocalApiRequest("POST", "/v1/conflicts/auth/resolve", Map.of(), auth(), body));

    assertEquals(List.of("room:auth", "null:auth"), asked);
    assertEquals(409, stale.status());
    assertEquals(400, ambiguous.status());
    assertTrue(stale.body().toString().contains("refused auth"), stale.body().toString());
  }

  @Test
  void aWriteTheJournalRefusedIsAnsweredInHttpsEnvelopeWithItsKindsCode() {
    var lane =
        new LocalApiRouter(
            new TestOperations() {
              @Override
              public SyncConflicts.Conflict resolveConflict(
                  String type, String id, Resolution resolution) {
                throw new WriteRefused(
                    new Refusal(Refusal.Kind.ADMIN_ONLY, "Only an admin may.", "Ask one."));
              }
            });

    var refused =
        lane.handle(
            new LocalApiRequest(
                "POST",
                "/v1/conflicts/auth/resolve",
                Map.of("type", "spec"),
                auth(),
                "strategy=mine".getBytes(StandardCharsets.UTF_8)));

    assertEquals(403, refused.status());
    assertEquals(
        Map.of(
            "code",
            "forbidden_admin_only",
            "message",
            "Only an admin may.",
            "action",
            "Ask one.",
            "field_errors",
            List.of()),
        refused.body().get("error"));
  }

  @Test
  void aMergeSettlesOnlyTheVersionOfTheConflictItWasMadeFrom() {
    try (var box = new SyncBox("node")) {
      Acting.as(
          TestOperations.OWNER, () -> box.specs.create(SyncBox.spec("auth", "local", "pending")));
      var local = box.specs.comparableSnapshot("auth");
      parkTitle(box, local, "remote");
      var conflicts = new ConflictOperations(box.db);
      var rev = box.specs.revOf("auth");
      var lane =
          new LocalApiRouter(
              new TestOperations() {
                @Override
                public String conflictMergeTemplate(String type, String id) {
                  return conflicts.mergeTemplate(type, id);
                }

                @Override
                public SyncConflicts.Conflict resolveConflict(
                    String type, String id, Resolution resolution) {
                  return conflicts.resolve(type, id, resolution);
                }
              });
      var started = template(lane);
      var posted =
          lane.handle(
              new LocalApiRequest(
                  "POST",
                  "/v1/conflicts/auth",
                  Map.of("type", "spec", "template", "true"),
                  auth(),
                  new byte[0]));
      assertEquals(405, posted.status(), posted.body().toString());

      var unnamed =
          lane.handle(merge(started.replaceAll("(?m)^" + ConflictMerge.CONFLICT + ": .*\n", "")));
      assertEquals(400, unnamed.status(), unnamed.body().toString());
      assertEquals(
          Map.of(
              "code",
              "bad_request",
              "message",
              "A merged record must start from 'sail conflicts show auth --template'.",
              "field_errors",
              List.of()),
          unnamed.body().get("error"),
          "a refusal thrown on the socket carries HTTP's envelope");

      parkTitle(box, local, "remote, revised");
      var parked = box.conflicts.pendingFor("spec", "auth").orElseThrow();
      var moved = lane.handle(merge(started));
      assertEquals(409, moved.status(), moved.body().toString());
      assertEquals(
          "'auth' was re-recorded after this merge was started. Start again from the fresh version.",
          assertInstanceOf(Map.class, moved.body().get("error")).get("message"));
      assertEquals(parked, box.conflicts.pendingFor("spec", "auth").orElseThrow());
      assertEquals(rev, box.specs.revOf("auth"));
      assertEquals(local, box.specs.comparableSnapshot("auth"));

      var fresh = lane.handle(merge(template(lane)));
      assertEquals(200, fresh.status(), fresh.body().toString());
      assertEquals("merged", box.specs.findById("auth").orElseThrow().title());
      assertTrue(box.conflicts.pending().isEmpty());
      var gone = lane.handle(get("/v1/conflicts/auth", Map.of("type", "spec", "template", "true")));
      assertEquals(404, gone.status(), gone.body().toString());
      assertEquals(404, lane.handle(merge(started)).status());
    }
  }

  private static String template(LocalApiRouter lane) {
    var response =
        lane.handle(get("/v1/conflicts/auth", Map.of("type", "spec", "template", "true")));
    assertEquals(200, response.status(), response.body().toString());
    return (String) response.body().get("template");
  }

  private static void parkTitle(SyncBox box, Map<String, Object> local, String theirs) {
    var remote = new LinkedHashMap<>(local);
    remote.put("title", theirs);
    var recorded = YamlUtil.dumpJson(local);
    box.conflicts.record(
        "spec",
        "auth",
        recorded,
        recorded,
        YamlUtil.dumpJson(remote),
        "9-main",
        "main",
        List.of("title"));
  }

  private static LocalApiRequest merge(String template) {
    assertTrue(template.contains("\ntitle: local\n"), template);
    var headers = new HashMap<>(auth());
    headers.put("content-type", "application/json");
    var body =
        YamlUtil.dumpJson(
            Map.of(
                "strategy",
                "merge",
                "merged",
                template.replace("\ntitle: local\n", "\ntitle: merged\n")));
    return new LocalApiRequest(
        "POST",
        "/v1/conflicts/auth/resolve",
        Map.of("type", "spec"),
        headers,
        body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void missingCredentialIs401OnEveryRoute() {
    for (var path : List.of("/v1/specs", "/v1/specs/board", "/v1/events", "/v1/whoami", "/v1/x")) {
      var response = router.handle(new LocalApiRequest("GET", path, Map.of(), new byte[0]));
      assertEquals(401, response.status(), path);
      assertTrue(response.body().get("error").toString().contains("SAIL_RUN_CREDENTIAL"), path);
      assertTrue(response.body().get("error").toString().contains("box.credential"), path);
    }
  }

  @Test
  void unknownCredentialIs401() {
    var response =
        router.handle(
            new LocalApiRequest(
                "GET",
                "/v1/specs",
                Map.of(),
                Map.of("authorization", "Bearer sailrun_wrong"),
                new byte[0]));
    assertEquals(401, response.status());
  }

  @Test
  void whoamiReflectsTheRunPrincipal() {
    var response = router.handle(get("/v1/whoami", Map.of()));
    assertEquals(200, response.status());
    assertEquals(TestOperations.PRINCIPAL, response.body().get("handle"));
    assertEquals(TestOperations.OWNER, response.body().get("owner"));
    assertEquals("member", response.body().get("role"));
    assertEquals("agent", response.body().get("lane"));
    assertEquals("run-1", response.body().get("run_id"));
    assertEquals("acme", response.body().get("project"));

    assertEquals(405, router.handle(form("POST", "/v1/whoami", "")).status());
  }

  @Test
  void postEventHandsTheDoorAnEventAboutTheAuthenticatedRunAndReturns202() {
    var event =
        Event.of(
            "light-grid",
            "oauth",
            Event.WellKnownTypes.AGENT_TOOL_FINISHED,
            "claude-code",
            "host-01",
            Map.of("run_id", "run-victim", "source", "watcher", "exit_code", 0, "reason", "done"));
    var response = router.handle(form("POST", "/v1/events", event.toJsonLine()));
    assertEquals(202, response.status());
    assertEquals(1L, response.body().get("id"));
    var offered = ops.lastEvent;
    assertEquals(
        TestOperations.PRINCIPAL,
        offered.agent(),
        "the event names the authenticated run's principal, not the client's agent");
    assertEquals("acme", offered.project(), "the client-chosen project is overridden");
    assertEquals("auth", offered.spec(), "the client-chosen spec is overridden");
    assertEquals(
        "run-1",
        offered.data().get("run_id"),
        "an event can only address the authenticated run, never another one");
    assertFalse(
        offered.data().containsKey("source"),
        "the agent lane can never mark its own stop authoritative");
    assertFalse(offered.data().containsKey("exit_code"), "exit codes come from the watcher only");
    assertEquals("done", offered.data().get("reason"), "benign payload fields pass through");
    assertEquals(
        TestOperations.PRINCIPAL,
        ops.lastBound.handle(),
        "the door is asked as the run's principal");
  }

  @Test
  void aRefusalFromTheEventDoorIsTheRoutesAnswer() {
    ops.eventRefusal =
        Result.failure(ErrorCode.FORBIDDEN, "Event type 'spec_dispatched' is not available.");
    var event = Event.of("acme", "auth", "spec_dispatched", "claude-code", "host-01");

    var response = router.handle(form("POST", "/v1/events", event.toJsonLine()));

    assertEquals(403, response.status());
    assertEquals("Event type 'spec_dispatched' is not available.", response.body().get("error"));
  }

  @Test
  void eventsRejectsWrongMethodAndMalformedBody() {
    assertEquals(405, router.handle(get("/v1/events", Map.of())).status());
    assertEquals(400, router.handle(form("POST", "/v1/events", "{not json}")).status());
  }

  @Test
  void listSpecsPassesEveryFilterThrough() {
    router.handle(
        get(
            "/v1/specs",
            Map.of(
                "project", "acme",
                "status", "pending",
                "assignee", "me",
                "repo", "app",
                "search", "oauth")));
    var f = ops.lastFilter;
    assertEquals("acme", f.project());
    assertEquals("pending", f.status());
    assertEquals("me", f.assignee());
    assertEquals("app", f.repo());
    assertEquals("oauth", f.search());
  }

  @Test
  void createSpecParsesFormFieldsCsvAndPriority() {
    var response =
        router.handle(
            form(
                "POST",
                "/v1/specs",
                "id=oauth-flow&title=OAuth%20Flow&status=pending&priority=5"
                    + "&depends_on=a,%20b%20,,c&repos=app,web&body=%23%20Goal"));
    assertEquals(201, response.status());
    var req = ops.lastCreate;
    assertEquals("oauth-flow", req.id());
    assertEquals("OAuth Flow", req.title());
    assertEquals("pending", req.status());
    assertEquals(5, req.priority());
    assertEquals(List.of("a", "b", "c"), req.dependsOn());
    assertEquals(List.of("app", "web"), req.repos());
    assertEquals("# Goal", req.body());
    assertEquals(TestOperations.PRINCIPAL, ops.lastBound.handle());
  }

  @Test
  void createSpecPassesTheHomeRoomThroughAndLeavesItNullWhenAbsent() {
    router.handle(form("POST", "/v1/specs", "id=born&title=Born&room_id=design-talk"));
    assertEquals("design-talk", ops.lastCreate.roomId(), "a brainstorm's spec names its room");

    router.handle(form("POST", "/v1/specs", "id=solo&title=Solo"));
    assertNull(ops.lastCreate.roomId(), "no room means the identity-room default downstream");
  }

  @Test
  void createSpecDefaultsStatusDraftAndStampsThePrincipal() {
    router.handle(form("POST", "/v1/specs", "id=x&title=X&actor=ada"));
    assertEquals("draft", ops.lastCreate.status());
    assertEquals(
        TestOperations.PRINCIPAL,
        ops.lastBound.handle(),
        "a client-sent actor field is ignored; authorship is the authenticated principal");
    assertEquals(0, ops.lastCreate.priority());
    assertEquals(List.of(), ops.lastCreate.dependsOn());
  }

  @Test
  void createSpecToleratesAnUnparseablePriority() {
    router.handle(form("POST", "/v1/specs", "id=x&title=X&priority=high"));
    assertEquals(0, ops.lastCreate.priority());
  }

  @Test
  void specsCollectionRejectsWrongMethod() {
    assertEquals(405, router.handle(form("DELETE", "/v1/specs", "")).status());
  }

  @Test
  void boardReadsProjectAndRejectsWrongMethod() {
    assertEquals(200, router.handle(get("/v1/specs/board", Map.of("project", "acme"))).status());
    assertEquals("acme", ops.lastBoardProject);
    assertEquals(405, router.handle(form("POST", "/v1/specs/board", "")).status());
  }

  @Test
  void showUpdateDeleteASpec() {
    assertEquals(200, router.handle(get("/v1/specs/oauth", Map.of())).status());
    assertEquals("oauth", ops.lastShownId);

    var updated =
        router.handle(form("PUT", "/v1/specs/oauth", "status=archived&depends_on=a&actor=ada"));
    assertEquals(200, updated.status());
    assertEquals("archived", ops.lastUpdate.status());
    assertEquals(List.of("a"), ops.lastUpdate.dependsOn());
    assertEquals(TestOperations.PRINCIPAL, ops.lastBound.handle());
    assertEquals(TestOperations.PRINCIPAL, ops.lastActor.handle());
    assertEquals(TestOperations.OWNER, ops.lastActor.owner());
    assertEquals(Role.MEMBER, ops.lastActor.role());
    assertTrue(ops.lastActor.agentLane());

    assertEquals(200, router.handle(form("DELETE", "/v1/specs/oauth", "")).status());
    assertEquals("oauth", ops.lastDeletedId);

    assertEquals(405, router.handle(form("PATCH", "/v1/specs/oauth", "")).status());
  }

  @Test
  void updateLeavesUnsetListsNull() {
    router.handle(form("PUT", "/v1/specs/oauth", "status=done"));
    assertNull(ops.lastUpdate.dependsOn());
    assertNull(ops.lastUpdate.priority());
  }

  @Test
  void getAndSetContent() {
    assertEquals(200, router.handle(get("/v1/specs/oauth/content", Map.of())).status());
    assertEquals("oauth", ops.lastContentId);

    var set = router.handle(form("PUT", "/v1/specs/oauth/content", "body=%23%20Body&plan=steps"));
    assertEquals(200, set.status());
    assertEquals("# Body", ops.lastContent.body());
    assertEquals("steps", ops.lastContent.plan());

    assertEquals(405, router.handle(form("DELETE", "/v1/specs/oauth/content", "")).status());
  }

  @Test
  void postAndListMessagesUseTheRunPrincipalAndClampPages() {
    var posted =
        router.handle(
            form(
                "POST",
                "/v1/specs/oauth/messages",
                "body=Progress%20update&reply_to=01900000-0000-7000-8000-000000000001"));
    assertEquals(201, posted.status());
    assertEquals("Progress update", ops.lastMessage.body());
    assertFalse(ops.lastMessage.question());
    assertEquals(TestOperations.PRINCIPAL, ops.lastMessageAuthor);
    assertEquals(TestOperations.PRINCIPAL, ops.lastActor.handle());
    assertEquals(TestOperations.OWNER, ops.lastActor.owner());
    assertTrue(ops.lastActor.agentLane());

    var asked =
        router.handle(
            form("POST", "/v1/specs/oauth/messages", "body=Which%20flow%3F&question=true"));
    assertEquals(201, asked.status());
    assertTrue(ops.lastMessage.question(), "the form flag reaches the request");

    assertEquals(
        200,
        router
            .handle(
                get(
                    "/v1/specs/oauth/messages",
                    Map.of("before", "01900000-0000-7000-8000-000000000001", "limit", "500")))
            .status());
    assertEquals(100, ops.lastMessageLimit);
    assertEquals("01900000-0000-7000-8000-000000000001", ops.lastBefore);

    router.handle(get("/v1/specs/oauth/messages", Map.of("limit", "0")));
    assertEquals(1, ops.lastMessageLimit);
    router.handle(get("/v1/specs/oauth/messages", Map.of()));
    assertEquals(50, ops.lastMessageLimit);
    assertEquals(
        400, router.handle(get("/v1/specs/oauth/messages", Map.of("limit", "bad"))).status());
    assertEquals(405, router.handle(form("DELETE", "/v1/specs/oauth/messages", "")).status());
  }

  @Test
  void afterFilterPassesThroughToTheDeliveryRead() {
    var response =
        router.handle(
            get(
                "/v1/specs/oauth/messages",
                Map.of("after", "01900000-0000-7000-8000-000000000001")));
    assertEquals(200, response.status());
    assertEquals("01900000-0000-7000-8000-000000000001", ops.lastAfter);
    assertNull(ops.lastBefore);
  }

  @Test
  void aHistoricalReadOnlyInviteCredentialStaysViewerTierAcrossTheUpgrade() {
    var whoami =
        router.handle(
            new LocalApiRequest("GET", "/v1/whoami", Map.of(), inviteAuth(), new byte[0]));
    assertEquals(200, whoami.status());
    assertEquals("viewer", whoami.body().get("role"), "the retired lane never gains write tier");
    assertEquals("run-3", whoami.body().get("run_id"));

    router.handle(
        new LocalApiRequest(
            "PUT",
            "/v1/specs/oauth",
            Map.of(),
            inviteAuth(),
            "status=archived".getBytes(StandardCharsets.UTF_8)));
    assertEquals(Role.VIEWER, ops.lastActor.role(), "viewer tier — the security property");
    assertEquals(
        Actor.Lane.ROOM,
        ops.lastActor.lane(),
        "the read-only run resolves to the room principal, never the write-capable agent principal");
  }

  @Test
  void aSpeclessRoomSessionPostsOnlyToItsOwnRoom() {
    var own =
        router.handle(
            new LocalApiRequest(
                "POST",
                "/v1/specs/lounge/messages",
                Map.of(),
                roomAuth(),
                "body=hi".getBytes(StandardCharsets.UTF_8)));
    assertEquals(201, own.status(), "a room-keyed run owns its own conversation");

    var foreign =
        router.handle(
            new LocalApiRequest(
                "POST",
                "/v1/specs/auth/messages",
                Map.of(),
                roomAuth(),
                "body=hi".getBytes(StandardCharsets.UTF_8)));
    assertEquals(403, foreign.status(), "another room is never this session's to post into");
  }

  @Test
  void aRunReadingItsOwnRoomAcknowledgesExactlyTheMessagesShown() {
    var response = router.handle(get("/v1/specs/auth/messages", Map.of()));

    assertEquals(200, response.status());
    assertEquals("run-1", ops.lastAckRunId, "reading the room is a delivery");
    assertEquals(
        List.of("01900000-0000-7000-8000-000000000001"),
        ops.lastDelivered,
        "the acknowledgement names exactly the page's messages, never more");

    ops.lastAckRunId = null;
    router.handle(
        get("/v1/specs/auth/messages", Map.of("before", "01900000-0000-7000-8000-000000000002")));
    assertEquals(
        "run-1",
        ops.lastAckRunId,
        "a paged self-read still delivers what it showed — identity acks make that safe");
  }

  @Test
  void foreignFailedOrEmptyReadsLeaveTheLedgerAlone() {
    router.handle(get("/v1/specs/oauth/messages", Map.of()));
    assertNull(ops.lastAckRunId, "another spec's room is not this run's delivery");

    router.handle(
        new LocalApiRequest("GET", "/v1/specs/auth/messages", Map.of(), boxAuth(), new byte[0]));
    assertNull(ops.lastAckRunId, "the box credential has no run to deliver to");

    ops.emptyMessages = true;
    router.handle(get("/v1/specs/auth/messages", Map.of()));
    assertNull(ops.lastAckRunId, "an empty page delivers nothing");

    ops.emptyMessages = false;
    ops.failMessages = true;
    router.handle(get("/v1/specs/auth/messages", Map.of()));
    assertNull(ops.lastAckRunId, "a failed read delivers nothing");
  }

  @Test
  void runMessagesServesTheInboxAndTheAcknowledgementToRunCallersOnly() {
    var inbox = router.handle(get("/v1/run/messages", Map.of()));
    assertEquals(200, inbox.status());
    assertEquals("run-1", inbox.body().get("run_id"));
    assertEquals(false, inbox.body().get("has_more"));
    assertEquals("run-1", ops.lastInboxRunId);

    var ack =
        router.handle(
            form(
                "POST",
                "/v1/run/messages",
                "delivered=01900000-0000-7000-8000-000000000002,"
                    + "01900000-0000-7000-8000-000000000003"));
    assertEquals(200, ack.status());
    assertEquals("run-1", ops.lastAckRunId, "the credential names the run, never the client");
    assertEquals(
        List.of("01900000-0000-7000-8000-000000000002", "01900000-0000-7000-8000-000000000003"),
        ops.lastDelivered,
        "a comma-separated ack carries every id shown");

    var boxed =
        router.handle(
            new LocalApiRequest("GET", "/v1/run/messages", Map.of(), boxAuth(), new byte[0]));
    assertEquals(403, boxed.status());
    assertTrue(boxed.body().get("error").toString().contains("run credential"));

    assertEquals(405, router.handle(form("DELETE", "/v1/run/messages", "")).status());

    var emptyAck = router.handle(form("POST", "/v1/run/messages", "delivered="));
    assertEquals(200, emptyAck.status());
    assertEquals(
        List.of(),
        ops.lastDelivered,
        "an ack naming no ids is a no-op, never an error — the relay may fire with nothing to"
            + " acknowledge");
  }

  @Test
  void runSessionRecordsTheHookReportedIdentityForRunCallersOnly() {
    var recorded =
        router.handle(
            form(
                "POST",
                "/v1/run/session",
                "session_id=abc-123&source=startup"
                    + "&transcript_path=%2Fhome%2Fdev%2F.claude%2Fp%2Fabc.jsonl"));
    assertEquals(200, recorded.status());
    assertEquals("run-1", recorded.body().get("run_id"));
    assertEquals("abc-123", recorded.body().get("session_id"));
    assertEquals("run-1", ops.lastSessionRunId, "the credential names the run, never the client");
    assertEquals("abc-123", ops.lastSessionId);
    assertEquals("startup", ops.lastSessionSource);
    assertEquals(
        "/home/dev/.claude/p/abc.jsonl",
        ops.lastTranscriptPath,
        "form values arrive urldecoded, so a path travels intact");

    var boxed =
        router.handle(
            new LocalApiRequest(
                "POST",
                "/v1/run/session",
                Map.of(),
                boxAuth(),
                "session_id=x".getBytes(StandardCharsets.UTF_8)));
    assertEquals(403, boxed.status());
    assertTrue(boxed.body().get("error").toString().contains("run credential"));

    assertEquals(405, router.handle(get("/v1/run/session", Map.of())).status());
    assertEquals(405, router.handle(form("DELETE", "/v1/run/session", "")).status());
  }

  @Test
  void aRoomBoundInteractiveSessionReportsItsConversationOverTheBoxLane() {
    var reported =
        router.handle(
            new LocalApiRequest(
                "POST",
                "/v1/run/session",
                Map.of(),
                boxAuth(),
                ("room_id=design-talk&agent=claude-code&session_id=abc-123&source=startup"
                        + "&transcript_path=%2Fhome%2Fdev%2F.claude%2Fp%2Fabc.jsonl")
                    .getBytes(StandardCharsets.UTF_8)));
    assertEquals(200, reported.status(), reported.body().toString());
    assertEquals("design-talk", reported.body().get("room_id"));
    assertEquals("abc-123", reported.body().get("session_id"));
    assertEquals("claude-code", reported.body().get("agent"));
    assertEquals("design-talk", ops.lastConversationRoom);
    assertEquals("claude-code", ops.lastConversationAgent);
    assertEquals("abc-123", ops.lastSessionId);
    assertEquals("startup", ops.lastSessionSource);
    assertEquals("/home/dev/.claude/p/abc.jsonl", ops.lastTranscriptPath);
    assertEquals(
        TestOperations.BOX_HANDLE,
        ops.lastActor.handle(),
        "the conversation is authored by the box FDE the credential stands for");
    assertEquals(Role.MEMBER, ops.lastActor.role(), "the actor's role travels for the room gate");
    assertNull(ops.lastSessionRunId, "no run row is touched by a box-lane report");

    var wrongMethod =
        router.handle(
            new LocalApiRequest(
                "GET",
                "/v1/run/session",
                Map.of("room_id", "design-talk"),
                boxAuth(),
                new byte[0]));
    assertEquals(403, wrongMethod.status(), "a GET carries no form, so no room: refused first");
  }

  @Test
  void aRunCallersReportIgnoresAnyRoomItClaims() {
    var recorded =
        router.handle(form("POST", "/v1/run/session", "session_id=abc&room_id=someone-elses"));
    assertEquals(200, recorded.status());
    assertEquals("run-1", ops.lastSessionRunId, "a run credential always reports onto its run");
    assertNull(ops.lastConversationRoom, "a run never mints a room conversation");
  }

  @Test
  void boxCredentialActsAsTheFdeOnSpecAndMessageRoutes() {
    var created =
        router.handle(
            new LocalApiRequest(
                "POST",
                "/v1/specs",
                Map.of(),
                boxAuth(),
                "id=room&title=Room".getBytes(StandardCharsets.UTF_8)));
    assertEquals(201, created.status());
    assertEquals(TestOperations.BOX_HANDLE, ops.lastBound.handle());
    assertEquals(
        TestOperations.BOX_HANDLE,
        ops.lastActor.handle(),
        "the create travels with the actor so a room binding is authorized");

    var posted =
        router.handle(
            new LocalApiRequest(
                "POST",
                "/v1/specs/oauth/messages",
                Map.of(),
                boxAuth(),
                "body=hello".getBytes(StandardCharsets.UTF_8)));
    assertEquals(201, posted.status());
    assertEquals(TestOperations.BOX_HANDLE, ops.lastMessageAuthor);
    assertEquals(TestOperations.BOX_HANDLE, ops.lastActor.handle());
    assertEquals(Role.MEMBER, ops.lastActor.role());
    assertFalse(ops.lastActor.agentLane());

    var updated =
        router.handle(
            new LocalApiRequest(
                "PUT",
                "/v1/specs/oauth",
                Map.of(),
                boxAuth(),
                "status=done".getBytes(StandardCharsets.UTF_8)));
    assertEquals(200, updated.status());
    assertEquals(TestOperations.BOX_HANDLE, ops.lastBound.handle());
  }

  @Test
  void boxCredentialCannotPostEvents() {
    var response =
        router.handle(
            new LocalApiRequest(
                "POST",
                "/v1/events",
                Map.of(),
                boxAuth(),
                "{\"v\":1,\"ts\":\"t\",\"type\":\"agent_tool_started\"}"
                    .getBytes(StandardCharsets.UTF_8)));

    assertEquals(403, response.status());
    assertTrue(response.body().get("error").toString().contains("run credential"));
  }

  @Test
  void whoamiReflectsTheBoxFde() {
    var response =
        router.handle(new LocalApiRequest("GET", "/v1/whoami", Map.of(), boxAuth(), new byte[0]));

    assertEquals(200, response.status());
    assertEquals(TestOperations.BOX_HANDLE, response.body().get("handle"));
    assertEquals("member", response.body().get("role"));
    assertEquals("cli", response.body().get("lane"));
    assertEquals("box", response.body().get("credential"));
    assertFalse(response.body().containsKey("run_id"));
  }

  @Test
  void boxResolutionNeverShadowsARunCredential() {
    var greedy =
        new TestOperations() {
          @Override
          public Optional<Actor> boxActorForCredential(String credential) {
            return Optional.of(new Actor("impostor", Role.ADMIN, Actor.Lane.CLI));
          }
        };
    var response = new LocalApiRouter(greedy).handle(get("/v1/whoami", Map.of()));

    assertEquals(TestOperations.PRINCIPAL, response.body().get("handle"));
    assertEquals("agent", response.body().get("lane"));
  }

  @Test
  void unknownRouteAndUnknownSubResourceAre404() {
    assertEquals(404, router.handle(get("/v1/widgets", Map.of())).status());
    assertEquals(404, router.handle(get("/v1/specs/oauth/history", Map.of())).status());
  }

  @Test
  void operationExceptionsBecome400And500() {
    assertEquals(400, router.handle(get("/v1/specs/bad", Map.of())).status());
    assertEquals(500, router.handle(get("/v1/specs/boom", Map.of())).status());
    assertNotNull(router.handle(get("/v1/specs/boom", Map.of())).body().get("error"));
  }

  private static final class RecordingOps extends TestOperations {
    private SpecStore.SpecFilter lastFilter;
    private SpecCreateRequest lastCreate;
    private SpecUpdateRequest lastUpdate;
    private SpecContentRequest lastContent;
    private Actor lastActor;
    private Actor lastBound;
    private String lastBoardProject;
    private String lastShownId;
    private String lastDeletedId;
    private String lastContentId;
    private SpecMessageRequest lastMessage;
    private String lastMessageAuthor;
    private String lastBefore;
    private String lastAfter;
    private int lastMessageLimit;
    private String lastAckRunId;
    private List<String> lastDelivered;
    private String lastInboxRunId;
    private String lastSessionRunId;
    private String lastSessionId;
    private String lastSessionSource;
    private String lastTranscriptPath;
    private String lastConversationRoom;
    private String lastConversationAgent;
    private boolean emptyMessages;
    private boolean failMessages;
    private Event lastEvent;
    private Result<EventPublishResponse> eventRefusal;

    @Override
    public Result<EventPublishResponse> publishEvent(Event event) {
      lastEvent = event;
      lastBound = Actor.current();
      return eventRefusal != null ? eventRefusal : super.publishEvent(event);
    }

    @Override
    public Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter) {
      lastFilter = filter;
      return super.globalSpecs(filter);
    }

    @Override
    public Result<GlobalSpecCreatedResponse> createGlobalSpec(SpecCreateRequest request) {

      var actor = Actor.current();
      lastCreate = request;
      lastActor = actor;
      lastBound = Actor.current();
      return Actor.call(actor, () -> super.createGlobalSpec(request));
    }

    @Override
    public Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
        String specId, SpecUpdateRequest request) {

      var actor = Actor.current();
      lastUpdate = request;
      lastActor = actor;
      lastBound = Actor.current();
      return Actor.call(actor, () -> super.updateGlobalSpec(specId, request));
    }

    @Override
    public Result<GlobalSpecDetailResponse> globalSpec(String specId) {
      if ("bad".equals(specId)) {
        throw new IllegalArgumentException("bad id");
      }
      if ("boom".equals(specId)) {
        throw new IllegalStateException("kaboom");
      }
      lastShownId = specId;
      return super.globalSpec(specId);
    }

    @Override
    public Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId) {

      var actor = Actor.current();
      lastDeletedId = specId;
      return Actor.call(actor, () -> super.deleteGlobalSpec(specId));
    }

    @Override
    public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
      lastContentId = specId;
      return super.globalSpecContent(specId);
    }

    @Override
    public Result<GlobalSpecContentResponse> setGlobalSpecContent(
        String specId, SpecContentRequest request) {

      var actor = Actor.current();
      lastContent = request;
      lastActor = actor;
      return Actor.call(actor, () -> super.setGlobalSpecContent(specId, request));
    }

    @Override
    public Result<GlobalBoardResponse> globalBoard(String project) {
      lastBoardProject = project;
      return super.globalBoard(project);
    }

    @Override
    public Result<SpecMessageResponse> postRoomMessage(
        String specId, SpecMessageRequest request, String author) {

      var actor = Actor.current();
      lastMessage = request;
      lastMessageAuthor = author;
      lastActor = actor;
      return Actor.call(actor, () -> super.postRoomMessage(specId, request, author));
    }

    @Override
    public Result<SpecMessagesResponse> roomMessages(
        String specId, String before, String after, int limit) {
      lastBefore = before;
      lastAfter = after;
      lastMessageLimit = limit;
      if (failMessages) {
        return Result.failure(ErrorCode.INTERNAL, "boom");
      }
      if (emptyMessages) {
        return Result.success(new SpecMessagesResponse(specId, List.of()));
      }
      return super.roomMessages(specId, before, after, limit);
    }

    @Override
    public Result<RunInboxResponse> runInbox(String runId) {
      lastInboxRunId = runId;
      return super.runInbox(runId);
    }

    @Override
    public Result<RunAckResponse> ackRunMessages(String runId, List<String> delivered) {
      lastAckRunId = runId;
      lastDelivered = delivered;
      return super.ackRunMessages(runId, delivered);
    }

    @Override
    public Result<RunSessionResponse> recordRunSession(
        String runId, String sessionId, String source, String transcriptPath) {
      lastSessionRunId = runId;
      lastSessionId = sessionId;
      lastSessionSource = source;
      lastTranscriptPath = transcriptPath;
      return super.recordRunSession(runId, sessionId, source, transcriptPath);
    }

    @Override
    public Result<RoomConversationResponse> recordRoomConversation(
        String roomId, String agent, String sessionId, String source, String transcriptPath) {

      var actor = Actor.current();
      lastConversationRoom = roomId;
      lastConversationAgent = agent;
      lastSessionId = sessionId;
      lastSessionSource = source;
      lastTranscriptPath = transcriptPath;
      lastActor = actor;
      return Actor.call(
          actor,
          () -> super.recordRoomConversation(roomId, agent, sessionId, source, transcriptPath));
    }
  }
}
