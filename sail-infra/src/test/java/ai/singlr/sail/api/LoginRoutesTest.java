/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The login doors through the real web router over the production operations and stores (HL5):
 * every person keeps their own logins, a viewer too; another person's are listed or removed only by
 * an admin; a credential that acts for no person keeps none; no answer, refusal or event holds a
 * value; each change is announced with its fingerprint.
 */
class LoginRoutesTest {

  private static final String TOKEN = "sk-ant-oat01-TOKEN_value-0001";
  private static final String KEY = "sk-ant-api03-KEY_value-0002";

  @TempDir Path tempDir;
  private final HttpClient client = HttpClient.newHttpClient();
  private final List<Event> events = new CopyOnWriteArrayList<>();
  private Sqlite db;
  private EventBus bus;
  private HttpServer server;
  private String ada;
  private String sam;
  private String obs;
  private String machine;

  @BeforeEach
  void aBoxWithAnAdminAMemberAViewerAndAMachine() throws Exception {
    db = Sqlite.open(tempDir.resolve("main.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    Acting.system(
        () -> {
          var fdes = new FdeStore(db);
          var tokens = new TokenStore(db);
          ada =
              tokens
                  .create("ada", "admin", fdes.add("ada", "Ada", "ada@x", "admin").id(), null)
                  .token();
          sam =
              tokens
                  .create("sam", "member", fdes.add("sam", "Sam", "sam@x", "member").id(), null)
                  .token();
          obs =
              tokens
                  .create("obs", "viewer", fdes.add("obs", "Obs", "obs@x", "viewer").id(), null)
                  .token();
          machine = tokens.create("ci", "member", null, null).token();
        });
    bus = new EventBus();
    bus.subscribe(
        new EventSubscriber() {
          @Override
          public String name() {
            return "logins";
          }

          @Override
          public Predicate<Event> filter() {
            return event -> event.type().startsWith("login_");
          }

          @Override
          public void onEvent(Event event) {
            events.add(event);
          }
        });
    var operations =
        TestControlPlane.on(
            OperationsFactory.create(
                db, NO_CONTAINERS, bus, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            tempDir,
            new SyncConfig(null, null, null, "box"));
    var auth = TestAuth.tokens(db);
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
    bus.close();
    db.close();
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

  private record Answer(int status, String body) {
    Map<String, Object> map() {
      return YamlUtil.parseMap(body);
    }
  }

  private Answer ask(String token, String method, String path, String json) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    var answer = new Answer(response.statusCode(), response.body());
    assertFalse(answer.body().contains(TOKEN), "no answer holds a value: " + answer.body());
    assertFalse(answer.body().contains(KEY), "no answer holds a value: " + answer.body());
    return answer;
  }

  private Answer store(String token, String name, String kind, String value) throws Exception {
    return ask(
        token,
        "PUT",
        "/v1/logins/claude-code/" + name,
        YamlUtil.dumpJson(Map.of("kind", kind, "value", value)));
  }

  @SuppressWarnings("unchecked")
  private static List<String> named(Answer listed) {
    return ((List<Map<String, Object>>) listed.map().get("logins"))
        .stream()
            .map(
                login ->
                    login.get("name")
                        + " "
                        + login.get("kind")
                        + (Boolean.TRUE.equals(login.get("default")) ? " default" : ""))
            .toList();
  }

  @SuppressWarnings("unchecked")
  @Test
  void aMemberStoresListsSwitchesAndRemovesTheirOwnLoginsSeeingNoValue() throws Exception {
    var stored = store(sam, "default", "token", TOKEN);

    assertEquals(200, stored.status(), stored.body());
    var login = (Map<String, Object>) stored.map().get("login");
    assertEquals("sam", login.get("fde"));
    assertEquals("default", login.get("name"));
    assertEquals(true, login.get("default"));
    assertEquals(8, login.get("fingerprint").toString().length());
    assertEquals(200, store(sam, "personal", "api-key", KEY).status());
    assertEquals(
        List.of("default token default", "personal api-key"),
        named(ask(sam, "GET", "/v1/logins", null)));

    var used = ask(sam, "POST", "/v1/logins/claude-code/personal:use", null);

    assertEquals(200, used.status(), used.body());
    assertEquals(
        List.of("default token", "personal api-key default"),
        named(ask(sam, "GET", "/v1/logins", null)));
    var fdes = new FdeStore(db);
    var samId = fdes.byHandle("sam").orElseThrow().id();
    assertEquals(
        KEY, fdes.logins().defaultFor(samId, Harnesses.of("claude-code")).orElseThrow().value());

    var removed = ask(sam, "DELETE", "/v1/logins/claude-code/default", null);

    assertEquals(200, removed.status(), removed.body());
    assertEquals(List.of("personal api-key default"), named(ask(sam, "GET", "/v1/logins", null)));
    var removedAgain = ask(sam, "DELETE", "/v1/logins/claude-code/default", null);
    assertEquals(200, removedAgain.status(), removedAgain.body());
    assertNull(removedAgain.map().get("login"));
    assertEquals(
        List.of("login_stored", "login_stored", "login_default", "login_removed"),
        events.stream().map(Event::type).toList());
    var first = events.getFirst();
    assertEquals(
        Map.of(
            "person", "sam",
            "harness", "claude-code",
            "name", "default",
            "kind", "token",
            "fingerprint", login.get("fingerprint")),
        first.data());
    assertEquals("sam", first.agent());
    assertFalse(events.toString().contains(TOKEN));
    assertFalse(events.toString().contains(KEY));
  }

  @Test
  void aViewerKeepsTheirOwnAndAnotherPersonsAreAnAdminsToListOrRemove() throws Exception {
    assertEquals(200, store(obs, "default", "token", TOKEN).status());
    assertEquals(200, store(sam, "default", "token", TOKEN).status());

    var refused = ask(sam, "GET", "/v1/logins?fde=obs", null);
    var refusedRemove = ask(sam, "DELETE", "/v1/logins/claude-code/default?fde=obs", null);

    assertEquals(403, refused.status());
    assertEquals("forbidden_admin_only", ((Map<?, ?>) refused.map().get("error")).get("code"));
    assertEquals(403, refusedRemove.status());
    assertEquals(
        List.of("default token default"), named(ask(ada, "GET", "/v1/logins?fde=sam", null)));
    assertEquals(200, ask(ada, "DELETE", "/v1/logins/claude-code/default?fde=sam", null).status());
    assertEquals(List.of(), named(ask(sam, "GET", "/v1/logins", null)));
    assertEquals(List.of("default token default"), named(ask(obs, "GET", "/v1/logins", null)));
    assertEquals("ada", events.getLast().agent(), "the removal is announced as the admin's");
    assertEquals("sam", events.getLast().data().get("person"));
    assertEquals(404, ask(ada, "GET", "/v1/logins?fde=nobody", null).status());
  }

  @Test
  void aCredentialForNoPersonKeepsNoLogins() throws Exception {
    var refused = store(machine, "default", "token", TOKEN);

    assertEquals(403, refused.status());
    assertEquals(
        "This credential acts for no person, so it keeps no logins.",
        ((Map<?, ?>) refused.map().get("error")).get("message"));
  }

  @Test
  void aValueOrNameBreakingItsRuleAndALoginNotStoredAreRefusedSayingSoAndNeverTheValue()
      throws Exception {
    var spaced = store(sam, "default", "token", "sk-ant-oat01 TOKEN_value-0001");
    var badName = store(sam, "Work", "token", TOKEN);
    var badKind = store(sam, "default", "session", TOKEN);
    var missing = ask(sam, "POST", "/v1/logins/claude-code/work:use", null);
    var plain = ask(sam, "POST", "/v1/logins/claude-code/work", null);

    assertEquals(422, spaced.status());
    assertEquals(
        "A login's value is printable ASCII with no space, quote, backslash, $ or backtick.",
        ((Map<?, ?>) spaced.map().get("error")).get("message"));
    assertFalse(spaced.body().contains("TOKEN_value"));
    assertEquals(422, badName.status());
    assertEquals(422, badKind.status());
    assertEquals(
        "Claude Code takes no login of kind 'session'; it takes token, api-key.",
        ((Map<?, ?>) badKind.map().get("error")).get("message"));
    assertEquals(404, missing.status());
    assertEquals(
        "No Claude Code login named 'work' is stored.",
        ((Map<?, ?>) missing.map().get("error")).get("message"));
    assertTrue(plain.status() >= 400, plain.body());
    assertTrue(events.isEmpty());
  }

  /** A login live runs started with goes on serving them; removing it says so, once. */
  @Test
  void removingALoginLiveRunsStartedWithSaysTheyFinishOnIt() throws Exception {
    assertEquals(200, store(sam, "work", "token", TOKEN).status());
    var runs = new RunStore(db);
    for (var i = 0; i < 2; i++) {
      var id = DateTimeUtils.newId().toString();
      Acting.system(
          () -> {
            runs.create(
                id, "acme", "auth", "sam", "build", "claude-code", "b", "t", null, null, "/l", "u");
            runs.recordLaunch(
                id,
                new RunStore.Launched(
                    null, null, null, new RunStore.RunLogin("work", "token"), null));
          });
    }

    var removed = ask(sam, "DELETE", "/v1/logins/claude-code/work", null);

    assertEquals("2 live runs finish on the old `work`.", removed.map().get("note"));
  }
}
