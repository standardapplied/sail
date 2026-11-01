/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ConnectEnvironment;
import ai.singlr.sail.engine.HostAccess;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A scoped FDE reads exactly its granted projects through main's API. The box first holds project
 * {@code alpha} alone, and every read route is asked as {@code sam}, whom main scoped to it. Then
 * everything of project {@code bravo} arrives — its catalog row and container, a spec, rooms,
 * messages, a run, a review, a shared file, events, a parked conflict, an FDE granted it — beside
 * an event of no project, and every route is asked again: {@code sam} is answered byte for byte as
 * before, so nothing of {@code bravo} shows, counts and next-up included, and a route addressed to
 * something of {@code bravo} still answers what it answered when that did not exist.
 *
 * <p>The routes are a table, held complete from the operations side: every method of {@link
 * Operations} is either reached by a route of the table or declared one no {@code GET} reaches, so
 * a read added to the API without a row here fails the sweep.
 */
class ScopedReadsTest {

  private static final String A_RUN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String B_RUN = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String A_FILE = "docs/alpha.md";
  private static final String B_FILE = "docs/bravo.md";

  /** The id bravo's review is asked under where the sweep must not name the real one. */
  private static final String NO_SUCH_REVIEW = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";

  /** The routes that address nothing: lists, and what the box says of itself. */
  private static final List<String> UNADDRESSED =
      List.of(
          "/v1/health",
          "/v1/whoami",
          "/v1/fdes",
          "/v1/agents",
          "/v1/sync",
          "/v1/conflicts",
          "/v1/events/recent",
          "/v1/events/stats",
          "/v1/specs",
          "/v1/specs/board",
          "/v1/rooms",
          "/v1/runs",
          "/v1/projects");

  /** The unaddressed routes on which an unrestricted caller is shown something of bravo. */
  private static final Set<String> SHOW_EVERY_PROJECT =
      Set.of(
          "/v1/fdes",
          "/v1/conflicts",
          "/v1/events/recent",
          "/v1/specs",
          "/v1/specs/board",
          "/v1/rooms",
          "/v1/runs",
          "/v1/projects");

  /** The routes that address a project or something of one, asked of alpha and of bravo. */
  private static final List<String> ADDRESSED =
      List.of(
          "/v1/conflicts/{spec}?type=spec",
          "/v1/conflicts/{spec}?type=spec&template=true",
          "/v1/events?spec={spec}",
          "/v1/specs?project={project}",
          "/v1/specs/board?project={project}",
          "/v1/specs/{spec}",
          "/v1/specs/{spec}/content",
          "/v1/specs/{spec}/reviews",
          "/v1/specs/{spec}/history",
          "/v1/rooms?project={project}",
          "/v1/rooms/{room}",
          "/v1/rooms/{room}/members",
          "/v1/rooms/{room}/messages",
          "/v1/rooms/{spec}/messages",
          "/v1/reviews/{review}",
          "/v1/runs?project={project}",
          "/v1/runs?spec={spec}",
          "/v1/runs/{run}",
          "/v1/runs/{run}/log",
          "/v1/runs/{run}/stream",
          "/v1/projects/{project}",
          "/v1/projects/{project}/specs",
          "/v1/projects/{project}/specs/{spec}",
          "/v1/projects/{project}/agent",
          "POST /v1/projects/{project}/agent/report",
          "/v1/projects/{project}/connect",
          "/v1/projects/{project}/ports",
          "/v1/projects/{project}/snapshots",
          "/v1/projects/{project}/files",
          "/v1/projects/{project}/files/{file}");

  /**
   * Asked of bravo alone: alpha's run is this box's, so its stream would start a real {@code incus}
   * tail, while bravo's ran elsewhere and is refused before any process starts.
   */
  private static final Set<String> NEVER_ASKED_OF_ALPHA = Set.of("/v1/runs/{run}/stream");

  /** The operations no {@code GET} reaches: the writes, and what only the local socket asks. */
  private static final Set<String> NOT_READ_OVER_HTTP =
      Set.of(
          "sync",
          "skills",
          "resolveConflict",
          "approveReview",
          "dismissFinding",
          "dispatch",
          "restoreSnapshot",
          "deleteSnapshot",
          "stopRun",
          "createFollowupSpec",
          "restoreGlobalSpec",
          "pruneSpecs",
          "postRoomMessage",
          "addRoomMember",
          "removeRoomMember",
          "createRoom",
          "deleteRoom",
          "createGlobalSpec",
          "updateGlobalSpec",
          "deleteGlobalSpec",
          "setGlobalSpecContent",
          "publishEvent",
          "runForCredential",
          "runActor",
          "boxActorForCredential",
          "runInbox",
          "ackRunMessages",
          "recordRunSession",
          "recordRoomConversation");

  /** The id bravo's run is asked under where a write is addressed to a run nobody holds. */
  private static final String NO_SUCH_RUN = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";

  /**
   * The writes that address something by id — method, route and body — each asked of bravo's and of
   * something nobody holds. Held complete like the reads: every operation no {@code GET} reaches is
   * reached by a write of this table or by a birth the test makes beside it, or is declared one
   * HTTP never addresses to a row.
   */
  private static final List<List<String>> ADDRESSED_WRITES =
      List.of(
          List.of("PUT", "/v1/specs/{spec}", "{\"title\": \"taken over\"}"),
          List.of("DELETE", "/v1/specs/{spec}", ""),
          List.of("PUT", "/v1/specs/{spec}/content", "{\"body\": \"b\"}"),
          List.of("POST", "/v1/specs/{spec}/restore", "{\"rev\": \"1-main\"}"),
          List.of("POST", "/v1/specs/{spec}/followup", "{}"),
          List.of("POST", "/v1/specs:prune", "{\"ids\": [\"{spec}\"]}"),
          List.of("POST", "/v1/rooms/{room}/messages", "{\"body\": \"hello\"}"),
          List.of("POST", "/v1/rooms/{spec}/messages", "{\"body\": \"hello\"}"),
          List.of("POST", "/v1/rooms/{room}/members", "{\"agent\": \"claude-code\"}"),
          List.of("POST", "/v1/rooms/{spec}/members", "{\"agent\": \"claude-code\"}"),
          List.of("DELETE", "/v1/rooms/{room}/members", ""),
          List.of("DELETE", "/v1/rooms/{spec}/members", ""),
          List.of("DELETE", "/v1/rooms/{room}", ""),
          List.of("POST", "/v1/reviews/{review}/approve", ""),
          List.of("POST", "/v1/reviews/{review}/dismiss/a-finding", ""),
          List.of("POST", "/v1/runs/{run}/stop", ""),
          List.of("POST", "/v1/conflicts/{spec}/resolve?type=spec", "{\"strategy\": \"mine\"}"),
          List.of("POST", "/v1/events", event("{project}", "\"spec\": \"{spec}\", ", "")),
          List.of("POST", "/v1/events", event("alpha", "\"spec\": \"{spec}\", ", "")),
          List.of("POST", "/v1/events", event("alpha", "", ", \"run_id\": \"{run}\"")),
          List.of("PUT", "/v1/projects/{project}/files/{file}", "sneaked in"),
          List.of("DELETE", "/v1/projects/{project}/files/{file}", ""),
          List.of("POST", "/v1/projects/{project}/dispatch", "{}"),
          List.of("POST", "/v1/projects/{project}/snapshots/a-label/restore", ""),
          List.of("DELETE", "/v1/projects/{project}/snapshots/a-label", ""));

