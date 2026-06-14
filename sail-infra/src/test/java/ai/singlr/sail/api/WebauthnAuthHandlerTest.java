/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.auth.EnrollmentService;
import ai.singlr.sail.auth.EnrollmentTickets;
import ai.singlr.sail.auth.PasskeyCeremonies;
import ai.singlr.sail.auth.PasskeyException;
import ai.singlr.sail.auth.PasskeyService;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.EnrollmentTicketStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.PendingChallengeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.webauthn.RelyingParty;
import ai.singlr.sail.webauthn.TestAuthenticator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebauthnAuthHandlerTest {

  private static final String RP_ID = "sail.acme.dev";
  private static final String ORIGIN = "https://sail.acme.dev";
  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

  @TempDir Path tempDir;
  private Sqlite db;
  private TokenStore tokenStore;
  private String adminToken;
  private String viewerToken;
  private SailApiServer server;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    tokenStore = new TokenStore(db);
    adminToken = tokenStore.create("admin", "admin").token();
    viewerToken = tokenStore.create("viewer", "viewer").token();
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.close();
    if (db != null) db.close();
  }

  private void startWith(PasskeyCeremonies ceremonies) throws Exception {
    startWith(
        ceremonies,
        ceremonies == null ? null : new FakeEnrollment(),
        ceremonies == null ? null : ORIGIN);
  }

  private void startWith(
      PasskeyCeremonies ceremonies, EnrollmentTickets enrollment, String enrollOrigin)
      throws Exception {
    server =
        new SailApiServer(
            "127.0.0.1",
            0,
            new TestOperations(),
            tokenStore,
            new EventBus(),
            null,
            new WebauthnAuthHandler(
                ceremonies, enrollment, new TokenAuth(tokenStore), enrollOrigin));
    server.start();
  }

  private HttpResponse<String> post(String path, String token, String body) throws Exception {
    return send("POST", path, token, body);
  }

  private HttpResponse<String> send(String method, String path, String token, String body)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    builder.header("Content-Type", "application/json");
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void registerStartRequiresAdmin() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(401, post("/v1/auth/register/start", null, "{\"fde\":\"uday\"}").statusCode());
    assertEquals(
        403, post("/v1/auth/register/start", viewerToken, "{\"fde\":\"uday\"}").statusCode());
    assertEquals(
        200, post("/v1/auth/register/start", adminToken, "{\"fde\":\"uday\"}").statusCode());
  }

  @Test
  void registerStartReturnsChallengeAndOptions() throws Exception {
    startWith(new FakeCeremonies());
    var response = post("/v1/auth/register/start", adminToken, "{\"fde\":\"uday\"}");
    var body = YamlUtil.parseMap(response.body());
    assertEquals("wac_reg", body.get("challenge_id"));
    assertTrue(((Map<?, ?>) body.get("public_key")).containsKey("challenge"));
  }

  @Test
  void registerStartMissingFdeIsRejected() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(422, post("/v1/auth/register/start", adminToken, "{}").statusCode());
  }

  @Test
  void registerStartMapsNotFound() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(
        404, post("/v1/auth/register/start", adminToken, "{\"fde\":\"ghost\"}").statusCode());
  }

  @Test
  void registerStartMapsIllegalArgumentToInvalidRequest() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(
        422, post("/v1/auth/register/start", adminToken, "{\"fde\":\"iae\"}").statusCode());
  }

  @Test
  void registerStartMapsUnexpectedToInternal() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(
        500, post("/v1/auth/register/start", adminToken, "{\"fde\":\"boom\"}").statusCode());
  }

  @Test
  void registerFinishSavesAndEchoesCredential() throws Exception {
    startWith(new FakeCeremonies());
    var body =
        "{\"challenge_id\":\"wac_reg\",\"client_data_json\":\"AAAA\","
            + "\"attestation_object\":\"AAAA\",\"label\":\"laptop\"}";
    var response = post("/v1/auth/register/finish", adminToken, body);
    assertEquals(200, response.statusCode());
    var parsed = YamlUtil.parseMap(response.body());
    assertEquals("registered", parsed.get("status"));
    assertEquals("uday", parsed.get("fde"));
    assertEquals(B64URL.encodeToString(new byte[] {1, 2, 3}), parsed.get("credential_id"));
  }

  @Test
  void registerFinishWithoutLabelSucceeds() throws Exception {
    startWith(new FakeCeremonies());
    var body =
        "{\"challenge_id\":\"wac_reg\",\"client_data_json\":\"AAAA\",\"attestation_object\":\"AAAA\"}";
    assertEquals(200, post("/v1/auth/register/finish", adminToken, body).statusCode());
  }

  @Test
  void registerFinishRejectsBadBase64() throws Exception {
    startWith(new FakeCeremonies());
    var body =
        "{\"challenge_id\":\"wac_reg\",\"client_data_json\":\"!!!\",\"attestation_object\":\"AAAA\"}";
    assertEquals(422, post("/v1/auth/register/finish", adminToken, body).statusCode());
  }

  @Test
  void registerFinishMapsBadRequest() throws Exception {
    startWith(new FakeCeremonies());
    var body =
        "{\"challenge_id\":\"wac_reg\",\"client_data_json\":\"AAAA\","
            + "\"attestation_object\":\"AAAA\",\"label\":\"dup\"}";
    assertEquals(422, post("/v1/auth/register/finish", adminToken, body).statusCode());
  }

  @Test
  void loginStartIsPublicAndReturnsChallenge() throws Exception {
    startWith(new FakeCeremonies());
    var response = post("/v1/auth/login/start", null, "{}");
    assertEquals(200, response.statusCode());
    assertEquals("wac_login", YamlUtil.parseMap(response.body()).get("challenge_id"));
  }

  @Test
  void loginFinishIssuesSession() throws Exception {
    startWith(new FakeCeremonies());
    var body =
        "{\"challenge_id\":\"wac_login\",\"credential_id\":\"AAAA\",\"client_data_json\":\"AAAA\","
            + "\"authenticator_data\":\"AAAA\",\"signature\":\"AAAA\"}";
    var response = post("/v1/auth/login/finish", null, body);
    assertEquals(200, response.statusCode());
    assertEquals("sess_fake", YamlUtil.parseMap(response.body()).get("session_token"));
  }

  @Test
  void loginFinishMissingFieldIsRejected() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(422, post("/v1/auth/login/finish", null, "{}").statusCode());
  }

  @Test
  void loginFinishDecodesAndForwardsTheUserHandle() throws Exception {
    var ceremonies = new FakeCeremonies();
    startWith(ceremonies);
    var body =
        "{\"challenge_id\":\"wac_login\",\"credential_id\":\"AAAA\",\"client_data_json\":\"AAAA\","
            + "\"authenticator_data\":\"AAAA\",\"signature\":\"AAAA\",\"user_handle\":\"AQID\"}";

    assertEquals(200, post("/v1/auth/login/finish", null, body).statusCode());
    assertArrayEquals(new byte[] {1, 2, 3}, ceremonies.lastUserHandle);
  }

  @Test
  void loginFinishTreatsAnAbsentUserHandleAsNull() throws Exception {
    var ceremonies = new FakeCeremonies();
    startWith(ceremonies);
    var body =
        "{\"challenge_id\":\"wac_login\",\"credential_id\":\"AAAA\",\"client_data_json\":\"AAAA\","
            + "\"authenticator_data\":\"AAAA\",\"signature\":\"AAAA\"}";

    assertEquals(200, post("/v1/auth/login/finish", null, body).statusCode());
    assertTrue(ceremonies.userHandleSeen);
    assertNull(ceremonies.lastUserHandle);
  }

  @Test
  void loginFinishRejectsAMalformedUserHandle() throws Exception {
    startWith(new FakeCeremonies());
    var body =
        "{\"challenge_id\":\"wac_login\",\"credential_id\":\"AAAA\",\"client_data_json\":\"AAAA\","
            + "\"authenticator_data\":\"AAAA\",\"signature\":\"AAAA\",\"user_handle\":\"!!!\"}";

    assertEquals(422, post("/v1/auth/login/finish", null, body).statusCode());
  }

  @Test
  void loginFinishMapsUnauthorized() throws Exception {
    startWith(new FakeCeremonies());
    var body =
        "{\"challenge_id\":\"bad\",\"credential_id\":\"AAAA\",\"client_data_json\":\"AAAA\","
            + "\"authenticator_data\":\"AAAA\",\"signature\":\"AAAA\"}";
    assertEquals(401, post("/v1/auth/login/finish", null, body).statusCode());
  }

  @Test
  void nonPostIsMethodNotAllowed() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(405, send("GET", "/v1/auth/login/start", null, null).statusCode());
  }

  @Test
  void unknownPasskeyEndpointIsNotFound() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(404, post("/v1/auth/bogus", adminToken, "{}").statusCode());
  }

  @Test
  void unconfiguredServerReturns503() throws Exception {
    startWith(null);
    assertEquals(503, post("/v1/auth/login/start", null, "{}").statusCode());
  }

  @Test
  void mintTicketRequiresAdmin() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(401, post("/v1/auth/register/ticket", null, "{\"fde\":\"uday\"}").statusCode());
    assertEquals(
        403, post("/v1/auth/register/ticket", viewerToken, "{\"fde\":\"uday\"}").statusCode());
  }

  @Test
  void mintTicketReturnsTicketAndEnrollUrl() throws Exception {
    startWith(new FakeCeremonies());
    var response = post("/v1/auth/register/ticket", adminToken, "{\"fde\":\"uday\"}");
    assertEquals(200, response.statusCode());
    var body = YamlUtil.parseMap(response.body());
    assertEquals("enr_fake", body.get("ticket"));
    assertEquals("uday", body.get("fde"));
    assertEquals(ORIGIN + "/enroll?ticket=enr_fake", body.get("enroll_url"));
  }

  @Test
  void mintTicketMissingFdeIsRejected() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(422, post("/v1/auth/register/ticket", adminToken, "{}").statusCode());
  }

  @Test
  void mintTicketUnknownFdeIsNotFound() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(
        404, post("/v1/auth/register/ticket", adminToken, "{\"fde\":\"ghost\"}").statusCode());
  }

  @Test
  void mintTicketOmitsUrlWhenOriginUnset() throws Exception {
    startWith(new FakeCeremonies(), new FakeEnrollment(), null);
    var body =
        YamlUtil.parseMap(
            post("/v1/auth/register/ticket", adminToken, "{\"fde\":\"uday\"}").body());
    assertFalse(body.containsKey("enroll_url"));
  }

  @Test
  void registerStartWithValidTicketNeedsNoToken() throws Exception {
    startWith(new FakeCeremonies());
    var response = postWithTicket("/v1/auth/register/start", "enr_valid", "{}");
    assertEquals(200, response.statusCode());
    assertEquals("wac_reg", YamlUtil.parseMap(response.body()).get("challenge_id"));
  }

  @Test
  void registerStartWithInvalidTicketIsForbidden() throws Exception {
    startWith(new FakeCeremonies());
    assertEquals(403, postWithTicket("/v1/auth/register/start", "enr_bad", "{}").statusCode());
  }

  @Test
  void registerFinishWithValidTicketSucceeds() throws Exception {
    startWith(new FakeCeremonies());
    var body =
        "{\"challenge_id\":\"wac_reg\",\"client_data_json\":\"AAAA\",\"attestation_object\":\"AAAA\"}";
    assertEquals(200, postWithTicket("/v1/auth/register/finish", "enr_valid", body).statusCode());
  }

  @Test
  void registerFinishWithInvalidTicketIsForbidden() throws Exception {
    startWith(new FakeCeremonies());
    var body =
        "{\"challenge_id\":\"wac_reg\",\"client_data_json\":\"AAAA\",\"attestation_object\":\"AAAA\"}";
    assertEquals(403, postWithTicket("/v1/auth/register/finish", "enr_bad", body).statusCode());
  }

  @Test
  void realRoundTripEnrollsAndLogsIn() throws Exception {
    new FdeStore(db).add("uday", null, null);
    var service =
        new PasskeyService(
            new RelyingParty(RP_ID, "Sail", Set.of(ORIGIN)),
            new FdeStore(db),
            new WebauthnCredentialStore(db),
            new AuthSessionStore(db),
            new PendingChallengeStore(db));
    startWith(service);
    var authenticator = new TestAuthenticator(RP_ID);

    var regStart =
        YamlUtil.parseMap(post("/v1/auth/register/start", adminToken, "{\"fde\":\"uday\"}").body());
    var regChallenge = challengeOf(regStart);
    var registration = authenticator.register(regChallenge, ORIGIN);
    var regFinish =
        post(
            "/v1/auth/register/finish",
            adminToken,
            "{\"challenge_id\":\""
                + regStart.get("challenge_id")
                + "\",\"client_data_json\":\""
                + B64URL.encodeToString(registration.clientDataJson())
                + "\",\"attestation_object\":\""
                + B64URL.encodeToString(registration.attestationObject())
                + "\"}");
    assertEquals(200, regFinish.statusCode(), regFinish.body());

    var loginStart = YamlUtil.parseMap(post("/v1/auth/login/start", null, "{}").body());
    var assertion = authenticator.assertResponse(challengeOf(loginStart), ORIGIN);
    var loginFinish =
        post(
            "/v1/auth/login/finish",
            null,
            "{\"challenge_id\":\""
                + loginStart.get("challenge_id")
                + "\",\"credential_id\":\""
                + B64URL.encodeToString(assertion.credentialId())
                + "\",\"client_data_json\":\""
                + B64URL.encodeToString(assertion.clientDataJson())
                + "\",\"authenticator_data\":\""
                + B64URL.encodeToString(assertion.authenticatorData())
                + "\",\"signature\":\""
                + B64URL.encodeToString(assertion.signature())
                + "\"}");
    assertEquals(200, loginFinish.statusCode(), loginFinish.body());
    var session = YamlUtil.parseMap(loginFinish.body());
    assertEquals("uday", session.get("fde"));
    assertTrue(
        new AuthSessionStore(db).validate((String) session.get("session_token")).isPresent());
  }

  @Test
  void realTicketEnrollmentRoundTrip() throws Exception {
    new FdeStore(db).add("uday", null, null);
    var service =
        new PasskeyService(
            new RelyingParty(RP_ID, "Sail", Set.of(ORIGIN)),
            new FdeStore(db),
            new WebauthnCredentialStore(db),
            new AuthSessionStore(db),
            new PendingChallengeStore(db));
    startWith(
        service, new EnrollmentService(new EnrollmentTicketStore(db), new FdeStore(db)), ORIGIN);
    var authenticator = new TestAuthenticator(RP_ID);

    var mint =
        YamlUtil.parseMap(
            post("/v1/auth/register/ticket", adminToken, "{\"fde\":\"uday\"}").body());
    var ticket = (String) mint.get("ticket");
    assertEquals(ORIGIN + "/enroll?ticket=" + ticket, mint.get("enroll_url"));

    var regStart =
        YamlUtil.parseMap(postWithTicket("/v1/auth/register/start", ticket, "{}").body());
    var registration = authenticator.register(challengeOf(regStart), ORIGIN);
    var regFinish =
        postWithTicket(
            "/v1/auth/register/finish", ticket, registerFinishBody(regStart, registration));
    assertEquals(200, regFinish.statusCode(), regFinish.body());

    assertEquals(403, postWithTicket("/v1/auth/register/start", ticket, "{}").statusCode());

    var loginStart = YamlUtil.parseMap(post("/v1/auth/login/start", null, "{}").body());
    var assertion = authenticator.assertResponse(challengeOf(loginStart), ORIGIN);
    var loginFinish = post("/v1/auth/login/finish", null, loginFinishBody(loginStart, assertion));
    assertEquals(200, loginFinish.statusCode(), loginFinish.body());
    assertEquals("uday", YamlUtil.parseMap(loginFinish.body()).get("fde"));
  }

  private HttpResponse<String> postWithTicket(String path, String ticket, String body)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    builder.header("X-Enrollment-Ticket", ticket);
    builder.header("Content-Type", "application/json");
    builder.method("POST", HttpRequest.BodyPublishers.ofString(body));
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String registerFinishBody(
      Map<String, Object> regStart, TestAuthenticator.Registration registration) {
    return "{\"challenge_id\":\""
        + regStart.get("challenge_id")
        + "\",\"client_data_json\":\""
        + B64URL.encodeToString(registration.clientDataJson())
        + "\",\"attestation_object\":\""
        + B64URL.encodeToString(registration.attestationObject())
        + "\"}";
  }

  private static String loginFinishBody(
      Map<String, Object> loginStart, TestAuthenticator.Assertion assertion) {
    return "{\"challenge_id\":\""
        + loginStart.get("challenge_id")
        + "\",\"credential_id\":\""
        + B64URL.encodeToString(assertion.credentialId())
        + "\",\"client_data_json\":\""
        + B64URL.encodeToString(assertion.clientDataJson())
        + "\",\"authenticator_data\":\""
        + B64URL.encodeToString(assertion.authenticatorData())
        + "\",\"signature\":\""
        + B64URL.encodeToString(assertion.signature())
        + "\"}";
  }

  private static byte[] challengeOf(Map<String, Object> ceremony) {
    var publicKey = (Map<?, ?>) ceremony.get("public_key");
    return Base64.getUrlDecoder().decode((String) publicKey.get("challenge"));
  }

  private static final class FakeEnrollment implements EnrollmentTickets {

    @Override
    public Ticket issue(String fdeHandle) {
      if ("ghost".equals(fdeHandle)) {
        throw new PasskeyException(PasskeyException.Kind.NOT_FOUND, "no such fde");
      }
      return new Ticket("enr_fake", fdeHandle, "2026-06-01T00:00:00Z");
    }

    @Override
    public Optional<String> authorize(String ticket) {
      return "enr_valid".equals(ticket) ? Optional.of("uday") : Optional.empty();
    }

    @Override
    public boolean consume(String ticket) {
      return "enr_valid".equals(ticket);
    }
  }

  private static final class FakeCeremonies implements PasskeyCeremonies {

    byte[] lastUserHandle;
    boolean userHandleSeen;

    @Override
    public Ceremony startRegistration(String fdeHandle) {
      return switch (fdeHandle) {
        case "ghost" -> throw new PasskeyException(PasskeyException.Kind.NOT_FOUND, "no such fde");
        case "iae" -> throw new IllegalArgumentException("bad input");
        case "boom" -> throw new IllegalStateException("kaboom");
        default -> new Ceremony("wac_reg", Map.of("challenge", "Y2hhbGxlbmdl"));
      };
    }

    @Override
    public Registration finishRegistration(
        String challengeId, byte[] clientDataJson, byte[] attestationObject, String label) {
      if ("dup".equals(label)) {
        throw new PasskeyException(PasskeyException.Kind.BAD_REQUEST, "already registered");
      }
      return new Registration("uday", new byte[] {1, 2, 3});
    }

    @Override
    public Ceremony startLogin() {
      return new Ceremony("wac_login", Map.of("challenge", "Y2hhbGxlbmdl"));
    }

    @Override
    public LoginResult finishLogin(
        String challengeId,
        byte[] credentialId,
        byte[] clientDataJson,
        byte[] authenticatorData,
        byte[] signature,
        byte[] userHandle) {
      if ("bad".equals(challengeId)) {
        throw new PasskeyException(PasskeyException.Kind.UNAUTHORIZED, "auth failed");
      }
      this.lastUserHandle = userHandle;
      this.userHandleSeen = true;
      return new LoginResult("sess_fake", "uday", "2026-06-01T00:00:00Z");
    }
  }
}
