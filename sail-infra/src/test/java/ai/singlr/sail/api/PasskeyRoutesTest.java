/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.auth.PasskeyCeremonies;
import ai.singlr.sail.auth.PasskeyService;
import ai.singlr.sail.auth.Passkeys;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.PendingChallengeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.webauthn.RelyingParty;
import ai.singlr.sail.webauthn.TestAuthenticator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A person's own passkeys through the real web router over the production operations, stores and
 * passkey ceremonies: each person lists and removes only their own, a viewer too; a removal ends
 * the sessions the passkey signed in and leaves the caller's own; the last passkey and the one the
 * caller signed in with stay, the last said first; another person's id answers as no id does.
 */
class PasskeyRoutesTest {

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final String RP_ID = "sail.acme.dev";
  private static final String ORIGIN = "https://sail.acme.dev";
  private static final Instant FIRST = Instant.parse("2026-11-20T09:00:00Z");
  private static final Instant SECOND = Instant.parse("2026-11-21T09:00:00Z");
  private static final Instant SIGNED_IN = Instant.parse("2026-11-22T09:00:00Z");

  @AfterAll
  static void closeClient() {
    CLIENT.close();
  }

  @TempDir Path tempDir;
  private Clock clock;
  private Sqlite db;
  private HttpServer server;
  private PasskeyService ceremonies;
  private WebauthnCredentialStore credentials;
  private String theoId;
  private String machine;

  @BeforeEach
  void aBoxWithAMemberAnotherAndAViewer() throws Exception {
    clock = DateTimeUtils.clock;
    at(FIRST);
    db = Sqlite.open(tempDir.resolve("main.db"));
    new SchemaManager(db).migrate();
    Acting.system(
        () -> {
          var fdes = new FdeStore(db);
          fdes.add("mara", "Mara", "mara@x", "member");
          theoId = fdes.add("theo", "Theo", "theo@x", "member").id();
          fdes.add("vik", "Vik", "vik@x", "viewer");
          machine = new TokenStore(db).create("ci", "member", null, null).token();
        });
    credentials = new WebauthnCredentialStore(db);
    ceremonies =
        new PasskeyService(
            new RelyingParty(RP_ID, "Sail", Set.of(ORIGIN)),
            new FdeStore(db),
            credentials,
            new AuthSessionStore(db),
            new PendingChallengeStore(db),
            Duration.ofDays(30));
    var operations =
        TestControlPlane.on(
            OperationsFactory.create(
                db, NO_CONTAINERS, null, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            tempDir,
            new SyncConfig(null, null, null, "box"));
    var auth = TestAuth.sessions(db);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        new ApiRouter(
            operations, auth, new RateLimitGate(), new AgentLogStreamer(auth), Optional.empty()));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    db.close();
    DateTimeUtils.clock = clock;
  }

  private static void at(Instant instant) {
    DateTimeUtils.clock = Clock.fixed(instant, ZoneOffset.UTC);
  }

  /** Scenario 1: both of Mara's passkeys, oldest first, the one she signed in with current. */
  @Test
  void aPersonListsTheirOwnPasskeysOldestFirstTheOneTheySignedInWithCurrent() throws Exception {
    var first = enroll("mara", "MacBook");
    at(SECOND);
    var second = enroll("mara", "iPhone");
    enroll("theo", "Theo's Mac");
    at(SIGNED_IN);
    var mara = signIn(first);

    var listed = ask(mara, "GET", "/v1/passkeys");

    assertEquals(200, listed.status());
    assertEquals(
        Map.of(
            "schema_version",
            1,
            "passkeys",
            List.of(
                passkey(first, "MacBook", FIRST, SIGNED_IN, true),
                passkey(second, "iPhone", SECOND, null, false)),
            "total",
            2),
        listed.map());
  }

  /** Scenario 7: a viewer lists their own, none current for a session no passkey signed in. */
  @Test
  void aViewerListsTheirOwn() throws Exception {
    var vik = enroll("vik", "Vik's Mac");
    var session = sessionOf("vik");

    var listed = ask(session, "GET", "/v1/passkeys");

    assertEquals(200, listed.status());
    assertEquals(
        Map.of(
            "schema_version",
            1,
            "passkeys",
            List.of(passkey(vik, "Vik's Mac", FIRST, null, false)),
            "total",
            1),
        listed.map());
  }