  /** A retold {@code spec_dispatched} addressed to {@code project} and what {@code about} names. */
  private static String event(String project, String spec, String run) {
    return "{\"ts\": \"2026-10-13T00:00:00Z\", \"project\": \""
        + project
        + "\", "
        + spec
        + "\"type\": \"spec_dispatched\", \"agent\": \"sam\", \"host\": \"laptop\","
        + " \"data\": {\"source\": \"sync\""
        + run
        + "}}";
  }

  /** A retold announcement of message {@code id}, addressed to alpha. */
  private static String announcement(String id) {
    return "{\"ts\": \"2026-10-13T00:00:00Z\", \"project\": \"alpha\","
        + " \"type\": \"spec_message_posted\", \"agent\": \"sam\", \"host\": \"laptop\","
        + " \"data\": {\"source\": \"sync\", \"message_id\": \""
        + id
        + "\"}}";
  }

  /** The operations HTTP never addresses to a row: a round of sync, and the local socket's. */
  private static final Set<String> NEVER_ADDRESSED_OVER_HTTP =
      Set.of(
          "sync",
          "skills",
          "runForCredential",
          "runActor",
          "boxActorForCredential",
          "runInbox",
          "ackRunMessages",
          "recordRunSession",
          "recordRoomConversation");

  @TempDir Path tempDir;

  private final List<String> containers = new CopyOnWriteArrayList<>();
  private final Set<String> reached = new ConcurrentSkipListSet<>();
  private final HttpClient client = HttpClient.newHttpClient();
  private Sqlite db;
  private AuditPersister recent;
  private EventBus bus;
  private HttpServer server;
  private SailOperations operations;
  private String sam;
  private String ada;
  private String alphaReview;

  @BeforeEach
  void aBoxHoldingAlphaAlone() throws Exception {
    db = Sqlite.open(tempDir.resolve("main.db"));
    new SchemaManager(db).migrate();
    recent = new AuditPersister(tempDir.resolve("events.jsonl"), 64);
    bus = new EventBus();
    Acting.system(
        () -> {
          var fdes = new FdeStore(db);
          var tokens = new TokenStore(db);
          ada =
              tokens
                  .create(
                      "ada-laptop", "admin", fdes.add("ada", "Ada", "ada@x", "admin").id(), null)
                  .token();
          sam =
              tokens
                  .create(
                      "sam-laptop", "member", fdes.add("sam", "Sam", "sam@x", "member").id(), null)
                  .token();
          fdes.restrict("sam");
          fdes.grant("sam", "alpha");
          alphaReview = arrive("alpha", A_RUN, A_FILE, "main", "sam", 1);
        });
    var specs = new SpecStore(db);
    var runs = new RunStore(db);
    operations =
        TestControlPlane.standalone(
            new SailOperations(
                    new BoxShell(),
                    ProjectReader.ofCatalog(new ProjectStore(db)),
                    (command, logPath) -> 4242L,
                    bus,
                    recent,
                    specs,
                    new ReviewStore(db),
                    runs,
                    new ProjectStore(db),
                    () -> new ConnectEnvironment("203.0.113.7", "ops", true),
                    SyncScheduler.disabled(),
                    new FdeStore(db),
                    SessionYield.NONE)
                .useMessages(new MessageStore(db))
                .useRooms(new RoomStore(db))
                .useEvents(new EventStore(db)),
            db,
            tempDir.resolve("projects"));
    var auth = TestAuth.tokens(db);
    var streamer = new AgentLogStreamer(auth, runs::findById, specs::findById, () -> "main");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        new ApiRouter(recording(operations), auth, new RateLimitGate(), streamer, () -> "main"));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  @AfterEach
  void close() {
    server.stop(0);
    bus.close();
    db.close();
  }

  @Test
  void aScopedReaderIsAnsweredTheSameWhetherOrNotAnotherProjectExists() throws Exception {
    var withoutBravo = sweep(sam, NO_SUCH_REVIEW);

    var bravoReview = Acting.system(this::bravoArrives);
    var withBravo = sweep(sam, bravoReview);
    var unrestricted = sweep(ada, bravoReview);

    for (var route : withBravo.keySet()) {
      assertEquals(withoutBravo.get(route), withBravo.get(route), route);
    }
    for (var route : withBravo.keySet()) {
      if (!route.startsWith("bravo ")) {
        assertEquals("200", withBravo.get(route).substring(0, 3), route + " served sam");
        assertEquals("200", unrestricted.get(route).substring(0, 3), route + " served ada");
      }
      if (route.startsWith("bravo ") || SHOW_EVERY_PROJECT.contains(route)) {
        assertNotEquals(withBravo.get(route), unrestricted.get(route), route + " shows ada bravo");
      }
    }
    assertEquals(
        """
        404 {"schema_version": 1, "error": {"code": "spec_not_found", "message": "Spec 'bravo-spec' was not found.", "field_errors": []}}""",
        withBravo.get("bravo /v1/specs/{spec}"));
    assertEquals(
        """
        404 {"schema_version": 1, "error": {"code": "room_not_found", "message": "Room 'bravo-room' was not found.", "field_errors": []}}""",
        withBravo.get("bravo /v1/rooms/{room}"));
    assertEquals(
        """
        404 {"schema_version": 1, "error": {"code": "run_not_found", "message": "No run '%s'.", "field_errors": []}}"""
            .formatted(B_RUN),
        withBravo.get("bravo /v1/runs/{run}"));
    assertEquals(
        """
        404 {"schema_version": 1, "error": {"code": "not_found", "message": "Review '{review}' not found.", "field_errors": []}}""",
        withBravo.get("bravo /v1/reviews/{review}"));
    assertEquals(
        """
        404 {"code": "run_not_found", "message": "No run '%s'."}"""
            .formatted(B_RUN),
        withBravo.get("bravo /v1/runs/{run}/stream"));
    assertEquals(readsOverHttp(), new TreeSet<>(reached));
  }

  @Test
  void nothingOfBravoIsNamedToAScopedReaderAndAllOfItToAnUnrestrictedOne() throws Exception {
    var bravoReview = Acting.system(this::bravoArrives);
    var hidden = List.of("bravo", B_RUN, bravoReview, "unplaced");

    var scoped = sweep(sam, NO_SUCH_REVIEW);
    var unrestricted = sweep(ada, NO_SUCH_REVIEW);

    for (var route : scoped.keySet()) {
      if (!route.startsWith("bravo ")) {
        var answer = scoped.get(route).toLowerCase(Locale.ROOT);
        assertEquals(List.of(), hidden.stream().filter(answer::contains).toList(), route);
      }
    }
    var shown = String.join("\n", unrestricted.values()).toLowerCase(Locale.ROOT);
    assertEquals(hidden, hidden.stream().filter(shown::contains).toList());
  }

