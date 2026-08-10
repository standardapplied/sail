/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.SpecStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class LocalApiRouterTest {

  private final EventBus bus = new EventBus();
  private final RecordingOps ops = new RecordingOps();
  private final LocalApiRouter router = new LocalApiRouter(bus, ops);

  private static Map<String, String> auth() {
    return Map.of("authorization", "Bearer " + TestOperations.RUN_CREDENTIAL);
  }

  private static Map<String, String> boxAuth() {
    return Map.of("authorization", "Bearer " + TestOperations.BOX_CREDENTIAL);
  }

  private static LocalApiRequest get(String path, Map<String, String> query) {
    return new LocalApiRequest("GET", path, query, auth(), new byte[0]);
  }

  private static LocalApiRequest form(String method, String path, String body) {
    return new LocalApiRequest(
        method, path, Map.of(), auth(), body.getBytes(StandardCharsets.UTF_8));
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
  void postEventPublishesStampedWithThePrincipalAndReturns202() throws Exception {
    var seen = new AtomicReference<Event>();
    var latch = new CountDownLatch(1);
    var subscription =
        bus.subscribe(
            BusTesting.latching(
                new EventSubscriber() {
                  @Override
                  public String name() {
                    return "capture";
                  }

                  @Override
                  public Predicate<Event> filter() {
                    return e -> true;
                  }

                  @Override
                  public void onEvent(Event event) {
                    seen.set(event);
                  }
                },
                latch));
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
    assertTrue(((Long) response.body().get("id")) > 0);
    BusTesting.awaitDelivery(latch);
    var stamped = seen.get();
    assertEquals(
        TestOperations.PRINCIPAL,
        stamped.agent(),
        "the server stamps event authorship from the authenticated run, not the client body");
    assertEquals("acme", stamped.project(), "the client-chosen project is overridden");
    assertEquals("auth", stamped.spec(), "the client-chosen spec is overridden");
    assertEquals(
        "run-1",
        stamped.data().get("run_id"),
        "an event can only address the authenticated run, never another one");
    assertFalse(
        stamped.data().containsKey("source"),
        "the agent lane can never mark its own stop authoritative");
    assertFalse(stamped.data().containsKey("exit_code"), "exit codes come from the watcher only");
    assertEquals("done", stamped.data().get("reason"), "benign payload fields pass through");
    subscription.close();
  }

  @Test
  void eventsRejectsTypesOutsideTheAgentLane() {
    for (var type : List.of("spec_dispatched", "agent_cancelled", "spec_status_changed")) {
      var event = Event.of("acme", "auth", type, "claude-code", "host-01");
      var response = router.handle(form("POST", "/v1/events", event.toJsonLine()));
      assertEquals(403, response.status(), type);
      assertTrue(response.body().get("error").toString().contains(type), type);
    }
  }

  @Test
  void eventsRejectsTerminalSessionTypesSoARunCannotFinishItself() {
    for (var type :
        List.of(
            Event.WellKnownTypes.AGENT_SESSION_STOPPED,
            Event.WellKnownTypes.AGENT_SESSION_COMPLETED)) {
      var event =
          Event.of("acme", "auth", type, "claude-code", "host-01", Map.of("run_id", "run-1"));
      var response = router.handle(form("POST", "/v1/events", event.toJsonLine()));
      assertEquals(
          403,
          response.status(),
          type + " must come from the watcher's verified exit, never the live agent");
    }
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
    assertEquals(TestOperations.PRINCIPAL, req.createdBy());
  }

  @Test
  void createSpecDefaultsStatusDraftAndStampsThePrincipal() {
    router.handle(form("POST", "/v1/specs", "id=x&title=X&actor=ada"));
    assertEquals("draft", ops.lastCreate.status());
    assertEquals(
        TestOperations.PRINCIPAL,
        ops.lastCreate.createdBy(),
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
    assertEquals(TestOperations.PRINCIPAL, ops.lastUpdate.updatedBy());
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
    assertEquals(TestOperations.PRINCIPAL, ops.lastMessageAuthor);
    assertEquals(TestOperations.PRINCIPAL, ops.lastActor.handle());
    assertEquals(TestOperations.OWNER, ops.lastActor.owner());
    assertTrue(ops.lastActor.agentLane());

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
    assertEquals(TestOperations.BOX_HANDLE, ops.lastCreate.createdBy());

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
    assertEquals(TestOperations.BOX_HANDLE, ops.lastUpdate.updatedBy());
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
    var response = new LocalApiRouter(bus, greedy).handle(get("/v1/whoami", Map.of()));

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
    private boolean emptyMessages;
    private boolean failMessages;

    @Override
    public Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter) {
      lastFilter = filter;
      return super.globalSpecs(filter);
    }

    @Override
    public Result<GlobalSpecCreatedResponse> createGlobalSpec(SpecCreateRequest request) {
      lastCreate = request;
      return super.createGlobalSpec(request);
    }

    @Override
    public Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
        String specId, SpecUpdateRequest request, Actor actor) {
      lastUpdate = request;
      lastActor = actor;
      return super.updateGlobalSpec(specId, request, actor);
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
    public Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId, Actor actor) {
      lastDeletedId = specId;
      return super.deleteGlobalSpec(specId, actor);
    }

    @Override
    public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
      lastContentId = specId;
      return super.globalSpecContent(specId);
    }

    @Override
    public Result<GlobalSpecContentResponse> setGlobalSpecContent(
        String specId, SpecContentRequest request, Actor actor) {
      lastContent = request;
      lastActor = actor;
      return super.setGlobalSpecContent(specId, request, actor);
    }

    @Override
    public Result<GlobalBoardResponse> globalBoard(String project) {
      lastBoardProject = project;
      return super.globalBoard(project);
    }

    @Override
    public Result<SpecMessageResponse> postSpecMessage(
        String specId, SpecMessageRequest request, Actor actor, String author) {
      lastMessage = request;
      lastMessageAuthor = author;
      lastActor = actor;
      return super.postSpecMessage(specId, request, actor, author);
    }

    @Override
    public Result<SpecMessagesResponse> specMessages(
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
      return super.specMessages(specId, before, after, limit);
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
  }
}