  /** A credential that acts for no person holds no passkeys, and removes none. */
  @Test
  void aMachineCredentialHoldsNone() throws Exception {
    var maras = enroll("mara", "MacBook");
    enroll("mara", "iPhone");

    assertEquals(
        Map.of("schema_version", 1, "passkeys", List.of(), "total", 0),
        ask(machine, "GET", "/v1/passkeys").map());
    assertEquals(404, ask(machine, "DELETE", "/v1/passkeys/" + encoded(maras)).status());
    assertEquals(2, credentials.listForFde(idOf("mara")).size());
  }

  /**
   * Scenario 2: another person's id is not found, as an id that names nothing is, or one that is
   * not base64url, and the passkey stays.
   */
  @Test
  void anotherPersonsPasskeyIsNotFoundAndStays() throws Exception {
    var first = enroll("mara", "MacBook");
    enroll("mara", "iPhone");
    var theo = enroll("theo", "Theo's Mac");
    var mara = signIn(first);

    for (var id : List.of(encoded(theo), "bm90LWEtcGFzc2tleQ", "not+base64url=")) {
      var refused = ask(mara, "DELETE", "/v1/passkeys/" + id);

      assertEquals(404, refused.status(), id);
      assertEquals(
          refusal(
              "not_found",
              "No passkey of yours has that id.",
              "List your passkeys again.",
              List.of()),
          refused.map(),
          id);
    }
    assertEquals(1, credentials.listForFde(theoId).size(), "Theo's passkey stays");
  }

  /**
   * Scenario 3: removing a passkey another session signed in with ends that session at its next
   * request and leaves the caller's own serving.
   */
  @Test
  void removingAPasskeyEndsTheSessionsItSignedInAndNoOther() throws Exception {
    var first = enroll("mara", "MacBook");
    var second = enroll("mara", "iPhone");
    var mara = signIn(first);
    var elsewhere = signIn(second);
    assertEquals(200, ask(elsewhere, "GET", "/v1/whoami").status());

    var httpLog = Logger.getLogger("com.sun.net.httpserver");
    var warned = new ArrayList<String>();
    var listening =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            warned.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    httpLog.addHandler(listening);
    Answer removed;
    try {
      removed = ask(mara, "DELETE", "/v1/passkeys/" + encoded(second));
    } finally {
      httpLog.removeHandler(listening);
    }

    assertEquals(204, removed.status());
    assertEquals("", removed.body());
    assertEquals(List.of(), warned, "a 204 is sent with no body, so the server warns of none");
    assertEquals(403, ask(elsewhere, "GET", "/v1/whoami").status(), "the session it signed in");
    assertEquals(200, ask(mara, "GET", "/v1/whoami").status(), "the caller's own");
    assertEquals(
        List.of(encoded(first)),
        credentials.listForFde(idOf("mara")).stream()
            .map(credential -> Passkeys.encodeId(credential.credentialId()))
            .toList());
  }

  /** Scenario 4: the passkey the caller signed in with is not removed. */
  @Test
  void thePasskeyTheCallerSignedInWithStays() throws Exception {
    var first = enroll("mara", "MacBook");
    enroll("mara", "iPhone");
    var mara = signIn(first);

    var refused = ask(mara, "DELETE", "/v1/passkeys/" + encoded(first));

    assertEquals(409, refused.status());
    assertEquals(
        refusal(
            "conflict",
            "You are signed in with this passkey.",
            "Sign in with another passkey to remove this one.",
            List.of()),
        refused.map());
    assertEquals(2, credentials.listForFde(idOf("mara")).size());
    assertEquals(200, ask(mara, "GET", "/v1/whoami").status());
  }