  @Test
  void aScopedCallersWriteOutsideItsScopeIsAnsweredAsOneOnWhatDoesNotExist() throws Exception {
    var bravoReview = Acting.system(this::bravoArrives);
    var before = entries();

    assertEquals(
        """
        404 {"schema_version": 1, "error": {"code": "not_found", "message": "Project 'bravo' was not found.", "action": "Ask an admin on main for the project: fde grant <handle> <project>.", "remedy": [{"text": "Ask an admin on main for the project: "}, {"run": "sail", "verb": "fde grant", "args": ["<handle>", "<project>"]}, {"text": "."}], "field_errors": []}}""",
        send(
            sam,
            "POST",
            "/v1/specs",
            "{\"id\": \"sneak\", \"project\": \"bravo\", \"title\": \"t\"}"));
    assertEquals(
        """
        409 {"schema_version": 1, "error": {"code": "conflict", "message": "Spec 'bravo-spec' already exists.", "action": "Pick another spec id, or edit it with: spec update bravo-spec", "remedy": [{"text": "Pick another spec id, or edit it with: "}, {"run": "sail", "verb": "spec update", "args": ["bravo-spec"]}], "field_errors": []}}""",
        send(
            sam,
            "POST",
            "/v1/specs",
            "{\"id\": \"bravo-spec\", \"project\": \"alpha\", \"title\": \"t\"}"));
    var told = new ArrayList<String>();
    for (var write : ADDRESSED_WRITES) {
      var onBravo =
          send(
              sam,
              write.get(0),
              addressed(write.get(1), "bravo", B_RUN, bravoReview, B_FILE),
              addressed(write.get(2), "bravo", B_RUN, bravoReview, B_FILE));
      var onNothing =
          send(
                  sam,
                  write.get(0),
                  addressed(write.get(1), "ghost", NO_SUCH_RUN, NO_SUCH_REVIEW, B_FILE),
                  addressed(write.get(2), "ghost", NO_SUCH_RUN, NO_SUCH_REVIEW, B_FILE))
              .replace("ghost", "bravo")
              .replace(NO_SUCH_RUN, B_RUN)
              .replace(NO_SUCH_REVIEW, bravoReview);
      if (!onBravo.equals(onNothing) || !onBravo.startsWith("4")) {
        told.add(write.get(0) + " " + write.get(1) + "\n  " + onBravo + "\n  " + onNothing);
      }
    }
    assertEquals(List.of(), told, "a write on bravo's is answered as one on what nobody holds");
    var said =
        db.queryOne(
                "SELECT id FROM room_messages WHERE room_id = 'bravo-room' LIMIT 1",
                row -> row.text(0))
            .orElseThrow();
    var announced = send(sam, "POST", "/v1/events", announcement(said));
    assertEquals("403", announced.substring(0, 3), announced);
    assertEquals(
        send(sam, "POST", "/v1/events", announcement(NO_SUCH_RUN)).replace(NO_SUCH_RUN, said),
        announced,
        "a message in bravo's room is announced as one nobody holds");
    assertEquals(
        """
        404 {"schema_version": 1, "error": {"code": "not_found", "message": "Project 'bravo' was not found.", "action": "Ask an admin on main for the project: fde grant <handle> <project>.", "remedy": [{"text": "Ask an admin on main for the project: "}, {"run": "sail", "verb": "fde grant", "args": ["<handle>", "<project>"]}, {"text": "."}], "field_errors": []}}""",
        send(
            sam,
            "POST",
            "/v1/rooms",
            "{\"id\": \"sneak-room\", \"project\": \"bravo\", \"title\": \"t\"}"));
    assertEquals(
        "404", send(sam, "PUT", "/v1/projects/bravo/files/notes.md", "sneaked in").substring(0, 3));
    assertEquals(before, entries(), "nothing of any refused write was recorded");

    assertEquals(
        "201",
        send(
                sam,
                "POST",
                "/v1/specs",
                "{\"id\": \"mine\", \"project\": \"alpha\", \"title\": \"t\"}")
            .substring(0, 3));
    assertEquals(
        "200",
        send(ada, "PUT", "/v1/specs/bravo-spec", "{\"title\": \"an admin edits it\"}")
            .substring(0, 3));
    var writes = new TreeSet<>(NOT_READ_OVER_HTTP);
    writes.removeAll(NEVER_ADDRESSED_OVER_HTTP);
    assertEquals(
        writes,
        reached.stream().filter(writes::contains).collect(Collectors.toCollection(TreeSet::new)));
  }

  @Test
  void aSpecIsNeitherBornIntoNorOverARoomOutsideTheScopeNorToldThatRoomsProject() throws Exception {
    Acting.system(this::bravoArrives);
    var before = entries();

    var intoBravo =
        send(
            sam,
            "POST",
            "/v1/specs",
            "{\"id\": \"sneak\", \"project\": \"alpha\", \"title\": \"t\","
                + " \"room_id\": \"bravo-room\"}");
    var intoNothing =
        send(
            sam,
            "POST",
            "/v1/specs",
            "{\"id\": \"sneak\", \"project\": \"alpha\", \"title\": \"t\","
                + " \"room_id\": \"ghost-room\"}");

    assertEquals(
        """
        404 {"schema_version": 1, "error": {"code": "room_not_found", "message": "Room 'bravo-room' was not found.", "field_errors": []}}""",
        intoBravo);
    assertEquals(intoNothing.replace("ghost", "bravo"), intoBravo);
    assertEquals(
        """
        409 {"schema_version": 1, "error": {"code": "conflict", "message": "Room 'bravo-room' already exists, and a spec's id is reserved for its own room.", "action": "Pick another spec id.", "field_errors": []}}""",
        send(
            sam,
            "POST",
            "/v1/specs",
            "{\"id\": \"bravo-room\", \"project\": \"alpha\", \"title\": \"t\"}"));
    assertEquals(before, entries(), "nothing of any refused birth was recorded");
    assertEquals(
        """
        422 {"schema_version": 1, "error": {"code": "invalid_request", "message": "Room 'bravo-room' belongs to project 'bravo', not 'alpha'.", "action": "A spec is born only into a room of its own project.", "field_errors": []}}""",
        send(
            ada,
            "POST",
            "/v1/specs",
            "{\"id\": \"sneak\", \"project\": \"alpha\", \"title\": \"t\","
                + " \"room_id\": \"bravo-room\"}"),
        "a caller who sees the room is still told why it is not the spec's");
  }

