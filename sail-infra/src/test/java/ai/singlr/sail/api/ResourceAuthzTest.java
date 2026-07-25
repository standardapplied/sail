/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Route-level authorization matrix against a real {@link SailOperations} and real stores, so the
 * aggregate {@code AccessPolicy} classes are exercised end to end for every scoped route — spec
 * edit/delete/content/restore, reassignment, and review approve/dismiss — over both the API-token
 * lane and the passkey/session (gateway) lane, plus the host-admin (no-fde) regression.
 */
class ResourceAuthzTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specStore;
  private ReviewStore reviewStore;
  private FdeStore fdes;
  private AuthSessionStore sessions;
  private TokenStore tokenStore;
  private SailApiServer server;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    reviewStore = new ReviewStore(db);
    fdes = new FdeStore(db);
    sessions = new AuthSessionStore(db);
    tokenStore = new TokenStore(db);
    var yaml = tempDir.resolve("sail.yaml").toString();
    var ops =
        new SailOperations(
            new ShellExecutor(false), yaml, new EventBus(), null, specStore, reviewStore);
    var auth = new SessionAwareAuth(sessions, fdes, new TokenAuth(tokenStore));
    server = new SailApiServer("127.0.0.1", 0, ops, auth, new EventBus(), null, null, null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.close();
    if (db != null) db.close();
  }

  private void seedSpec(String id, String assignee, String createdBy) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            "Title",
            SpecStatus.PENDING,
            assignee,
            null,
            null,
            null,
            null,
            0,
            createdBy,
            "",
            "",
            createdBy,
            List.of(),
            List.of()));
  }

  private String seedReviewAwaitingApproval(String specId) {
    var reviewId = reviewStore.createReview(specId, 1);
    var stageId = reviewStore.createStage(reviewId, "human", "human");
    reviewStore.startStage(stageId, "someone");
    reviewStore.addFinding(
        stageId,
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "A.java",
            1,
            2,
            "Issue",
            "Desc",
            "Ev",
            new Finding.Suggestion("bad", "good", "why"),
            0.9));
    return reviewId;
  }

  private String memberToken(String handle) {
    var fde = fdes.add(handle, null, null, "member");
    return tokenStore.create(handle + "-laptop", "member", fde.id(), null).token();
  }

  private String sessionToken(String handle, String role) {
    var fde = fdes.add(handle, null, null, role);
    return sessions.create(fde.id(), Duration.ofMinutes(30)).token();
  }

  @Test
  void assigneeEditsOwnSpec() throws Exception {
    seedSpec("auth", "uday", "uday");
    assertEquals(
        200,
        send("PUT", "/v1/specs/auth", memberToken("uday"), "{\"title\":\"New\"}").statusCode());
  }

  @Test
  void nonAssigneeMemberCannotEditNamingTheOwner() throws Exception {
    seedSpec("auth", "uday", "uday");
    var response = send("PUT", "/v1/specs/auth", memberToken("raj"), "{\"title\":\"New\"}");
    assertEquals(403, response.statusCode(), response.body());
    assertTrue(response.body().contains("forbidden_not_assignee"), response.body());
    assertTrue(response.body().contains("uday"), response.body());
  }

  @Test
  void viewerCannotEditASpec() throws Exception {
    seedSpec("auth", "uday", "uday");
    var viewer = tokenStore.create("v", "viewer").token();
    assertEquals(403, send("PUT", "/v1/specs/auth", viewer, "{\"title\":\"New\"}").statusCode());
  }

  @Test
  void adminEditsAnyonesSpec() throws Exception {
    seedSpec("auth", "uday", "uday");
    var admin = tokenStore.create("a", "admin").token();
    assertEquals(200, send("PUT", "/v1/specs/auth", admin, "{\"title\":\"New\"}").statusCode());
  }

  @Test
  void assigneeDeletesOwnSpecButNonAssigneeCannot() throws Exception {
    seedSpec("auth", "uday", "uday");
    seedSpec("billing", "uday", "uday");
    assertEquals(403, send("DELETE", "/v1/specs/auth", memberToken("raj"), null).statusCode());
    assertEquals(200, send("DELETE", "/v1/specs/billing", memberToken("uday"), null).statusCode());
  }

  @Test
  void assigneeWritesContentButNonAssigneeCannot() throws Exception {
    seedSpec("auth", "uday", "uday");
    assertEquals(
        403,
        send("PUT", "/v1/specs/auth/content", memberToken("raj"), "{\"body\":\"x\"}").statusCode());
    assertEquals(
        200,
        send("PUT", "/v1/specs/auth/content", memberToken("uday"), "{\"body\":\"x\"}")
            .statusCode());
  }

  @Test
  void nonAssigneeCannotRestoreButAssigneeReachesThePolicyGate() throws Exception {
    seedSpec("auth", "uday", "uday");
    assertEquals(
        403,
        send("POST", "/v1/specs/auth/restore", memberToken("raj"), "{\"rev\":\"1-a\"}")
            .statusCode());
    var assignee = send("POST", "/v1/specs/auth/restore", memberToken("uday"), "{\"rev\":\"1-a\"}");
    assertNotEquals(403, assignee.statusCode(), assignee.body());
  }

  @Test
  void unassignedSpecIsMutableOnlyByItsCreatorOrAdmin() throws Exception {
    seedSpec("orphan", null, "uday");
    assertEquals(
        200,
        send("PUT", "/v1/specs/orphan", memberToken("uday"), "{\"title\":\"n\"}").statusCode());
    assertEquals(
        403, send("PUT", "/v1/specs/orphan", memberToken("raj"), "{\"title\":\"n\"}").statusCode());
  }

  @Test
  void memberCannotReassignAnothersSpec() throws Exception {
    seedSpec("auth", "uday", "uday");
    var response = send("PUT", "/v1/specs/auth", memberToken("raj"), "{\"assignee\":\"raj\"}");
    assertEquals(403, response.statusCode(), response.body());
    assertTrue(response.body().contains("forbidden_admin_only"), response.body());
  }

  @Test
  void memberCannotReassignEvenTheirOwnSpecToAnother() throws Exception {
    seedSpec("auth", "uday", "uday");
    assertEquals(
        403,
        send("PUT", "/v1/specs/auth", memberToken("uday"), "{\"assignee\":\"raj\"}").statusCode());
  }

  @Test
  void memberMayClaimAnUnassignedSpec() throws Exception {
    seedSpec("free", null, "someone");
    assertEquals(
        200,
        send("PUT", "/v1/specs/free", memberToken("raj"), "{\"assignee\":\"raj\"}").statusCode());
  }

  @Test
  void adminMayReassignAnySpec() throws Exception {
    seedSpec("auth", "uday", "uday");
    var admin = tokenStore.create("a", "admin").token();
    assertEquals(200, send("PUT", "/v1/specs/auth", admin, "{\"assignee\":\"raj\"}").statusCode());
  }

  @Test
  void reviewAssigneeApprovesOwnGateButNonAssigneeCannot() throws Exception {
    seedSpec("auth", "uday", "uday");
    var refusedReview = seedReviewAwaitingApproval("auth");
    var refused =
        send("POST", "/v1/reviews/" + refusedReview + "/approve", memberToken("raj"), "{}");
    assertEquals(403, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("forbidden_not_assignee"), refused.body());

    var approved =
        send("POST", "/v1/reviews/" + refusedReview + "/approve", memberToken("uday"), "{}");
    assertEquals(200, approved.statusCode(), approved.body());
  }

  @Test
  void adminApprovesAnyReview() throws Exception {
    seedSpec("auth", "uday", "uday");
    var reviewId = seedReviewAwaitingApproval("auth");
    var admin = tokenStore.create("a", "admin").token();
    assertEquals(
        200, send("POST", "/v1/reviews/" + reviewId + "/approve", admin, "{}").statusCode());
  }

  @Test
  void reviewFindingDismissIsAssigneeOrAdmin() throws Exception {
    seedSpec("auth", "uday", "uday");
    var reviewId = seedReviewAwaitingApproval("auth");
    var findingId = reviewStore.findingsForReview(reviewId).getFirst().id();
    assertEquals(
        403,
        send("POST", "/v1/reviews/" + reviewId + "/dismiss/" + findingId, memberToken("raj"), "{}")
            .statusCode());
    assertEquals(
        200,
        send("POST", "/v1/reviews/" + reviewId + "/dismiss/" + findingId, memberToken("uday"), "{}")
            .statusCode());
  }

  @Test
  void gatewaySessionLaneObeysTheSameMatrix() throws Exception {
    seedSpec("auth", "uday", "uday");
    var udaySession = sessionToken("uday", "member");
    var rajSession = sessionToken("raj", "member");
    assertEquals(200, send("PUT", "/v1/specs/auth", udaySession, "{\"title\":\"n\"}").statusCode());
    assertEquals(403, send("PUT", "/v1/specs/auth", rajSession, "{\"title\":\"n\"}").statusCode());
  }

  @Test
  void hostAdminMachineTokenWithoutFdeStillFullyOperates() throws Exception {
    seedSpec("auth", "uday", "uday");
    var host = tokenStore.create("host", "admin").token();
    assertEquals(200, send("PUT", "/v1/specs/auth", host, "{\"title\":\"n\"}").statusCode());
    assertEquals(200, send("DELETE", "/v1/specs/auth", host, null).statusCode());
  }

  private HttpResponse<String> send(String method, String path, String token, String body)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    var publisher =
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body);
    builder.method(method, publisher);
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
