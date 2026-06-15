/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.SpecStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalApiRouterTest {

  private final EventBus bus = new EventBus();
  private final RecordingOps ops = new RecordingOps();
  private final LocalApiRouter router = new LocalApiRouter(bus, ops);

  private static LocalApiRequest get(String path, Map<String, String> query) {
    return new LocalApiRequest("GET", path, query, new byte[0]);
  }

  private static LocalApiRequest form(String method, String path, String body) {
    return new LocalApiRequest(method, path, Map.of(), body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void postEventPublishesAndReturns202() {
    var event = Event.of("light-grid", "oauth", "spec_dispatched", "sail", "host-01");
    var response =
        router.handle(
            new LocalApiRequest(
                "POST",
                "/v1/events",
                Map.of(),
                event.toJsonLine().getBytes(StandardCharsets.UTF_8)));
    assertEquals(202, response.status());
    assertTrue(((Long) response.body().get("id")) > 0);
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
  void createSpecParsesFormFieldsCsvPriorityAndActor() {
    var response =
        router.handle(
            form(
                "POST",
                "/v1/specs",
                "id=oauth-flow&title=OAuth%20Flow&status=pending&priority=5"
                    + "&depends_on=a,%20b%20,,c&repos=app,web&body=%23%20Goal&actor=ada"));
    assertEquals(201, response.status());
    var req = ops.lastCreate;
    assertEquals("oauth-flow", req.id());
    assertEquals("OAuth Flow", req.title());
    assertEquals("pending", req.status());
    assertEquals(5, req.priority());
    assertEquals(List.of("a", "b", "c"), req.dependsOn());
    assertEquals(List.of("app", "web"), req.repos());
    assertEquals("# Goal", req.body());
    assertEquals("ada", req.createdBy());
  }

  @Test
  void createSpecDefaultsStatusDraftAndActorAgent() {
    router.handle(form("POST", "/v1/specs", "id=x&title=X"));
    assertEquals("draft", ops.lastCreate.status());
    assertEquals("agent", ops.lastCreate.createdBy());
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
    assertEquals("ada", ops.lastUpdate.updatedBy());

    assertEquals(200, router.handle(form("DELETE", "/v1/specs/oauth", "")).status());
    assertEquals("oauth", ops.lastDeletedId);

    assertEquals(405, router.handle(form("PATCH", "/v1/specs/oauth", "")).status());
  }

  @Test
  void updateLeavesUnsetListsNull() {
    router.handle(form("PUT", "/v1/specs/oauth", "status=done"));
    org.junit.jupiter.api.Assertions.assertNull(ops.lastUpdate.dependsOn());
    org.junit.jupiter.api.Assertions.assertNull(ops.lastUpdate.priority());
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
        String specId, SpecUpdateRequest request) {
      lastUpdate = request;
      return super.updateGlobalSpec(specId, request);
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
      lastDeletedId = specId;
      return super.deleteGlobalSpec(specId);
    }

    @Override
    public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
      lastContentId = specId;
      return super.globalSpecContent(specId);
    }

    @Override
    public Result<GlobalSpecContentResponse> setGlobalSpecContent(
        String specId, SpecContentRequest request) {
      lastContent = request;
      return super.setGlobalSpecContent(specId, request);
    }

    @Override
    public Result<GlobalBoardResponse> globalBoard(String project) {
      lastBoardProject = project;
      return super.globalBoard(project);
    }
  }
}
