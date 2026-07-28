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
      assertTrue(response.body().get("error").toString().contains("run credential"), path);
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
            Event.WellKnownTypes.AGENT_SESSION_COMPLETED,
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
        "a terminal event can only address the authenticated run, never another one");
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
  }
}