  @Test
  void aSpecMovedIntoTheScopeShowsNothingOfTheRoomAndRunItLeftInAnotherProject() throws Exception {
    Acting.system(this::bravoArrives);
    var run = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    assertEquals(
        "201",
        send(
                ada,
                "POST",
                "/v1/specs",
                "{\"id\": \"wander\", \"project\": \"bravo\", \"title\": \"t\"}")
            .substring(0, 3));
    Acting.system(
        () -> {
          var runs = new RunStore(db);
          runs.create(
              run,
              "bravo",
              "wander",
              "elsewhere",
              "build",
              "claude-code",
              "b",
              "t",
              null,
              null,
              "/log",
              "u");
          runs.complete(run, "completed", 0);
        });
    assertEquals(
        "200", send(ada, "PUT", "/v1/specs/wander", "{\"project\": \"alpha\"}").substring(0, 3));
    assertEquals("200", send(ada, "PUT", "/v1/specs/wander", "{\"wake\": \"on\"}").substring(0, 3));
    assertEquals(
        "bravo", new RoomStore(db).findById("wander").orElseThrow().project(), "the room stayed");

    for (var read : List.of("/v1/specs/wander", "/v1/specs?project=alpha", "/v1/specs")) {
      var asSam = ask(sam, read);
      assertEquals("200", asSam.substring(0, 3), read);
      assertEquals(true, asSam.contains("\"id\": \"wander\""), read + ": " + asSam);
      assertEquals(false, asSam.contains("\"wake\""), read + ": " + asSam);
      assertEquals(true, ask(ada, read).contains("\"wake\": \"on\""), read);
    }
    assertEquals(false, ask(sam, "/v1/specs/wander").contains(run));
    assertEquals(true, ask(ada, "/v1/specs/wander").contains(run));

    var asked =
        Acting.system(
            () ->
                new MessageStore(db)
                    .append(
                        "wander",
                        "claude-code/" + run,
                        "Which?",
                        null,
                        MessageStore.Mark.QUESTION));
    for (var read : List.of("/v1/specs/wander", "/v1/specs?project=alpha", "/v1/specs")) {
      assertEquals(false, ask(sam, read).contains(asked.id()), read);
      assertEquals(true, ask(ada, read).contains(asked.id()), read);
    }
    for (var board : List.of("/v1/specs/board", "/v1/specs/board?project=alpha")) {
      assertEquals(true, ask(sam, board).contains("\"needs_reply\": 0"), board);
      assertEquals(true, ask(ada, board).contains("\"needs_reply\": 1"), board);
    }

    var retold = send(ada, "POST", "/v1/events", announcement(asked.id()));
    assertEquals("20", retold.substring(0, 2), retold);
    assertEquals(
        true,
        retold.contains("\"project\": \"bravo\""),
        "a message is announced under the project it is filed in, its room's: " + retold);
    assertEquals(
        send(sam, "POST", "/v1/events", announcement(NO_SUCH_RUN)).replace(NO_SUCH_RUN, asked.id()),
        send(sam, "POST", "/v1/events", announcement(asked.id())),
        "and to sam it is a message nobody holds");
    var toBravo = announcement(asked.id()).replace("\"alpha\"", "\"bravo\"");
    assertEquals(
        send(sam, "POST", "/v1/events", toBravo.replace(asked.id(), NO_SUCH_RUN))
            .replace(NO_SUCH_RUN, asked.id()),
        send(sam, "POST", "/v1/events", toBravo),
        "whichever project the announcement is addressed to");
  }

  @Test
  void aSpecMovedIntoTheScopeIsNeitherSeatedInNorRestoredToWhatItLeftInAnotherProject()
      throws Exception {
    Acting.system(this::bravoArrives);
    assertEquals(
        "201",
        send(
                ada,
                "POST",
                "/v1/specs",
                "{\"id\": \"wander\", \"project\": \"bravo\", \"title\": \"t\"}")
            .substring(0, 3));
    assertEquals(
        "200", send(ada, "PUT", "/v1/specs/wander", "{\"project\": \"alpha\"}").substring(0, 3));
    var inBravo =
        new SpecStore(db)
            .history("wander").stream()
                .filter(
                    revision ->
                        "bravo".equals(YamlUtil.parseMap(revision.snapshot()).get("project")))
                .findFirst()
                .orElseThrow()
                .rev();
    var before = entries();

    for (var seat :
        List.of(List.of("POST", "{\"agent\": \"claude-code\"}"), List.of("DELETE", ""))) {
      assertEquals(
          """
          404 {"schema_version": 1, "error": {"code": "room_not_found", "message": "Room 'wander' was not found.", "field_errors": []}}""",
          send(sam, seat.get(0), "/v1/rooms/wander/members", seat.get(1)),
          seat.get(0) + ": the spec is sam's to see, the room it lives in is not");
    }
    assertEquals(
        send(sam, "POST", "/v1/specs/wander/restore", "{\"rev\": \"1-nobody\"}")
            .replace("1-nobody", inBravo),
        send(sam, "POST", "/v1/specs/wander/restore", "{\"rev\": \"" + inBravo + "\"}"),
        "a revision made in bravo is one sam's history of the spec does not hold");
    assertEquals(before, entries(), "nothing of any refused write was recorded");
    assertEquals("200", ask(ada, "/v1/rooms/wander/members").substring(0, 3), "ada sees the room");
  }

  @Test
  void aRunWhoseSpecLeftTheScopeNeverSaysWhoOwnsThatSpecNow() throws Exception {
    Acting.system(this::bravoArrives);
    var run = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    Acting.system(
        () ->
            new RunStore(db)
                .create(
                    run,
                    "alpha",
                    "bravo-spec",
                    "main",
                    "build",
                    "claude-code",
                    "b",
                    "t",
                    null,
                    null,
                    "/log",
                    "u"));
    assertEquals(
        true, ask(sam, "/v1/runs/" + run).contains("bravo-spec"), "the run is sam's to see");

    for (var door :
        List.of(
            List.of("GET", "/v1/runs/" + run + "/log"),
            List.of("GET", "/v1/runs/" + run + "/stream"),
            List.of("POST", "/v1/runs/" + run + "/stop"))) {
      var refused = send(sam, door.get(0), door.get(1), "");
      assertEquals("403", refused.substring(0, 3), door + ": " + refused);
      assertEquals(false, refused.contains("ada"), door + ": " + refused);
    }
  }

  @Test
  void aSpecMovedIntoTheScopeIsNotItsOwnersToPruneWhileWorkOfItStaysInAnotherProject()
      throws Exception {
    var bravoReview = Acting.system(this::bravoArrives);
    for (var change :
        List.of("\"project\": \"alpha\"", "\"assignee\": \"sam\"", "\"status\": \"archived\"")) {
      assertEquals(
          "200", send(ada, "PUT", "/v1/specs/bravo-spec", "{" + change + "}").substring(0, 3));
    }
    var before = entries();

    for (var dryRun : List.of(true, false)) {
      assertEquals(
          """
          409 {"schema_version": 1, "error": {"code": "spec_not_prunable", "message": "Pruning spec 'bravo-spec' would erase work outside your project scope.", "action": "Ask an admin to prune it.", "field_errors": []}}""",
          send(
              sam,
              "POST",
              "/v1/specs:prune",
              "{\"ids\": [\"bravo-spec\"], \"dry_run\": " + dryRun + "}"),
          "dry run " + dryRun);
    }

    assertEquals(before, entries(), "nothing was erased, and nothing rehearsed was kept");
    assertEquals(true, new RunStore(db).findById(B_RUN).isPresent(), "bravo's run stays");
    assertEquals(true, new ReviewStore(db).findReview(bravoReview).isPresent(), "and its review");
    var erased =
        send(ada, "POST", "/v1/specs:prune", "{\"ids\": [\"bravo-spec\"], \"dry_run\": false}");
    assertEquals("200", erased.substring(0, 3), erased);
    assertEquals(false, new RunStore(db).findById(B_RUN).isPresent(), "an admin prunes all of it");
    for (var step :
        List.of(
            List.of(
                "POST", "/v1/specs", "{\"id\": \"own\", \"project\": \"alpha\", \"title\": \"t\"}"),
            List.of("PUT", "/v1/specs/own", "{\"status\": \"archived\"}"),
            List.of("POST", "/v1/specs:prune", "{\"ids\": [\"own\"], \"dry_run\": false}"))) {
      var done = send(sam, step.get(0), step.get(1), step.get(2));
      assertEquals("20", done.substring(0, 2), step + ": a spec wholly in alpha is sam's: " + done);
    }
    assertEquals(true, new ChangeLog(db).isErased("spec", "own"));
  }