  /**
   * Scenario 5: a person's only passkey is not removed, and when it is also the one they signed in
   * with, the last-one refusal is said.
   */
  @Test
  void aPersonsOnlyPasskeyStays() throws Exception {
    var only = enroll("mara", "MacBook");
    var mara = signIn(only);

    var refused = ask(mara, "DELETE", "/v1/passkeys/" + encoded(only));

    assertEquals(409, refused.status());
    assertEquals(
        refusal(
            "conflict",
            "This is your only passkey, so removing it would leave you no way to sign in.",
            "Enroll another passkey with a ticket from your admin, then remove this one.",
            List.of()),
        refused.map());
    assertEquals(1, credentials.listForFde(idOf("mara")).size());
  }

  /** Every other method on the paths answers as a path the API never served. */
  @Test
  void everyOtherMethodOnThePathsIsNotFound() throws Exception {
    var mara = signIn(enroll("mara", "MacBook"));

    for (var request :
        List.of(
            List.of("POST", "/v1/passkeys"),
            List.of("PUT", "/v1/passkeys/abc"),
            List.of("GET", "/v1/passkeys/abc"),
            List.of("DELETE", "/v1/passkeys/abc/def"))) {
      var answer = ask(mara, request.get(0), request.get(1));

      assertEquals(404, answer.status(), request.toString());
      assertEquals(
          refusal("not_found", "API endpoint was not found.", null, List.of()),
          answer.map(),
          request.toString());
    }
  }

  private TestAuthenticator enroll(String handle, String label) {
    var passkey = new TestAuthenticator(RP_ID);
    var ceremony = ceremonies.startRegistration(handle);
    var made = passkey.register(challengeOf(ceremony), ORIGIN);
    ceremonies.finishRegistration(
        ceremony.challengeId(), null, made.clientDataJson(), made.attestationObject(), label);
    return passkey;
  }

  private String signIn(TestAuthenticator passkey) {
    var login = ceremonies.startLogin();
    var assertion = passkey.assertResponse(challengeOf(login), ORIGIN);
    return ceremonies
        .finishLogin(
            login.challengeId(),
            assertion.credentialId(),
            assertion.clientDataJson(),
            assertion.authenticatorData(),
            assertion.signature(),
            null)
        .sessionToken();
  }

  private String sessionOf(String handle) {
    return new AuthSessionStore(db).create(idOf(handle), Duration.ofHours(1)).token();
  }

  private String idOf(String handle) {
    return new FdeStore(db).byHandle(handle).orElseThrow().id();
  }

  private static byte[] challengeOf(PasskeyCeremonies.Ceremony ceremony) {
    return Base64.getUrlDecoder().decode((String) ceremony.publicKey().get("challenge"));
  }

  private static String encoded(TestAuthenticator passkey) {
    return Passkeys.encodeId(passkey.credentialId());
  }

  private static Map<String, Object> passkey(
      TestAuthenticator passkey, String label, Instant created, Instant used, boolean current) {
    var shown = new LinkedHashMap<String, Object>();
    shown.put("id", encoded(passkey));
    shown.put("label", label);
    shown.put("created_at", DateTimeUtils.format(created));
    if (used != null) {
      shown.put("last_used_at", DateTimeUtils.format(used));
    }
    shown.put("current", current);
    return shown;
  }

  private static Map<String, Object> refusal(
      String code, String message, String action, List<Object> fieldErrors) {
    var error = new LinkedHashMap<String, Object>();
    error.put("code", code);
    error.put("message", message);
    if (action != null) {
      error.put("action", action);
    }
    error.put("field_errors", fieldErrors);
    return Map.of("schema_version", 1, "error", error);
  }

  private record Answer(int status, String body) {
    Map<String, Object> map() {
      return YamlUtil.parseMap(body);
    }
  }

  private Answer ask(String token, String method, String path) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .header("Authorization", "Bearer " + token)
            .method(method, HttpRequest.BodyPublishers.noBody());
    var response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(response.statusCode(), response.body());
  }

  private static final ShellExec NO_CONTAINERS =
      new ShellExec() {
        public Result exec(List<String> command) {
          return new Result(0, "[]", "");
        }

        public Result exec(List<String> command, Path workDir, Duration timeout) {
          return exec(command);
        }

        public boolean isDryRun() {
          return false;
        }
      };
}
