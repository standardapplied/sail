/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiRouterTest {

  @Test
  void healthDoesNotRequireAuth() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/health", null);

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"status\": \"ok\""));
      assertTrue(response.body().contains("\"schema_version\": 1"));
    }
  }

  @Test
  void protectedRoutesRequireBearerToken() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent", null);

      assertEquals(401, response.statusCode());
      assertTrue(response.body().contains("missing_bearer_token"));
    }
  }

  @Test
  void malformedBearerHeaderIsRejected() throws Exception {
    try (var server = server()) {
      var request =
          HttpRequest.newBuilder(uri(server, "/v1/projects/acme/agent"))
              .header("Authorization", "Token token")
              .GET()
              .build();

      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(401, response.statusCode());
      assertTrue(response.body().contains("missing_bearer_token"));
    }
  }

  @Test
  void duplicateBearerHeadersAreRejected() throws Exception {
    try (var server = server()) {
      var request =
          HttpRequest.newBuilder(uri(server, "/v1/projects/acme/agent"))
              .header("Authorization", "Bearer token")
              .header("Authorization", "Bearer token")
              .GET()
              .build();

      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(403, response.statusCode());
      assertTrue(response.body().contains("invalid_bearer_token"));
    }
  }

  @Test
  void protectedRoutesRejectWrongBearerToken() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent", "wrong");

      assertEquals(403, response.statusCode());
      assertTrue(response.body().contains("invalid_bearer_token"));
    }
  }

  @Test
  void dispatchParsesJsonBody() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/projects/acme/dispatch", "token", "{\"spec_id\": \"auth\"}");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"auth\""));
      assertTrue(response.body().contains("\"name\": \"acme\""));
    }
  }

  @Test
  void dispatchParsesSingleRepoTarget() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/projects/acme/dispatch",
              "token",
              "{\"spec_id\": \"auth\", \"repo\": \"chorus\"}");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"repos\": [\"chorus\"]"));
    }
  }

  @Test
  void dispatchParsesMultipleRepoTargets() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/projects/acme/dispatch",
              "token",
              "{\"spec_id\": \"auth\", \"repos\": [\"sing\", \"chorus\"]}");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"repos\": [\"sing\", \"chorus\"]"));
    }
  }

  @Test
  void dispatchParsesScalarReposTarget() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/projects/acme/dispatch",
              "token",
              "{\"spec_id\": \"auth\", \"repos\": \"chorus\"}");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"repos\": [\"chorus\"]"));
    }
  }

  @Test
  void dispatchRejectsRepoAndReposTogether() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/projects/acme/dispatch",
              "token",
              "{\"spec_id\": \"auth\", \"repo\": \"sing\", \"repos\": [\"chorus\"]}");

      assertEquals(400, response.statusCode());
      assertTrue(response.body().contains("invalid_json"));
    }
  }

  @Test
  void methodMismatchReturnsMethodNotAllowed() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/dispatch", "token");

      assertEquals(405, response.statusCode());
      assertTrue(response.body().contains("method_not_allowed"));
    }
  }

  @Test
  void unknownRouteReturnsNotFound() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/unknown", "token");

      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("not_found"));
    }
  }

  @Test
  void invalidProjectNameReturnsUnprocessableEntity() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/Bad_Name/agent", "token");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("invalid_request"));
    }
  }

  @Test
  void invalidSpecIdReturnsUnprocessableEntity() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/specs/Bad_Name", "token");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("invalid_request"));
    }
  }

  @Test
  void invalidJsonReturnsBadRequest() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/projects/acme/dispatch", "token", "{");

      assertEquals(400, response.statusCode());
      assertTrue(response.body().contains("invalid_json"));
    }
  }

  @Test
  void emptyJsonBodyDefaultsToEmptyRequest() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/projects/acme/dispatch", "token", "");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("schema_version"));
    }
  }

  @Test
  void unsupportedContentTypeReturnsUnsupportedMediaType() throws Exception {
    try (var server = server()) {
      var request =
          HttpRequest.newBuilder(uri(server, "/v1/projects/acme/dispatch"))
              .header("Authorization", "Bearer token")
              .header("Content-Type", "text/plain")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();

      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(415, response.statusCode());
      assertTrue(response.body().contains("unsupported_media_type"));
    }
  }

  @Test
  void oversizedBodyReturnsPayloadTooLarge() throws Exception {
    try (var server = server()) {
      var body = "{\"value\":\"" + "x".repeat(70_000) + "\"}";
      var response = post(server, "/v1/projects/acme/dispatch", "token", body);

      assertEquals(413, response.statusCode());
      assertTrue(response.body().contains("request_too_large"));
    }
  }

  @Test
  void invalidTailReturnsUnprocessableEntity() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent/log?tail=0", "token");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("invalid_tail"));
    }
  }

  @Test
  void validTailIsPassedToOperations() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent/log?tail=42", "token");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("42"));
    }
  }

  @Test
  void invalidTailTextReturnsUnprocessableEntity() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent/log?tail=abc", "token");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("invalid_tail"));
    }
  }

  @Test
  void missingTailValueReturnsUnprocessableEntity() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent/log?tail", "token");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("invalid_tail"));
    }
  }

  @Test
  void urlDecodedTailIsPassedToOperations() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent/log?tail=4%32", "token");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("42"));
    }
  }

  @Test
  void missingTailQueryValueUsesDefault() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent/log?foo=bar", "token");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("200"));
    }
  }

  @Test
  void shortUnknownRoutesReturnNotFound() throws Exception {
    try (var server = server()) {
      assertEquals(404, get(server, "/", "token").statusCode());
      assertEquals(404, get(server, "/v1", "token").statusCode());
      assertEquals(404, get(server, "/bad/projects/acme", "token").statusCode());
      assertEquals(404, get(server, "/v1/project/acme", "token").statusCode());
    }
  }

  @Test
  void knownResourcesWithWrongMethodsReturnMethodNotAllowed() throws Exception {
    try (var server = server()) {
      assertEquals(405, post(server, "/v1/projects/acme", "token", "{}").statusCode());
      assertEquals(405, post(server, "/v1/projects/acme/specs", "token", "{}").statusCode());
      assertEquals(405, get(server, "/v1/projects/acme/dispatch", "token").statusCode());
      assertEquals(405, post(server, "/v1/projects/acme/agent", "token", "{}").statusCode());
      assertEquals(405, post(server, "/v1/projects/acme/agent/log", "token", "{}").statusCode());
      assertEquals(405, get(server, "/v1/projects/acme/agent/stop", "token").statusCode());
      assertEquals(405, get(server, "/v1/projects/acme/agent/report", "token").statusCode());
    }
  }

  @Test
  void malformedKnownResourcesReturnMethodNotAllowed() throws Exception {
    try (var server = server()) {
      assertEquals(405, get(server, "/v1/projects/acme/specs/auth/extra", "token").statusCode());
      assertEquals(405, get(server, "/v1/projects/acme/dispatch/extra", "token").statusCode());
      assertEquals(405, get(server, "/v1/projects/acme/agent/log/extra", "token").statusCode());
    }
  }

  @Test
  void unknownAgentSubResourcesReturnNotFound() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent/unknown", "token");

      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("not_found"));
    }
  }

  @Test
  void routesAgentAndSpecEndpoints() throws Exception {
    try (var server = server()) {
      assertEquals(200, get(server, "/v1/projects/acme", "token").statusCode());
      assertEquals(200, get(server, "/v1/projects/acme/specs", "token").statusCode());
      assertEquals(200, get(server, "/v1/projects/acme/specs/auth", "token").statusCode());
      assertEquals(200, get(server, "/v1/projects/acme/agent", "token").statusCode());
      assertEquals(200, get(server, "/v1/projects/acme/agent/log", "token").statusCode());
      assertEquals(200, post(server, "/v1/projects/acme/agent/stop", "token", "{}").statusCode());
      assertEquals(200, post(server, "/v1/projects/acme/agent/report", "token", "{}").statusCode());
    }
  }

  @Test
  void operationExceptionsBecomeStructuredErrors() throws Exception {
    try (var server = serverWith(new FailingOperations())) {
      server.start();

      var response = get(server, "/v1/projects/acme/agent", "token");

      assertEquals(409, response.statusCode());
      assertTrue(response.body().contains("conflict"));
    }
  }

  @Test
  void unexpectedOperationExceptionsBecomeInternalErrors() throws Exception {
    try (var server = serverWith(new ExplodingOperations())) {
      server.start();

      var response = get(server, "/v1/projects/acme/agent", "token");

      assertEquals(500, response.statusCode());
      assertTrue(response.body().contains("internal"));
    }
  }

  @Test
  void responseHelpersAddSchema() {
    assertEquals(201, ApiResponse.created(Map.of("created", true)).status());
    assertFalse(
        ApiJson.withSchema(new ApiError("code", "message", "")).toString().contains("action"));
    assertTrue(
        ApiJson.withSchema(new ApiError("code", "message", "fix")).toString().contains("action"));
    assertThrows(NullPointerException.class, () -> new TokenAuth(null));
  }

  @Test
  void publishEventReturnsStampedId() throws Exception {
    try (var server = server()) {
      var body =
          "{\"v\":1,\"ts\":\"2026-05-21T12:34:56Z\",\"project\":\"light-grid\","
              + "\"type\":\"spec_dispatched\",\"agent\":\"sail\",\"host\":\"host-01\"}";
      var response = post(server, "/v1/events", "token", body);
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"id\": 1"));
      assertTrue(response.body().contains("\"event\""));
    }
  }

  @Test
  void publishEventRejectsBadJsonBody() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/events", "token", "{not even json}");
      assertEquals(400, response.statusCode());
    }
  }

  @Test
  void publishEventRejectsMissingRequiredField() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/events", "token", "{\"v\":1}");
      assertEquals(400, response.statusCode());
    }
  }

  @Test
  void publishEventRejectsGet() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events", "token");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void recentEventsReturnsArray() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/recent", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"events\""));
      assertTrue(response.body().contains("\"limit\": 100"));
    }
  }

  @Test
  void recentEventsHonorsLimitQueryParam() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/recent?limit=42", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"limit\": 42"));
    }
  }

  @Test
  void recentEventsRejectsInvalidLimit() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/recent?limit=99999", "token");
      assertEquals(422, response.statusCode());
    }
  }

  @Test
  void recentEventsRejectsNonNumericLimit() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/recent?limit=abc", "token");
      assertEquals(422, response.statusCode());
    }
  }

  @Test
  void eventBusStatsReturnsCounts() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/stats", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"published\""));
      assertTrue(response.body().contains("\"subscribers\""));
    }
  }

  @Test
  void unknownEventsPathReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/bogus", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void deeplyNestedEventsPathReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/recent/extra", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void recentEventsRejectsPost() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/events/recent", "token", "{}");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void eventBusStatsRejectsPost() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/events/stats", "token", "{}");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void eventsEndpointsRequireAuth() throws Exception {
    try (var server = server()) {
      assertEquals(401, get(server, "/v1/events/recent", null).statusCode());
      assertEquals(401, get(server, "/v1/events/stats", null).statusCode());
    }
  }

  @Test
  void eventModelsCoverConstruction() {
    var pub = new EventPublishResponse(7L, Map.of("k", "v"));
    var recent = new RecentEventsResponse(10, 2, java.util.List.of(Map.of("a", 1)));
    var stat = new SubscriberStatsView("audit", 1024, 0, 0L);
    var stats = new EventBusStatsResponse(5L, 1L, java.util.List.of(stat));
    assertEquals(7L, pub.id());
    assertEquals(2, recent.returned());
    assertEquals(1L, stats.rejectedSubscribers());
    assertEquals("audit", stats.subscribers().getFirst().name());
  }

  @Test
  void reviewModelsCoverConstruction() {
    var stageRow =
        new ai.singlr.sail.store.ReviewStore.StageRow(
            "s1", "r1", "security", "agent", "passed", "codex", "t1", "t2", null);
    var stageView = StageView.from(stageRow, 3);
    assertEquals("security", stageView.name());
    assertEquals(3, stageView.findingCount());
    var map = stageView.toMap();
    assertEquals("codex", map.get("reviewer"));

    var reviewRow =
        new ai.singlr.sail.store.ReviewStore.ReviewRow(
            "r1", "auth", 1, "passed", "t0", "t1", null, null, null);
    var reviewView = ReviewView.from(reviewRow, java.util.List.of(stageView));
    assertEquals(1, reviewView.iteration());
    var rmap = reviewView.toMap();
    assertEquals("r1", rmap.get("id"));

    var list = new ReviewListResponse("auth", java.util.List.of(reviewView));
    assertEquals("auth", list.toMap().get("spec_id"));

    var detail = new ReviewDetailResponse(reviewView, java.util.List.of());
    assertNotNull(detail.toMap().get("review"));

    var approve = new ReviewApproveResponse("r1", true);
    assertEquals(true, approve.toMap().get("approved"));

    var dismiss = new FindingDismissResponse("f1", true);
    assertEquals(true, dismiss.toMap().get("dismissed"));
  }

  @Test
  void globalSpecsListReturnsEmptyArray() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"specs\": []"));
      assertTrue(response.body().contains("\"total\": 0"));
    }
  }

  @Test
  void globalSpecsListAcceptsFilterParams() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs?status=pending&assignee=uday&q=auth", "token");
      assertEquals(200, response.statusCode());
    }
  }

  @Test
  void globalSpecCreateReturns201() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/specs",
              "token",
              "{\"id\": \"auth\", \"title\": \"OAuth flow\", \"status\": \"draft\"}");
      assertEquals(201, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"auth\""));
      assertTrue(response.body().contains("\"title\": \"OAuth flow\""));
    }
  }

  @Test
  void globalSpecGetReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"auth-flow\""));
    }
  }

  @Test
  void globalSpecUpdateReturns200() throws Exception {
    try (var server = server()) {
      var response = put(server, "/v1/specs/auth-flow", "token", "{\"title\": \"Updated\"}");
      assertEquals(200, response.statusCode());
    }
  }

  @Test
  void globalSpecDeleteReturns200() throws Exception {
    try (var server = server()) {
      var response = delete(server, "/v1/specs/auth-flow", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"deleted\": true"));
    }
  }

  @Test
  void globalSpecContentGetReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/content", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"spec_id\": \"auth-flow\""));
    }
  }

  @Test
  void globalSpecContentSetReturns200() throws Exception {
    try (var server = server()) {
      var response =
          put(
              server,
              "/v1/specs/auth-flow/content",
              "token",
              "{\"body\": \"# Spec\", \"plan\": \"## Plan\"}");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"body\": \"# Spec\""));
    }
  }

  @Test
  void globalSpecBoardReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/board", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"pending\": 0"));
    }
  }

  @Test
  void globalSpecHistoryGetReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/history", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"spec_id\": \"auth-flow\""));
      assertTrue(response.body().contains("\"rev\": \"1-abc\""));
      assertTrue(response.body().contains("\"total\": 1"));
    }
  }

  @Test
  void globalSpecHistoryRejectsNonGet() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/specs/auth-flow/history", "token", "{}");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void globalSpecRestorePostReturns200() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/specs/auth-flow/restore", "token", "{\"rev\": \"2-abc\"}");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"from_rev\": \"2-abc\""));
    }
  }

  @Test
  void globalSpecRestoreRejectsNonPost() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/restore", "token");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void followupPostReturns201WithDraftedSpec() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/specs/auth-flow/followup", "token", "{}");
      assertEquals(201, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"auth-flow-followup\""));
      assertTrue(response.body().contains("\"source_spec_id\": \"auth-flow\""));
      assertTrue(response.body().contains("\"finding_count\": 2"));
    }
  }

  @Test
  void followupHonorsRequestedId() throws Exception {
    try (var server = server()) {
      var response =
          post(server, "/v1/specs/auth-flow/followup", "token", "{\"id\": \"auth-round2\"}");
      assertEquals(201, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"auth-round2\""));
    }
  }

  @Test
  void followupRejectsNonPost() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/followup", "token");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void specReviewsListReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/reviews", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"spec_id\": \"auth-flow\""));
      assertTrue(response.body().contains("\"reviews\""));
    }
  }

  @Test
  void reviewDetailReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/reviews/review-123", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"review-123\""));
      assertTrue(response.body().contains("\"findings\""));
    }
  }

  @Test
  void reviewApproveReturns200() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/reviews/review-123/approve", "token", "");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"approved\": true"));
    }
  }

  @Test
  void reviewDismissFindingReturns200() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/reviews/review-123/dismiss/finding-456", "token", "");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"dismissed\": true"));
    }
  }

  @Test
  void reviewUnknownSubResourceReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/reviews/review-123/unknown", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void reviewExtraDepthReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/reviews/r1/approve/extra", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void reviewGetOnApproveReturns405() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/reviews/r1/approve", "token");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void agentSessionsReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent/sessions", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"project\": \"acme\""));
      assertTrue(response.body().contains("\"sessions\""));
      assertTrue(response.body().contains("\"agent\": \"claude-code\""));
    }
  }

  @Test
  void sessionViewModelCoversConstruction() {
    var row =
        new ai.singlr.sail.store.SessionStore.SessionRow(
            "s1",
            "proj",
            "auth",
            "claude-code",
            "feat/auth",
            "task",
            42,
            "completed",
            "t0",
            "t1",
            7);
    var view = SessionView.from(row);
    assertEquals("s1", view.id());
    assertEquals("proj", view.project());
    assertEquals(42, view.pid());
    var map = view.toMap();
    assertEquals("claude-code", map.get("agent"));
    assertEquals(7, map.get("exit_code"));
    assertTrue(map.containsKey("completed_at"));

    var list = new SessionListResponse("proj", java.util.List.of(view));
    assertEquals("proj", list.toMap().get("project"));
  }

  @Test
  void reviewsRequireAuth() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/reviews/review-123", null);
      assertEquals(401, response.statusCode());
    }
  }

  @Test
  void globalSpecsRequireAuth() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs", null);
      assertEquals(401, response.statusCode());
    }
  }

  @Test
  void globalSpecInvalidIdReturns422() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/Bad_Name!", "token");
      assertEquals(422, response.statusCode());
    }
  }

  @Test
  void globalSpecUnknownSubResourceReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/unknown", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void globalSpecExtraDepthReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/content/extra", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void globalSpecDeleteMethodNotAllowedOnContent() throws Exception {
    try (var server = server()) {
      var response = delete(server, "/v1/specs/auth-flow/content", "token");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void globalSpecPutMethodNotAllowedOnList() throws Exception {
    try (var server = server()) {
      var response = put(server, "/v1/specs", "token", "{}");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void globalSpecPostMethodNotAllowedOnDetail() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/specs/auth-flow", "token", "{}");
      assertEquals(405, response.statusCode());
    }
  }

  private static SailApiServer server() throws IOException {
    return serverWith(new FakeOperations(), true);
  }

  private static SailApiServer serverWith(ApiOperations ops) throws IOException {
    return serverWith(ops, false);
  }

  private static SailApiServer serverWith(ApiOperations ops, boolean autoStart) throws IOException {
    var server =
        new SailApiServer("127.0.0.1", 0, ops, new FixedTokenTestAuth("token"), null, null, null);
    if (autoStart) {
      server.start();
    }
    return server;
  }

  private static HttpResponse<String> get(SailApiServer server, String path, String token)
      throws Exception {
    var builder = HttpRequest.newBuilder(uri(server, path)).GET();
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(
      SailApiServer server, String path, String token, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(uri(server, path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> put(
      SailApiServer server, String path, String token, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(uri(server, path))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> delete(SailApiServer server, String path, String token)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(uri(server, path))
            .method("DELETE", HttpRequest.BodyPublishers.noBody());
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(SailApiServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private static class FakeOperations implements ApiOperations {
    @Override
    public Result<HealthResponse> health() {
      return Result.success(new HealthResponse("ok"));
    }

    @Override
    public Result<ProjectResponse> project(String project) {
      return Result.success(new ProjectResponse(project, "running", null));
    }

    @Override
    public Result<SpecsResponse> specs(String project) {
      return Result.success(
          new SpecsResponse(
              project,
              java.util.List.of(),
              new SpecSummaryView(0, 0, 0, 0),
              new BoardSummaryView(new SpecSummaryView(0, 0, 0, 0), 0, 0, null)));
    }

    @Override
    public Result<SpecResponse> spec(String project, String specId) {
      return Result.success(
          new SpecResponse(
              project,
              new SpecView(
                  specId,
                  "Spec",
                  "pending",
                  null,
                  java.util.List.of(),
                  java.util.List.of(),
                  null,
                  null,
                  null,
                  null,
                  true,
                  false,
                  java.util.List.of()),
              "specs/" + specId + "/spec.md",
              true,
              "content"));
    }

    @Override
    public Result<DispatchResponse> dispatch(String project, DispatchRequest request) {
      return Result.success(
          new DispatchResponse(
              project,
              true,
              null,
              new DispatchedSpecView(
                  request.specId(), "Spec", "in_progress", request.repos(), null, null, null, null),
              null,
              "",
              false));
    }

    @Override
    public Result<AgentStatusResponse> agentStatus(String project) {
      return Result.success(new AgentStatusResponse(project, false, null, null, null, null, null));
    }

    @Override
    public Result<AgentLogResponse> agentLog(String project, int tail) {
      return Result.success(
          new AgentLogResponse(project, java.util.List.of(String.valueOf(tail)), null));
    }

    @Override
    public Result<StopAgentResponse> stopAgent(String project) {
      return Result.success(new StopAgentResponse(project, false, null, null));
    }

    @Override
    public Result<AgentReportResponse> agentReport(String project) {
      return Result.success(
          new AgentReportResponse(
              project,
              "No session",
              null,
              null,
              null,
              null,
              java.util.List.of(),
              0,
              null,
              false,
              null,
              null,
              false,
              null));
    }

    @Override
    public Result<EventPublishResponse> publishEvent(Event event) {
      return Result.success(new EventPublishResponse(1L, event.toMap()));
    }

    @Override
    public Result<RecentEventsResponse> recentEvents(int limit) {
      return Result.success(new RecentEventsResponse(limit, 0, java.util.List.of()));
    }

    @Override
    public Result<EventBusStatsResponse> eventBusStats() {
      return Result.success(new EventBusStatsResponse(0L, 0L, java.util.List.of()));
    }

    @Override
    public Result<GlobalSpecsListResponse> globalSpecs(
        ai.singlr.sail.store.SpecStore.SpecFilter filter) {
      return Result.success(new GlobalSpecsListResponse(java.util.List.of(), 0));
    }

    @Override
    public Result<GlobalSpecDetailResponse> globalSpec(String specId) {
      return Result.success(
          new GlobalSpecDetailResponse(
              new GlobalSpecView(
                  specId,
                  "test-project",
                  "Test",
                  "pending",
                  null,
                  null,
                  null,
                  null,
                  0,
                  java.util.List.of(),
                  java.util.List.of(),
                  null,
                  "",
                  "",
                  null),
              null,
              null,
              0));
    }

    @Override
    public Result<FollowupSpecResponse> createFollowupSpec(
        String specId, FollowupCreateRequest request) {
      return Result.success(
          new FollowupSpecResponse(
              new GlobalSpecView(
                  request.id() != null ? request.id() : specId + "-followup",
                  "test-project",
                  "Address review findings: Test",
                  "draft",
                  null,
                  null,
                  null,
                  null,
                  3,
                  java.util.List.of(),
                  java.util.List.of(),
                  request.createdBy(),
                  "",
                  "",
                  request.createdBy()),
              specId,
              "r1",
              2));
    }

    @Override
    public Result<GlobalSpecCreatedResponse> createGlobalSpec(SpecCreateRequest request) {
      return Result.success(
          new GlobalSpecCreatedResponse(
              new GlobalSpecView(
                  request.id(),
                  request.project(),
                  request.title(),
                  request.status(),
                  null,
                  null,
                  null,
                  null,
                  0,
                  java.util.List.of(),
                  java.util.List.of(),
                  null,
                  "",
                  "",
                  null)));
    }

    @Override
    public Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
        String specId, SpecUpdateRequest request) {
      return Result.success(
          new GlobalSpecUpdatedResponse(
              new GlobalSpecView(
                  specId,
                  "test-project",
                  "Updated",
                  "pending",
                  null,
                  null,
                  null,
                  null,
                  0,
                  java.util.List.of(),
                  java.util.List.of(),
                  null,
                  "",
                  "",
                  null)));
    }

    @Override
    public Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId) {
      return Result.success(new GlobalSpecDeletedResponse(specId));
    }

    @Override
    public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
      return Result.success(new GlobalSpecContentResponse(specId, "", ""));
    }

    @Override
    public Result<GlobalSpecContentResponse> setGlobalSpecContent(
        String specId, SpecContentRequest request) {
      return Result.success(new GlobalSpecContentResponse(specId, request.body(), request.plan()));
    }

    @Override
    public Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId) {
      return Result.success(
          new GlobalSpecHistoryResponse(
              specId,
              java.util.List.of(
                  new SpecRevisionView("1-abc", "uday", "2026-06-13T00:00:00Z", "local", false))));
    }

    @Override
    public Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
        String specId, SpecRestoreRequest request) {
      return Result.success(
          new GlobalSpecRestoredResponse(
              GlobalSpecView.from(
                  new ai.singlr.sail.store.SpecStore.SpecRow(
                      specId,
                      "proj",
                      "t",
                      ai.singlr.sail.config.SpecStatus.fromWire("pending"),
                      null,
                      null,
                      null,
                      null,
                      null,
                      0,
                      null,
                      "",
                      "",
                      null,
                      java.util.List.of(),
                      java.util.List.of())),
              request.rev()));
    }

    @Override
    public Result<GlobalBoardResponse> globalBoard(String project) {
      return Result.success(
          new GlobalBoardResponse(
              new ai.singlr.sail.store.SpecStore.BoardSummary(0, 0, 0, 0, 0, 0, null), 0));
    }

    @Override
    public Result<ReviewListResponse> reviewsForSpec(String specId) {
      var stage = new StageView("s1", "security", "agent", "passed", "codex", "t1", "t2", 2, null);
      var review =
          new ReviewView(
              "r1", specId, 1, "passed", "t0", "t1", null, null, null, java.util.List.of(stage));
      return Result.success(new ReviewListResponse(specId, java.util.List.of(review)));
    }

    @Override
    public Result<ReviewDetailResponse> reviewDetail(String reviewId) {
      var stage = new StageView("s1", "security", "agent", "passed", "codex", "t1", "t2", 1, null);
      var review =
          new ReviewView(
              reviewId,
              "spec",
              1,
              "passed",
              "t0",
              "t1",
              null,
              null,
              null,
              java.util.List.of(stage));
      var finding =
          java.util.Map.<String, Object>of(
              "id", "f1", "severity", "HIGH", "title", "SQL injection");
      return Result.success(new ReviewDetailResponse(review, java.util.List.of(finding)));
    }

    @Override
    public Result<ReviewApproveResponse> approveReview(String reviewId, String actor) {
      return Result.success(new ReviewApproveResponse(reviewId, true));
    }

    @Override
    public Result<FindingDismissResponse> dismissFinding(String reviewId, String findingId) {
      return Result.success(new FindingDismissResponse(findingId, true));
    }

    @Override
    public Result<SessionListResponse> agentSessions(String project) {
      var session =
          new SessionView(
              "s1",
              project,
              "auth",
              "claude-code",
              "feat/auth",
              "task",
              1234,
              "running",
              "t0",
              null,
              null);
      return Result.success(new SessionListResponse(project, java.util.List.of(session)));
    }
  }

  private static final class FailingOperations extends FakeOperations {
    @Override
    public Result<AgentStatusResponse> agentStatus(String project) {
      return Result.failure(ErrorCode.CONFLICT, "Agent is busy.", "Wait.");
    }
  }

  private static final class ExplodingOperations extends FakeOperations {
    @Override
    public Result<AgentStatusResponse> agentStatus(String project) {
      throw new IllegalStateException("boom");
    }
  }
}