  @Test
  void aSpecMovedIntoTheScopeBringsNoneOfTheReviewsWrittenInTheProjectItLeft() throws Exception {
    var bravoReview = Acting.system(this::bravoArrives);
    var reviews = new ReviewStore(db);
    Acting.system(
        () -> {
          reviews.addFinding(
              reviews.createStage(bravoReview, "security", "agent"),
              new Finding(
                  "bravo-confidential",
                  Finding.Severity.HIGH,
                  Finding.Category.SECURITY,
                  "Bravo.java",
                  1,
                  1,
                  "Bravo confidential details",
                  "d",
                  "e",
                  new Finding.Suggestion("", "", ""),
                  1.0,
                  Finding.Resolution.OPEN,
                  null,
                  null,
                  null));
          reviews.pass(bravoReview, () -> {});
        });
    assertEquals(
        "200",
        send(ada, "PUT", "/v1/specs/bravo-spec", "{\"project\": \"alpha\"}").substring(0, 3));
    assertEquals(
        "201",
        send(
                ada,
                "POST",
                "/v1/specs",
                "{\"id\": \"unreviewed\", \"project\": \"alpha\", \"title\": \"t\"}")
            .substring(0, 3));

    assertEquals(
        true, ask(sam, "/v1/specs/bravo-spec").contains("\"project\": \"alpha\""), "sam's spec");
    for (var door :
        List.of(
            List.of("GET", "/v1/reviews/{review}", ""),
            List.of("POST", "/v1/reviews/{review}/approve", ""),
            List.of("POST", "/v1/reviews/{review}/dismiss/bravo-confidential", ""))) {
      assertEquals(
          send(sam, door.get(0), door.get(1).replace("{review}", NO_SUCH_REVIEW), door.get(2))
              .replace(NO_SUCH_REVIEW, bravoReview),
          send(sam, door.get(0), door.get(1).replace("{review}", bravoReview), door.get(2)),
          door + ": a review written in bravo is one sam's box does not hold");
    }
    assertEquals(
        """
        200 {"schema_version": 1, "spec_id": "bravo-spec", "reviews": []}""",
        ask(sam, "/v1/specs/bravo-spec/reviews"));
    assertEquals(true, ask(ada, "/v1/specs/bravo-spec/reviews").contains(bravoReview));
    assertEquals(false, ask(sam, "/v1/specs/bravo-spec").contains("open_findings"));
    assertEquals(true, ask(ada, "/v1/specs/bravo-spec").contains("\"open_findings\": 1"));
    assertEquals(
        send(sam, "POST", "/v1/specs/unreviewed/followup", "{}")
            .replace("unreviewed", "bravo-spec"),
        send(sam, "POST", "/v1/specs/bravo-spec/followup", "{}"),
        "a spec whose reviews sam does not hold is, to sam, one never reviewed");
    assertEquals(false, ask(sam, "/v1/specs").contains("followup"), "no follow-up was drafted");

    Acting.as("ada", () -> reviews.supersedeForSpec("bravo-spec"));
    assertEquals(
        "404",
        ask(sam, "/v1/reviews/" + bravoReview).substring(0, 3),
        "rewritten after the move, the review is still filed where it was written");
    assertEquals("200", ask(ada, "/v1/reviews/" + bravoReview).substring(0, 3));
  }

  @Test
  void aSpecMovedIntoTheScopeBringsNoneOfTheTalkHeldInTheProjectItLeft() throws Exception {
    Acting.system(this::bravoArrives);
    var messages = new MessageStore(db);
    var asked =
        Acting.system(
            () ->
                messages.append(
                    "bravo-spec",
                    "claude-code/" + B_RUN,
                    "Bravo's?",
                    null,
                    MessageStore.Mark.QUESTION));
    assertEquals(
        "200",
        send(ada, "PUT", "/v1/specs/bravo-spec", "{\"project\": \"alpha\", \"assignee\": \"sam\"}")
            .substring(0, 3));
    assertEquals(
        "201",
        send(
                ada,
                "POST",
                "/v1/specs",
                "{\"id\": \"quiet\", \"project\": \"alpha\", \"title\": \"t\"}")
            .substring(0, 3));
    assertEquals(
        Optional.of("bravo"), new ChangeLog(db).headProject("message", asked.id()), "it stayed");

    for (var page : List.of("", "?limit=1", "?after=" + asked.id(), "?before=" + asked.id())) {
      assertEquals(
          ask(sam, "/v1/rooms/quiet/messages" + page).replace("quiet", "bravo-spec"),
          ask(sam, "/v1/rooms/bravo-spec/messages" + page),
          page + ": to sam the conversation is one nobody has spoken in");
    }
    var all = ask(ada, "/v1/rooms/bravo-spec/messages");
    assertEquals(true, all.contains("Talk in the bravo spec") && all.contains(asked.id()), all);
    for (var read : List.of("/v1/specs/bravo-spec", "/v1/specs?project=alpha", "/v1/specs")) {
      var asSam = ask(sam, read);
      assertEquals(false, asSam.contains(asked.id()), read + ": " + asSam);
      assertEquals(
          false, asSam.contains(DateTimeUtils.format(asked.createdAt())), read + ": " + asSam);
      assertEquals(true, ask(ada, read).contains(asked.id()), read);
    }

    var said = send(sam, "POST", "/v1/rooms/bravo-spec/messages", "{\"body\": \"Said in alpha\"}");
    assertEquals("201", said.substring(0, 3), said);
    var asSam = ask(sam, "/v1/rooms/bravo-spec/messages?limit=2");
    assertEquals(true, asSam.contains("Said in alpha") && asSam.contains("\"total\": 1"), asSam);
    assertEquals(true, ask(ada, "/v1/rooms/bravo-spec/messages").contains("\"total\": 3"));
    assertEquals(
        false,
        ask(sam, "/v1/specs/bravo-spec").contains("needs_reply"),
        "a person's answer sam can read does not turn up the question sam cannot");

    var nobodys = DateTimeUtils.newId().toString();
    var answer = "{\"body\": \"An answer\", \"reply_to\": \"%s\"}";
    assertEquals(
        send(sam, "POST", "/v1/rooms/bravo-spec/messages", answer.formatted(nobodys)),
        send(sam, "POST", "/v1/rooms/bravo-spec/messages", answer.formatted(asked.id())),
        "to sam the question is one nobody asked");
    var answered = send(ada, "POST", "/v1/rooms/bravo-spec/messages", answer.formatted(asked.id()));
    assertEquals("201", answered.substring(0, 3), answered);
    assertEquals(
        true,
        ask(sam, "/v1/rooms/bravo-spec/messages").contains("\"total\": 1"),
        "an answer is filed with its question, in the project sam does not hold");
    assertEquals(true, ask(ada, "/v1/rooms/bravo-spec/messages").contains("\"total\": 4"));
  }

