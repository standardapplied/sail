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
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A3 at both doors, on main, through the real HTTP server and the real socket router with the real
 * subscribers behind them: an event drives only what its sender may drive. A member's stop, failure
 * or review evidence for another member's spec, run or room never reaches the bus, so nothing
 * moves, launches, notifies or counts as evidence; a run a member pushed is no key to the spec it
 * names; a member is never taken at their word for what this box observed, so their event starts no
 * review and finishes no run; a type the rule does not list is refused from everyone; an owner's
 * retelling and an admin's report land; and what lands carries the server's clock, the publisher
 * the server authenticated, and the project and conversation of the run it names.
 */
class EventDoorTest {

  private static final SyncConfig MAIN = new SyncConfig("main", null, "root", "main-box");
  private static final String STOPPED = Event.WellKnownTypes.AGENT_SESSION_STOPPED;
  private static final Map<String, Object> RETOLD =
      Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_SYNC);

  private static final ShellExec SHELL =
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

  @TempDir Path tempDir;
  private Sqlite db;
  private EventBus bus;
  private SailOperations operations;
  private SailApiServer server;
  private SpecStore specs;
  private RunStore runs;
  private String adaToken;
  private String bobToken;
  private String rootToken;
  private final List<Event> reached = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    var fdes = new FdeStore(db);
    var root = fdes.add("root", null, null, "admin");
    var ada = fdes.add("ada", null, null, "member");
    var bob = fdes.add("bob", null, null, "member");
    var tokens = new TokenStore(db);
    rootToken = tokens.create("root", "admin", root.id(), null).token();
    adaToken = tokens.create("ada", "member", ada.id(), null).token();
    bobToken = tokens.create("bob", "member", bob.id(), null).token();
    specs = new SpecStore(db);
    runs = new RunStore(db);
    Acting.as(
        "bob",
        () -> {
          specs.create(spec("theirs", "bob"));
          new RoomStore(db).create(room("den", "bob"));
        });
    Acting.as("ada", () -> specs.create(spec("mine", "ada")));
    Acting.system(
        () -> {
          specs.compareAndSetStatus("theirs", SpecStatus.PENDING, SpecStatus.IN_PROGRESS);
          specs.compareAndSetStatus("mine", SpecStatus.PENDING, SpecStatus.IN_PROGRESS);
        });
    bus = new EventBus();
    operations =
        TestControlPlane.on(
            OperationsFactory.create(
                db, SHELL, "sail.yaml", bus, null, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            tempDir,
            MAIN);
    server =
        new SailApiServer(
            "127.0.0.1", 0, operations, TestAuth.sessions(db, MAIN), bus, null, null, null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.close();
    operations.close();
    bus.close();
    db.close();
  }

  @Test
  void aMembersForgedStopDoesNotMoveAnotherMembersSpec() throws Exception {
    bus.subscribe(new SpecLifecycleReactor(specs));

    var observed = publish(adaToken, event("theirs", STOPPED, watcherStop(null)));
    var retold = publish(adaToken, event("theirs", STOPPED, retoldStop(null)));

    assertRefused(observed, "forbidden_not_assignee", STOPPED, "'theirs' (owned by 'bob')");
    assertRefused(retold, "forbidden_not_assignee", STOPPED, "'theirs' (owned by 'bob')");
    assertEquals(SpecStatus.IN_PROGRESS, specs.findById("theirs").orElseThrow().status());
  }

  @Test
  void aMembersForgedStopDoesNotFinishARunOfAnotherMembersSpec() throws Exception {
    var run = reserve("theirs", null, "build");
    bus.subscribe(new RunTracker(runs, SyncScheduler.disabled(), () -> "root"));

    var stop = publish(adaToken, event("theirs", STOPPED, watcherStop(run)));
    var retold = publish(adaToken, event("theirs", STOPPED, retoldStop(run)));
    var completion =
        publish(adaToken, event("theirs", "agent_session_completed", Map.of("run_id", run)));

    assertRefused(stop, "forbidden_not_assignee", STOPPED, "run " + run + " of 'theirs'");
    assertRefused(retold, "forbidden_not_assignee", STOPPED, "run " + run + " of 'theirs'");
    assertRefused(completion, "forbidden", "agent_session_completed", "not one a client may");
    assertEquals("running", runs.findById(run).orElseThrow().status());
  }

  @Test
  void aMembersForgedStopOfARoomRunIsRefusedSoTheRoomGuardKeepsItsBaseline() throws Exception {
    var run = reserve(null, "den", DispatchGate.ROOM_ROLE);
    var data = new LinkedHashMap<String, Object>(watcherStop(run));
    data.put(Event.WellKnownData.RUN_ROLE, Event.WellKnownData.RUN_ROLE_ROOM);

    var named = publish(adaToken, event("den", STOPPED, data));
    var unnamed = publish(adaToken, event("den", STOPPED, watcherStop(null)));
    var retold = publish(adaToken, event("den", STOPPED, retoldStop(null)));

    assertRefused(named, "forbidden_not_assignee", STOPPED, "run " + run + " of 'den'");
    assertRefused(unnamed, "forbidden_not_assignee", STOPPED, "'den' (owned by 'bob')");
    assertRefused(retold, "forbidden_not_assignee", STOPPED, "'den' (owned by 'bob')");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "agent_failed",
        "review_stage_started",
        "review_stage_failed",
        "review_errored",
        "review_escalated"
      })
  void aMembersForgedEvidenceDoesNotSuppressTheReconcilersRescue(String type) throws Exception {
    var run = strandRootsSpec();
    bus.subscribe(new SpecStoreAuditPersister(new EventStore(db)));

    var observed = publish(adaToken, event("stranded", type, Map.of()));
    var retold = publish(adaToken, event("stranded", type, RETOLD));

    assertRefused(observed, "forbidden_not_assignee", type, "'stranded' (owned by 'root')");
    assertRefused(retold, "forbidden_not_assignee", type, "'stranded' (owned by 'root')");
    assertTrue(new EventStore(db).forSpecAndType("stranded", type).isEmpty());
    assertEquals(1, reconciler().sweep(), "stranded run " + run + " must still be rescued");
  }

  @Test
  void aMembersForgedNotificationTypesAreRefused() throws Exception {
    for (var type : List.of("spec_dispatched", "spec_restarted", "review_completed")) {
      for (var data : List.of(Map.<String, Object>of(), RETOLD)) {
        assertRefused(
            publish(adaToken, event("theirs", type, data)),
            "forbidden_not_assignee",
            type,
            "'theirs' (owned by 'bob')");
      }
    }
    for (var type : List.of("snapshot_created", "board_updated")) {
      assertRefused(
          publish(adaToken, event(null, type, RETOLD)),
          "forbidden_not_assignee",
          type,
          "speaks for this box");
    }
  }

  @Test
  void aRunDecidesWhatItsEventIsAboutWhateverSpecItsSenderNames() throws Exception {
    var run = reserveOn("ada", "mine", null, "build");
    var delivered = new CountDownLatch(1);
    bus.subscribe(BusTesting.latching(new SpecLifecycleReactor(specs), delivered));

    var response = publish(adaToken, event("theirs", "elsewhere", STOPPED, retoldStop(run)));

    var landed = landed(response);
    assertEquals("mine", landed.spec());
    assertEquals("acme", landed.project());
    BusTesting.awaitDelivery(delivered);
    assertEquals(SpecStatus.REVIEW, specs.findById("mine").orElseThrow().status());
    assertEquals(SpecStatus.IN_PROGRESS, specs.findById("theirs").orElseThrow().status());
  }

  @Test
  void aRunAMemberPushedIsNoKeyToAnotherMembersSpec() throws Exception {
    var forged = reserveOn("ada", "theirs", null, "build");
    bus.subscribe(new SpecLifecycleReactor(specs));

    var retold = publish(adaToken, event("mine", STOPPED, retoldStop(forged)));
    var observed = publish(adaToken, event("mine", STOPPED, watcherStop(forged)));

    assertRefused(retold, "forbidden_not_assignee", STOPPED, "of 'theirs' (owned by 'bob')");
    assertRefused(observed, "forbidden_not_assignee", STOPPED, "of 'theirs' (owned by 'bob')");
    assertEquals(SpecStatus.IN_PROGRESS, specs.findById("theirs").orElseThrow().status());
  }

  @Test
  void anOwnerIsNotTakenAtTheirWordForWhatThisBoxObserved() throws Exception {
    var local = reserve("theirs", null, "build");
    bus.subscribe(new RunTracker(runs, SyncScheduler.disabled(), () -> "root"));

    var unnamed = publish(bobToken, event("theirs", STOPPED, watcherStop(null)));
    var named = publish(bobToken, event("theirs", STOPPED, retoldStop(local)));

    assertRefused(unnamed, "forbidden_not_assignee", STOPPED, "reports what this box observed");
    assertRefused(named, "forbidden_not_assignee", STOPPED, "reports what this box observed");
    assertEquals("running", runs.findById(local).orElseThrow().status());
  }

  @Test
  void anOwnersRetellingAndAnAdminsReportLandAndDriveTheMachinery() throws Exception {
    var delivered = new CountDownLatch(2);
    bus.subscribe(BusTesting.latching(new SpecLifecycleReactor(specs), delivered));

    var owners = publish(bobToken, event("theirs", STOPPED, retoldStop(null)));
    var admins = publish(rootToken, event("mine", STOPPED, watcherStop(null)));

    assertEquals(new Event.Publisher("bob", "member", "api"), landed(owners).publisher());
    assertEquals(
        Event.WellKnownData.SOURCE_SYNC,
        landed(owners).data().get(Event.WellKnownData.SOURCE),
        "a retelling starts no review and finishes no run here");
    assertEquals(new Event.Publisher("root", "admin", "api"), landed(admins).publisher());
    BusTesting.awaitDelivery(delivered);
    assertEquals(SpecStatus.REVIEW, specs.findById("theirs").orElseThrow().status());
    assertEquals(SpecStatus.REVIEW, specs.findById("mine").orElseThrow().status());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "spec_status_changed",
        "agent_session_completed",
        "agent_log_chunk",
        "spec_engaged",
        "guardrail_triggered",
        "sync_degraded",
        "made_up"
      })
  void aTypeTheRuleDoesNotListIsRefusedFromEveryone(String type) throws Exception {
    for (var token : List.of(adaToken, rootToken)) {
      assertRefused(
          publish(token, event("mine", type, Map.of())), "forbidden", type, "not one a client");
    }
  }

  @Test
  void aMemberWithNoVoiceInAConversationCannotAnnounceAMessageInIt() throws Exception {
    var message = Acting.as("bob", () -> new MessageStore(db).append("den", "bob", "hi", null));
    var announcement =
        Map.<String, Object>of(
            "message_id",
            message.id(),
            Event.WellKnownData.SOURCE,
            Event.WellKnownData.SOURCE_SYNC);

    var response = publish(adaToken, event("mine", "spec_message_posted", announcement));

    assertRefused(
        response, "forbidden_not_assignee", "spec_message_posted", "'den' (owned by 'bob')");
  }

  @Test
  void anAnnouncementIsDecidedOnItsMessagesConversationNotOnARunItsSenderNames() throws Exception {
    var message = Acting.as("bob", () -> new MessageStore(db).append("den", "bob", "hi", null));
    var announcement = new LinkedHashMap<String, Object>(RETOLD);
    announcement.put("message_id", message.id());
    announcement.put(Event.WellKnownData.RUN_ID, reserveOn("ada", "mine", null, "build"));

    var response = publish(adaToken, event("mine", "spec_message_posted", announcement));

    assertRefused(
        response, "forbidden_not_assignee", "spec_message_posted", "'den' (owned by 'bob')");
  }

  @Test
  void whoMayPostInAConversationRetellsAMessageInItAndNothingElse() throws Exception {
    var message = Acting.as("bob", () -> new MessageStore(db).append("den", "bob", "hi", null));
    var asObserved = Map.<String, Object>of("message_id", message.id(), "preview", "forged");
    var retold = new LinkedHashMap<>(asObserved);
    retold.put(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_SYNC);

    var refused = publish(bobToken, event("den", "spec_message_posted", asObserved));
    assertRefused(refused, "forbidden_not_assignee", "spec_message_posted", "this box observed");
    var landed = landed(publish(bobToken, event("mine", "spec_message_posted", retold)));

    assertEquals("den", landed.spec());
    assertEquals("bob", landed.agent());
    assertEquals("hi", landed.data().get("preview"));
    assertEquals(new Event.Publisher("bob", "member", "api"), landed.publisher());
  }

  @Test
  void anAnnouncedMessageIsRebuiltFromTheStoredRowNeverFromTheBody() throws Exception {
    var message =
        Acting.as("bob", () -> new MessageStore(db).append("den", "bob", "ship it?", null, true));
    var forged =
        Map.<String, Object>of(
            "message_id", message.id(), "preview", "forged words", "question", false);

    var response =
        publish(rootToken, eventBy("root", "mine", "elsewhere", "spec_message_posted", forged));

    var landed = landed(response);
    assertEquals("bob", landed.agent());
    assertEquals("den", landed.spec());
    assertEquals("acme", landed.project());
    assertEquals("ship it?", landed.data().get("preview"));
    assertEquals(true, landed.data().get("question"));
    assertEquals(Event.WellKnownData.SOURCE_SYNC, landed.data().get(Event.WellKnownData.SOURCE));
    assertEquals(new Event.Publisher("root", "admin", "api"), landed.publisher());
  }

  @Test
  void anAnnouncementOfAMessageThisBoxDoesNotHoldIsRefused() throws Exception {
    var unknown =
        publish(
            rootToken,
            event(
                "den",
                "spec_message_posted",
                Map.of("message_id", DateTimeUtils.newId().toString())));
    var unnamed = publish(rootToken, event("den", "spec_message_posted", Map.of()));

    assertEquals(404, unknown.statusCode(), unknown.body());
    assertTrue(unknown.body().contains("'spec_message_posted'"), unknown.body());
    assertEquals(404, unnamed.statusCode(), unnamed.body());
    assertEquals(0L, bus.stats().published());
  }

  @Test
  void theServerStampsItsOwnClockAndThePublisherItAuthenticated() throws Exception {
    var persisted = new CountDownLatch(1);
    bus.subscribe(BusTesting.latching(new SpecStoreAuditPersister(new EventStore(db)), persisted));
    var offered =
        new LinkedHashMap<>(
            Event.of("acme", "mine", STOPPED, "root", "box", retoldStop(null)).toMap());
    offered.put("ts", "2999-01-01T00:00:00Z");
    offered.put("publisher", Map.of("handle", "root", "role", "admin", "lane", "system"));
    var before = Instant.now();

    var response = publish(adaToken, YamlUtil.dumpJson(offered));

    var landed = landed(response);
    assertFalse(landed.ts().isBefore(before.minusSeconds(1)), landed.ts().toString());
    assertFalse(landed.ts().isAfter(Instant.now().plusSeconds(1)), landed.ts().toString());
    assertEquals(new Event.Publisher("ada", "member", "api"), landed.publisher());
    BusTesting.awaitDelivery(persisted);
    var row = new EventStore(db).forSpecAndType("mine", STOPPED).getFirst();
    assertFalse(Instant.parse(row.timestamp()).isAfter(Instant.now().plusSeconds(1)));
    assertEquals(
        Map.of("handle", "ada", "role", "member", "lane", "api"),
        YamlUtil.parseMap(row.publisher()));
    var history = http("GET", "/v1/events?spec=mine", adaToken, "");
    assertTrue(history.body().contains("\"publisher\": {\"handle\": \"ada\""), history.body());
  }

  @Test
  void aStoredEventNoClientPublishedIsServedWithNoPublisher() throws Exception {
    new EventStore(db)
        .insert(
            new EventStore.EventRow(
                0, "2026-01-01T00:00:00Z", "spec_dispatched", "acme", "mine", "sail", "box", "{}"));

    var history = http("GET", "/v1/events?spec=mine", adaToken, "");

    assertEquals(200, history.statusCode(), history.body());
    assertFalse(history.body().contains("publisher"), history.body());
  }

  @Test
  void aRunsHooksNarrateItsOwnRunOverTheSocket() throws Exception {
    var credential = reserveReserved("mine", null, "build").credential();
    var run = runs.findByCredential(credential).orElseThrow();
    var delivered = new CountDownLatch(4);
    bus.subscribe(BusTesting.latching(recorder(), delivered));

    for (var type :
        List.of(
            Event.WellKnownTypes.AGENT_SESSION_STARTED,
            Event.WellKnownTypes.AGENT_STOP_NUDGED,
            Event.WellKnownTypes.AGENT_TOOL_STARTED,
            Event.WellKnownTypes.AGENT_TOOL_FINISHED)) {
      var hook =
          Event.of("elsewhere", "theirs", type, "forged", "h", Map.of("run_id", "another-run"));
      assertEquals(202, socket(credential, hook.toJsonLine()).status(), type);
    }

    BusTesting.awaitDelivery(delivered);
    assertEquals(4, reached.size());
    for (var event : reached) {
      assertEquals("mine", event.spec());
      assertEquals("acme", event.project());
      assertEquals(run.id(), event.data().get(Event.WellKnownData.RUN_ID));
      assertEquals(run.principal(), event.agent());
      assertEquals(new Event.Publisher(run.principal(), "member", "agent"), event.publisher());
    }
  }

  @Test
  void aRoomRunsHooksNarrateItsRunAndNameNoConversationTheSenderLeftOut() throws Exception {
    Acting.system(() -> new RoomStore(db).create(room("lobby", "root")));
    var credential = reserveReserved(null, "lobby", DispatchGate.ROOM_ROLE).credential();
    var run = runs.findByCredential(credential).orElseThrow();
    var delivered = new CountDownLatch(2);
    bus.subscribe(BusTesting.latching(recorder(), delivered));

    var hook = Event.of("acme", "mine", Event.WellKnownTypes.AGENT_TOOL_STARTED, "claude", "h");
    assertEquals(202, socket(credential, hook.toJsonLine()).status());
    var stop = landed(publish(rootToken, event("mine", STOPPED, watcherStop(run.id()))));

    BusTesting.awaitDelivery(delivered);
    assertNull(reached.getFirst().spec(), "the socket names a run's spec, and this run has none");
    assertEquals(
        new Event.Publisher(run.principal(), "viewer", "room"), reached.getFirst().publisher());
    assertEquals("lobby", stop.spec(), "a conversation its sender names is the run's own");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "agent_session_stopped",
        "agent_session_completed",
        "agent_cancelled",
        "agent_failed",
        "review_stage_started",
        "review_escalated",
        "spec_dispatched",
        "spec_message_posted",
        "board_updated",
        "spec_status_changed",
        "made_up"
      })
  void aRunPublishesNothingButItsHooksOverTheSocket(String type) throws Exception {
    var credential = reserveReserved("mine", null, "build").credential();

    var response = socket(credential, Event.of("acme", "mine", type, "claude", "h").toJsonLine());

    assertEquals(403, response.status(), type);
    assertTrue(response.body().get("error").toString().contains("'" + type + "'"), type);
    assertEquals(0L, bus.stats().published());
  }

  private void assertRefused(HttpResponse<String> response, String code, String type, String what) {
    assertEquals(403, response.statusCode(), response.body());
    assertTrue(response.body().contains("\"" + code + "\""), response.body());
    assertTrue(response.body().contains("'" + type + "'"), response.body());
    assertTrue(response.body().contains(what), response.body());
    assertEquals(0L, bus.stats().published(), "a refused event never reaches the bus");
  }

  @SuppressWarnings("unchecked")
  private static Event landed(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    return Event.fromMap((Map<String, Object>) YamlUtil.parseMap(response.body()).get("event"));
  }

  private EventSubscriber recorder() {
    return new EventSubscriber() {
      @Override
      public String name() {
        return "recorder";
      }

      @Override
      public Predicate<Event> filter() {
        return EventSubscriber.all();
      }

      @Override
      public void onEvent(Event event) {
        reached.add(event);
      }
    };
  }

  private static Map<String, Object> watcherStop(String run) {
    return stop(Event.WellKnownData.SOURCE_WATCHER, run);
  }

  private static Map<String, Object> retoldStop(String run) {
    return stop(Event.WellKnownData.SOURCE_SYNC, run);
  }

  private static Map<String, Object> stop(String source, String run) {
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.SOURCE, source);
    data.put(Event.WellKnownData.EXIT_CODE, 0);
    if (run != null) {
      data.put(Event.WellKnownData.RUN_ID, run);
    }
    return data;
  }

  private static String event(String spec, String type, Map<String, Object> data) {
    return event(spec, "acme", type, data);
  }

  private static String event(String spec, String project, String type, Map<String, Object> data) {
    return eventBy("watcher", spec, project, type, data);
  }

  private static String eventBy(
      String agent, String spec, String project, String type, Map<String, Object> data) {
    return Event.of(project, spec, type, agent, "box", data).toJsonLine();
  }

  private HttpResponse<String> publish(String credential, String json) throws Exception {
    return http("POST", "/v1/events", credential, json);
  }

  private HttpResponse<String> http(String method, String path, String credential, String json)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Authorization", "Bearer " + credential)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(json))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private ApiResponse socket(String credential, String json) {
    return new LocalApiRouter(operations)
        .handle(
            new LocalApiRequest(
                "POST",
                "/v1/events",
                Map.of(),
                Map.of("authorization", "Bearer " + credential),
                json.getBytes(StandardCharsets.UTF_8)));
  }

  private String reserve(String specId, String roomId, String role) {
    return reserveOn("root", specId, roomId, role);
  }

  private String reserveOn(String box, String specId, String roomId, String role) {
    var reserved = reserveReserved(box, specId, roomId, role);
    return runs.findByCredential(reserved.credential()).orElseThrow().id();
  }

  private RunStore.Reservation.Reserved reserveReserved(String specId, String roomId, String role) {
    return reserveReserved("root", specId, roomId, role);
  }

  private RunStore.Reservation.Reserved reserveReserved(
      String box, String specId, String roomId, String role) {
    var id = DateTimeUtils.newId().toString();
    return (RunStore.Reservation.Reserved)
        Acting.system(
            () ->
                runs.reserveDispatch(
                    id,
                    "acme",
                    specId,
                    roomId,
                    box,
                    role,
                    List.of(),
                    "claude-code",
                    "feat/x",
                    "do it",
                    "/tmp/" + id + ".log",
                    "sail-agent-" + id,
                    Duration.ofHours(1)));
  }

  private String strandRootsSpec() {
    return Acting.system(
        () -> {
          specs.create(spec("stranded", "root"));
          specs.compareAndSetStatus("stranded", SpecStatus.PENDING, SpecStatus.IN_PROGRESS);
          var run = reserve("stranded", null, "build");
          runs.transition(run, "running", "stopped", 0);
          new EventStore(db)
              .insert(
                  new EventStore.EventRow(
                      0,
                      Instant.now().toString(),
                      STOPPED,
                      "acme",
                      "stranded",
                      "watcher",
                      "box",
                      "{\"source\":\"watcher\",\"run_id\":\"" + run + "\",\"exit_code\":0}"));
          return run;
        });
  }

  private MissedStopReconciler reconciler() {
    return new MissedStopReconciler(
        specs,
        runs,
        new EventStore(db),
        new ReviewStore(db),
        new EventBus(),
        (project, run, unit) -> false,
        () -> "root",
        () -> Instant.now().plus(Duration.ofMinutes(10)));
  }

  private static RoomStore.RoomRow room(String id, String owner) {
    return new RoomStore.RoomRow(id, "acme", id, owner, null, null, null, null, null, null);
  }

  private static SpecStore.SpecRow spec(String id, String assignee) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        id,
        SpecStatus.PENDING,
        assignee,
        null,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }
}