  @Test
  void aRunIsDeliveredNothingOfAProjectItsFdeNoLongerHolds() throws Exception {
    var runs = new RunStore(db);
    var messages = new MessageStore(db);
    var local = new LocalApiRouter(operations);
    var run = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    var reserved =
        Acting.system(
            () ->
                runs.reserveDispatch(
                    run,
                    "alpha",
                    "alpha-spec",
                    "sam",
                    "build",
                    List.of(),
                    "claude-code",
                    "b",
                    "t",
                    "/log",
                    "u"));
    var bearer =
        Map.of(
            "authorization", "Bearer " + ((RunStore.Reservation.Reserved) reserved).credential());
    var talk = messages.list("alpha-spec", null, 1).getFirst();
    var inbox = new LocalApiRequest("GET", "/v1/run/messages", Map.of(), bearer, new byte[0]);

    var held = local.handle(inbox);
    assertEquals(200, held.status());
    assertEquals(true, held.body().toString().contains("Talk in the alpha spec"), "" + held.body());

    Acting.system(() -> new FdeStore(db).revoke("sam", "alpha"));

    var gone =
        "404 {schema_version=1, error={code=run_not_found, message=Run '"
            + run
            + "' was not found., field_errors=[]}}";
    for (var asked :
        List.of(
            inbox,
            form(bearer, "/v1/run/messages", "delivered=" + talk.id()),
            form(bearer, "/v1/run/session", "session_id=a-session"))) {
      var answer = local.handle(asked);
      assertEquals(gone, answer.status() + " " + answer.body(), asked.method() + asked.path());
    }
    var room =
        local.handle(
            new LocalApiRequest(
                "GET", "/v1/specs/alpha-spec/messages", Map.of(), bearer, new byte[0]));
    assertEquals(
        "404 {schema_version=1, error={code=room_not_found, message=Room 'alpha-spec' was not"
            + " found., field_errors=[]}}",
        room.status() + " " + room.body());
    assertEquals(Set.of(), runs.deliveredMessageIds(run));
    assertEquals(null, runs.findById(run).orElseThrow().sessionId());

    Acting.system(() -> new FdeStore(db).grant("sam", "alpha"));
    assertEquals(held.body(), local.handle(inbox).body(), "granted again, it is delivered again");
  }

  private static LocalApiRequest form(Map<String, String> bearer, String path, String body) {
    return new LocalApiRequest(
        "POST", path, Map.of(), bearer, body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aRunOnASpecMovedIntoTheScopeIsDeliveredNoneOfTheTalkItLeftInAnotherProject()
      throws Exception {
    Acting.system(this::bravoArrives);
    var runs = new RunStore(db);
    var messages = new MessageStore(db);
    var left = messages.list("bravo-spec", null, 1).getFirst();
    assertEquals(
        "200",
        send(ada, "PUT", "/v1/specs/bravo-spec", "{\"project\": \"alpha\", \"assignee\": \"sam\"}")
            .substring(0, 3));
    var here = Acting.system(() -> messages.append("bravo-spec", "ada", "Said in alpha", null));
    var run = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    Acting.system(
        () ->
            runs.create(
                run,
                "alpha",
                "bravo-spec",
                "sam",
                "build",
                "claude-code",
                "b",
                "t",
                null,
                null,
                "/log",
                "u"));
    var sams = operations.runActor(runs.findById(run).orElseThrow()).orElseThrow();

    var inbox = Acting.by(sams, () -> operations.runInbox(run));
    var acked = Acting.by(sams, () -> operations.ackRunMessages(run, List.of(left.id())));
    var missing = Acting.by(sams, () -> operations.ackRunMessages(run, List.of(NO_SUCH_RUN)));

    assertEquals(
        List.of(here.id()),
        ((Result.Success<RunInboxResponse>) inbox)
            .value().messages().stream().map(SpecMessageView::id).toList());
    assertEquals(
        missing, acked, "a message filed under bravo is, to the run, one its room does not hold");
    assertEquals(
        "delivered must name messages on spec 'bravo-spec'.",
        ((Result.Failure<RunAckResponse>) acked).errorMessage());
    assertEquals(Set.of(), runs.deliveredMessageIds(run));
    assertEquals(
        List.of(left.id(), here.id()),
        ((Result.Success<RunInboxResponse>) Acting.system(() -> operations.runInbox(run)))
            .value().messages().stream().map(SpecMessageView::id).toList(),
        "the box's own machinery is still delivered all of it");
  }

  @Test
  void aScopedOwnersWriteOnItsSpecRewritesNoReviewFiledOutsideItsScope() throws Exception {
    var bravoReview = Acting.system(this::bravoArrives);
    var reviews = new ReviewStore(db);
    Acting.system(
        () -> {
          reviews.addFinding(
              reviews.createStage(bravoReview, "security", "agent"),
              new Finding(
                  "bravo-confidential",
                  Finding.Severity.HIGH,
                  Finding.Category.SECURITY,
                  "Bravo.java",
                  1,
                  1,
                  "Bravo confidential details",
                  "d",
                  "e",
                  new Finding.Suggestion("", "", ""),
                  1.0,
                  Finding.Resolution.OPEN,
                  null,
                  null,
                  null));
          reviews.pass(bravoReview, () -> {});
        });
    assertEquals(
        "200",
        send(ada, "PUT", "/v1/specs/bravo-spec", "{\"project\": \"alpha\", \"assignee\": \"sam\"}")
            .substring(0, 3));
    var written = new ChangeLog(db).history("review", bravoReview).size();

    var done = send(sam, "PUT", "/v1/specs/bravo-spec", "{\"status\": \"done\"}");

    assertEquals("200", done.substring(0, 3), done);
    assertEquals(
        Finding.Resolution.OPEN,
        reviews.findFinding("bravo-confidential").orElseThrow().resolution(),
        "what a review sam cannot read still holds open is not sam's to close");
    assertEquals(written, new ChangeLog(db).history("review", bravoReview).size());

    for (var status : List.of("review", "done")) {
      assertEquals(
          "200",
          send(ada, "PUT", "/v1/specs/bravo-spec", "{\"status\": \"" + status + "\"}")
              .substring(0, 3));
    }
    assertEquals(
        Finding.Resolution.SHIPPED,
        reviews.findFinding("bravo-confidential").orElseThrow().resolution(),
        "an admin, who reads the review, closes it");
  }

  @Test
  void aRoomThatCannotBeDeletedForItsSpecsNamesOnlyTheOnesItsReaderSees() throws Exception {
    Acting.system(this::bravoArrives);
    for (var spec : List.of("seen", "unseen")) {
      assertEquals(
          "201",
          send(
                  ada,
                  "POST",
                  "/v1/specs",
                  "{\"id\": \""
                      + spec
                      + "\", \"project\": \"alpha\", \"title\": \"t\","
                      + " \"room_id\": \"alpha-room\"}")
              .substring(0, 3));
    }
    assertEquals(
        "200", send(ada, "PUT", "/v1/specs/unseen", "{\"project\": \"bravo\"}").substring(0, 3));

    assertEquals(
        """
        409 {"schema_version": 1, "error": {"code": "conflict", "message": "Room 'alpha-room' holds 1 spec(s).", "action": "Delete or re-home its specs first: seen", "field_errors": []}}""",
        send(sam, "DELETE", "/v1/rooms/alpha-room", ""),
        "the specs counted are the ones the caller sees");
    assertEquals(
        """
        409 {"schema_version": 1, "error": {"code": "conflict", "message": "Room 'alpha-room' holds 2 spec(s).", "action": "Delete or re-home its specs first: seen, unseen", "field_errors": []}}""",
        send(ada, "DELETE", "/v1/rooms/alpha-room", ""));
    assertEquals(
        "200", send(ada, "PUT", "/v1/specs/seen", "{\"project\": \"bravo\"}").substring(0, 3));
    assertEquals(
        """
        409 {"schema_version": 1, "error": {"code": "conflict", "message": "Room 'alpha-room' holds work outside your project scope.", "action": "Ask an admin to re-home its specs: none is in a project this FDE is granted.", "field_errors": []}}""",
        send(sam, "DELETE", "/v1/rooms/alpha-room", ""),
        "a room emptied of what the caller sees still holds what it does not, uncounted");
  }

  @Test
  void aRunOnMainSeesNoMoreThanTheScopedFdeItActsFor() throws Exception {
    var runs = new RunStore(db);
    var operations =
        new SailOperations(
            new BoxShell(),
            ProjectReader.ofCatalog(new ProjectStore(db)),
            null,
            null,
            new SpecStore(db),
            new ReviewStore(db),
            runs,
            new ProjectStore(db),
            null,
            new FdeStore(db),
            SessionYield.NONE);

    var run = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    Acting.system(
        () ->
            runs.create(
                run,
                "alpha",
                "alpha-spec",
                "sam",
                "build",
                "claude-code",
                "b",
                "t",
                null,
                null,
                "/log",
                "u"));

    var sams = operations.runActor(runs.findById(run).orElseThrow()).orElseThrow();

    assertEquals("sam", sams.owner());
    assertEquals(ProjectScope.of(List.of("alpha")), sams.scope());
  }

  @Test
  void aPageOfEventsIsAsLongAsItsLimitOfWhatTheReaderSees() throws Exception {
    var recentBefore = ask(sam, "/v1/events/recent?limit=1");
    var specBefore = ask(sam, "/v1/events?spec=alpha-spec&limit=1");

    Acting.system(this::bravoArrives);

    assertEquals(
        List.of(recentBefore, specBefore),
        List.of(
            ask(sam, "/v1/events/recent?limit=1"), ask(sam, "/v1/events?spec=alpha-spec&limit=1")),
        "a busy project outside the scope, and an event of no project, take no room in the page");
  }

  @Test
  void aReplyFiledInAnotherProjectIsAnnouncedUnderThatProjectNotTheRoomsCurrentOne()
      throws Exception {
    Acting.system(this::bravoArrives);
    var left = new MessageStore(db).list("bravo-spec", null, 1).getFirst();
    assertEquals(
        "200",
        send(ada, "PUT", "/v1/specs/bravo-spec", "{\"project\": \"alpha\"}").substring(0, 3));
    var announced = new CopyOnWriteArrayList<Event>();
    var delivered = new CountDownLatch(1);
    try (var subscription =
        bus.subscribe(
            new EventSubscriber() {
              @Override
              public String name() {
                return "audit";
              }

              @Override
              public Predicate<Event> filter() {
                return event -> Event.WellKnownTypes.SPEC_MESSAGE_POSTED.equals(event.type());
              }

              @Override
              public void onEvent(Event event) {
                announced.add(event);
                delivered.countDown();
              }
            })) {
      var answered =
          send(
              ada,
              "POST",
              "/v1/rooms/bravo-spec/messages",
              "{\"body\": \"Bravo's secret answer\", \"reply_to\": \"" + left.id() + "\"}");
      assertEquals("201", answered.substring(0, 3), answered);
      BusTesting.awaitDelivery(delivered);
    }
    var event = announced.getFirst();
    var samsStream =
        SseHandler.parseFilter(
            URI.create("http://x/v1/events/stream"),
            new Actor("sam", Role.MEMBER, Actor.Lane.API)
                .withScope(ProjectScope.of(List.of("alpha"))));

    assertEquals(
        "bravo", event.project(), "the answer is filed with its question: " + event.toJsonLine());
    assertFalse(samsStream.test(event), "sam's stream never carries it");
    assertFalse(
        ask(sam, "/v1/rooms/bravo-spec/messages").contains("Bravo's secret answer"),
        "and sam's page of the room does not hold it");
  }

  @Test
  void aSpecMovedIntoTheScopeIsItsOwnersToDeleteAndToWake() throws Exception {
    Acting.system(this::bravoArrives);
    assertEquals(
        "201",
        send(
                ada,
                "POST",
                "/v1/specs",
                "{\"id\": \"wander\", \"project\": \"bravo\", \"title\": \"t\","
                    + " \"assignee\": \"sam\"}")
            .substring(0, 3));
    assertEquals(
        "200", send(ada, "PUT", "/v1/specs/wander", "{\"project\": \"alpha\"}").substring(0, 3));

    var woken = send(sam, "PUT", "/v1/specs/wander", "{\"wake\": \"on\"}");
    var deleted = send(sam, "DELETE", "/v1/specs/wander", "");

    assertEquals(
        """
        404 {"schema_version": 1, "error": {"code": "room_not_found", "message": "Room 'wander' was not found.", "field_errors": []}}""",
        woken,
        "the wake lives on the room the spec left behind, which is not there for its owner");
    assertEquals("200", deleted.substring(0, 3), deleted);
    assertTrue(
        new RoomStore(db).findById("wander").isPresent(),
        "the identity room it left in bravo stays, for an admin or retention");
  }

  @Test
  void aRunNarratesItsOwnLifecycleAfterAnAdminMovedItsSpecOutOfTheScope() throws Exception {
    Acting.system(this::bravoArrives);
    var runs = new RunStore(db);
    var local = new LocalApiRouter(operations);
    var run = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    var reserved =
        Acting.system(
            () ->
                runs.reserveDispatch(
                    run,
                    "alpha",
                    "alpha-spec",
                    "sam",
                    "build",
                    List.of(),
                    "claude-code",
                    "b",
                    "t",
                    "/log",
                    "u"));
    var bearer =
        Map.of(
            "authorization", "Bearer " + ((RunStore.Reservation.Reserved) reserved).credential());
    var narrated =
        new LocalApiRequest(
            "POST",
            "/v1/events",
            Map.of(),
            bearer,
            ("{\"v\": 1, \"id\": 0, \"ts\": \"2026-10-13T00:00:00Z\", \"project\": \"alpha\","
                    + " \"spec\": \"alpha-spec\", \"type\": \"agent_tool_started\","
                    + " \"agent\": \"claude-code/"
                    + run
                    + "\", \"host\": \"main\", \"data\": {}}")
                .getBytes(StandardCharsets.UTF_8));

    var before = local.handle(narrated);
    assertEquals(202, before.status(), "" + before.body());

    assertEquals(
        "200",
        send(ada, "PUT", "/v1/specs/alpha-spec", "{\"project\": \"bravo\"}").substring(0, 3));
    var after = local.handle(narrated);

    assertEquals(202, after.status(), "the run is filed under alpha still: " + after.body());
  }

  private long entries() {
    return db.queryOne("SELECT count(*) FROM change_log", row -> row.integer(0)).orElseThrow();
  }

  private String send(String token, String method, String route, String body) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + route))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return response.statusCode() + " " + response.body();
  }

  @Test
  void theTerminalDoorRefusesARoomOutsideTheFdesScopeAsOneThatDoesNotExist() throws Exception {
    Acting.system(this::bravoArrives);
    var door = new HostAccess(db, TestAuth.roles(db));

    var outside =
        assertThrows(
            IOException.class,
            () -> door.admit("bravo-room", "bravo", new PtyIdentity("sam", false)));
    var misaddressed =
        assertThrows(
            IOException.class,
            () -> door.admit("bravo-room", "alpha", new PtyIdentity("sam", false)));
    var missing =
        assertThrows(
            IOException.class,
            () -> door.admit("ghost-room", "bravo", new PtyIdentity("sam", false)));

    assertEquals("Room 'bravo-room' was not found.", outside.getMessage());
    assertEquals("Room 'bravo-room' was not found.", misaddressed.getMessage());
    assertEquals("Room 'ghost-room' was not found.", missing.getMessage());
    door.admit("alpha-room", "alpha", new PtyIdentity("sam", false));
    door.admit("bravo-room", "bravo", new PtyIdentity("ada", true));
  }

  /**
   * Everything of {@code project} reaches the box: its definition and container, a pending spec
   * assigned to {@code owner} with a body, a room with a message and an agent's open question, talk
   * in the spec's own room before that room's row exists, a finished run on {@code node}, a review,
   * a shared file, a stored event and a recent one, and a parked conflict on the spec. Returns the
   * review's id.
   */
  private String arrive(
      String project, String run, String file, String node, String owner, int priority) {
    var spec = project + "-spec";
    var room = project + "-room";
    new ProjectStore(db)
        .upsert(project, "name: " + project + "\nssh:\n  user: dev\nagent:\n  type: claude-code\n");
    containers.add(project);
    var specs = new SpecStore(db);
    specs.create(
        new SpecStore.SpecRow(
            spec,
            project,
            "The " + project + " work",
            SpecStatus.PENDING,
            owner,
            null,
            null,
            null,
            null,
            priority,
            null,
            null,
            null,
            null,
            List.of(),
            List.of()),
        "The body of " + project,
        "");
    new RoomStore(db)
        .create(
            new RoomStore.RoomRow(
                room,
                project,
                "The " + project + " room",
                owner,
                null,
                null,
                null,
                null,
                null,
                null));
    var messages = new MessageStore(db);
    messages.append(room, owner, "A word about " + project, null);
    messages.append(
        room,
        "claude-code/" + run,
        "A question about " + project + "?",
        null,
        MessageStore.Mark.QUESTION);
    messages.append(spec, owner, "Talk in the " + project + " spec", null);
    var runs = new RunStore(db);
    runs.create(
        run,
        project,
        spec,
        node,
        "build",
        "claude-code",
        "feat/" + project,
        "do " + project,
        123,
        null,
        "/home/dev/.sail/runs/" + run + "/agent.log",
        "sail-agent-" + run);
    runs.complete(run, "completed", 0);
    var review = new ReviewStore(db).createReview(spec, 1);
    new FileStore(db)
        .put(
            project,
            file,
            new ByteArrayInputStream(("shared by " + project).getBytes(StandardCharsets.UTF_8)),
            0644);
    new EventStore(db)
        .insert(
            new EventStore.EventRow(
                0,
                Instant.parse("2026-10-13T00:00:00Z"),
                "spec_dispatched",
                project,
                spec,
                "sail",
                "main",
                "{}"));
    recent.onEvent(Event.of(project, spec, "spec_dispatched", "sail", "main"));
    var held = specs.comparableSnapshot(spec);
    var theirs = new LinkedHashMap<>(held);
    theirs.put("title", "The " + project + " work, as main has it");
    new SyncConflicts(db)
        .record(
            "spec",
            spec,
            YamlUtil.dumpJson(held),
            YamlUtil.dumpJson(held),
            YamlUtil.dumpJson(theirs),
            "9-main",
            "main",
            List.of("title"));
    return review;
  }

  /**
   * Bravo arrives, ready before alpha's spec, with an FDE granted it alone, an event of the box
   * itself and a stored event on alpha's spec that names no project. Returns its review's id.
   */
  private String bravoArrives() {
    var fdes = new FdeStore(db);
    fdes.add("bea", "Bea", "bea@x", "member");
    fdes.restrict("bea");
    fdes.grant("bea", "bravo");
    recent.onEvent(SyncTransitionEvents.health("main@unplaced", false, "unreachable", "x", "main"));
    new EventStore(db)
        .insert(
            new EventStore.EventRow(
                0,
                Instant.parse("2026-10-13T00:00:01Z"),
                "unplaced",
                null,
                "alpha-spec",
                "sail",
                "main",
                "{}"));
    return arrive("bravo", B_RUN, B_FILE, "elsewhere", "ada", 9);
  }

  /**
   * Every route of the table as {@code token} is answered it, {@code "<status> <body>"} by route:
   * the addressed ones once for alpha and once for bravo, under the project and the route's
   * template. Bravo's review is {@code bravoReview}, which reads as {@code {review}} in an answer,
   * so a sweep made before that review existed compares with one made after.
   */
  private Map<String, String> sweep(String token, String bravoReview) throws Exception {
    var answers = new LinkedHashMap<String, String>();
    for (var route : UNADDRESSED) {
      answers.put(route, ask(token, route));
    }
    for (var template : ADDRESSED) {
      if (!NEVER_ASKED_OF_ALPHA.contains(template)) {
        answers.put(
            "alpha " + template,
            ask(token, addressed(template, "alpha", A_RUN, alphaReview, A_FILE)));
      }
      answers.put(
          "bravo " + template,
          ask(token, addressed(template, "bravo", B_RUN, bravoReview, B_FILE))
              .replace(bravoReview, "{review}"));
    }
    return answers;
  }

  private static String addressed(
      String template, String project, String run, String review, String file) {
    return template
        .replace("{project}", project)
        .replace("{spec}", project + "-spec")
        .replace("{room}", project + "-room")
        .replace("{run}", run)
        .replace("{review}", review)
        .replace("{file}", file);
  }

  private String ask(String token, String route) throws Exception {
    var post = route.startsWith("POST ");
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + (post ? route.substring("POST ".length()) : route)))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(30));
    var response =
        client.send(
            post
                ? request.POST(HttpRequest.BodyPublishers.noBody()).build()
                : request.GET().build(),
            HttpResponse.BodyHandlers.ofString());
    return response.statusCode() + " " + response.body();
  }

  /** {@code operations}, noting each operation a route reaches. */
  private Operations recording(Operations operations) {
    return (Operations)
        Proxy.newProxyInstance(
            Operations.class.getClassLoader(),
            new Class<?>[] {Operations.class},
            (proxy, method, arguments) -> {
              reached.add(method.getName());
              try {
                return method.invoke(operations, arguments);
              } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
              }
            });
  }

  private static Set<String> readsOverHttp() {
    return Arrays.stream(Operations.class.getMethods())
        .map(Method::getName)
        .filter(operation -> !NOT_READ_OVER_HTTP.contains(operation))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /** The box's containers, a log every run wrote two lines to, and no snapshot. */
  private final class BoxShell implements ShellExec {

    @Override
    public Result exec(List<String> command) {
      var asked = String.join(" ", command);
      if (asked.equals("incus list --format json")) {
        return new Result(0, containers.stream().map(BoxShell::running).toList().toString(), "");
      }
      for (var container : containers) {
        if (asked.equals("incus list ^" + container + "$ --format json")) {
          return new Result(0, "[" + running(container) + "]", "");
        }
      }
      if (asked.startsWith("incus snapshot list")) {
        return new Result(0, "[]", "");
      }
      if (asked.contains(" tail -n ")) {
        return new Result(0, "one\ntwo\n", "");
      }
      if (asked.contains("ss -ltnH")) {
        return new Result(0, "---\n", "");
      }
      return new Result(1, "", "no script for " + asked);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }

    private static String running(String container) {
      return """
          {"name": "%s", "status": "Running", "state": {"network": {"eth0": {"addresses": [
            {"family": "inet", "address": "10.0.0.42", "scope": "global"}]}}}}"""
          .formatted(container);
    }
  }
}
