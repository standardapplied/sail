/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.api.AgentLogStreamer;
import ai.singlr.sail.api.ApiRouter;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.OperationHooks;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.RateLimitGate;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SocketDoor;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A2 at the doors: each row of the Access arc's scenario matrix, asked through every door that
 * offers it by the same person with the same kind of credential, gets the same decision in the same
 * words. The cast is the fleet's: uday is main's admin, eng a member of every project, client a
 * member of acme only, obs a viewer of acme and beta. {@code e1} is eng's spec in acme, {@code r1}
 * eng's room there, {@code in-r1} a spec born in it, {@code a2} an unassigned acme spec and {@code
 * b1} eng's spec in beta. The web door is the person's own token at main; the host CLI is {@code
 * sail} on the person's own box, its host token at that box's web door; the socket is the box
 * credential at that box's socket. Every credential is resolved as production resolves it. A door
 * says a refusal its own way: the web and the socket as an error's code, words and fix; the CLI as
 * one line, its fix's commands rendered for the terminal and the status it was refused with.
 */
class DoorParityTest {

  private static final SyncConfig MAIN = new SyncConfig(SyncConfig.ROLE_MAIN, null, "uday", "main");

  @TempDir Path tempDir;
  private Sqlite db;
  private String hostToken;
  private final Map<String, Box> boxes = new HashMap<>();
  private final Map<String, String> tokens = new HashMap<>();

  /** A person's box: its operations and its web door. */
  private record Box(String person, SyncConfig config, SailOperations operations, HttpServer http) {
    String url() {
      return "http://127.0.0.1:" + http.getAddress().getPort();
    }
  }

  /**
   * A row's answer: allowed, or refused with {@code status} and {@code code}, in {@code message},
   * with {@code fix}; {@code terminalFix} is the fix as a terminal renders its commands.
   */
  private record Expected(int status, String code, String message, String fix, String terminalFix) {
    static final Expected ALLOWED = new Expected(0, null, null, null, null);

    static Expected refused(int status, String code, String message, String fix) {
      return new Expected(status, code, message, fix, fix);
    }

    Expected onTheTerminal(String rendered) {
      return new Expected(status, code, message, fix, rendered);
    }

    /** What a web or socket door answers. */
    String api() {
      return status == 0 ? "allowed" : status + " " + code + " " + message + " | " + fix;
    }

    /** What {@code sail} prints, refused over its box's web door when {@code overTheWeb}. */
    String cli(boolean overTheWeb) {
      return status == 0
          ? "allowed"
          : "✗ "
              + message
              + (terminalFix == null ? "" : " " + terminalFix)
              + (overTheWeb ? " (HTTP " + status + ")" : "");
    }

    String at(String door) {
      return switch (door) {
        case "cli" -> cli(true);
        case "host cli" -> cli(false);
        default -> api();
      };
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    var roster = new FdeStore(db);
    roster.add("uday", null, null, "admin");
    roster.add("eng", null, null, "member");
    roster.add("client", null, null, "member");
    roster.add("obs", null, null, "viewer");
    roster.restrict("client");
    roster.grant("client", "acme");
    roster.restrict("obs");
    roster.grant("obs", "acme");
    roster.grant("obs", "beta");
    for (var person : List.of("uday", "eng", "client", "obs")) {
      var fde = roster.byHandle(person).orElseThrow();
      tokens.put(person, new TokenStore(db).create(person, fde.role(), fde.id(), null).token());
    }
    hostToken = new TokenStore(db).create(HostToken.NAME, "admin", null, null).token();
    Acting.system(
        () ->
            List.of("acme", "beta")
                .forEach(
                    project ->
                        new ProjectStore(db)
                            .upsert(
                                TestDefinitions.yaml(
                                    "name: "
                                        + project
                                        + "\nagent:\n  type: claude-code\n  auto_snapshot:"
                                        + " false\n"))));
    var specs = new SpecStore(db);
    Acting.as(
        "eng",
        () -> {
          new RoomStore(db)
              .create(
                  new RoomStore.RoomRow(
                      "r1", "acme", "r1", "eng", null, null, "eng", null, null, "eng"));
          specs.create(spec("e1", "acme", "eng"));
          specs.create(spec("b1", "beta", "eng"));
          specs.create(spec("in-r1", "acme", null).withRoomId("r1"));
        });
    Acting.as("uday", () -> specs.create(spec("a2", "acme", null)));
    boxes.put("uday", box("uday", MAIN));
    for (var person : List.of("eng", "client", "obs")) {
      boxes.put(
          person, box(person, new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", person, person)));
    }
  }

  @AfterEach
  void tearDown() {
    boxes.values().forEach(box -> box.http().stop(0));
    boxes.values().forEach(box -> box.operations().close());
    db.close();
  }

  @Test
  void row1ObsReadsAcmesSpecs() throws Exception {
    assertEveryDoor(
        Expected.ALLOWED,
        web("uday", "obs", "GET", "/v1/specs/e1", null),
        cli("obs", "spec", "show", "e1"),
        socket("obs", "GET", "/v1/specs/e1", ""));
  }

  @Test
  void row2ObsCommentingOnE1IsReadOnlyAndNamesTheFix() throws Exception {
    assertEveryDoor(
        Expected.refused(
            403,
            "read_only_credential",
            "Your credential is read-only and cannot post in rooms.",
            "Ask an admin for a member or admin credential."),
        web("uday", "obs", "POST", "/v1/rooms/e1/messages", "{\"body\": \"hi\"}"),
        cli("obs", "spec", "comment", "e1", "--body", "hi"),
        socket("obs", "POST", "/v1/specs/e1/messages", "body=hi"));
  }

  @Test
  void row3ASpecInBetaIsNotFoundForClient() throws Exception {
    assertEveryDoor(
        Expected.refused(404, "spec_not_found", "Spec 'b1' was not found.", null),
        web("uday", "client", "GET", "/v1/specs/b1", null),
        cli("client", "spec", "show", "b1"),
        socket("client", "GET", "/v1/specs/b1", ""));
  }

  @Test
  void row5AnIdABetaSpecHoldsAlreadyExistsForClient() throws Exception {
    assertEveryDoor(
        Expected.refused(
                409,
                "conflict",
                "Spec 'b1' already exists.",
                "Pick another spec id, or edit it with: spec update b1")
            .onTheTerminal("Pick another spec id, or edit it with: sail spec update b1"),
        web(
            "uday",
            "client",
            "POST",
            "/v1/specs",
            "{\"id\":\"b1\",\"title\":\"T\",\"project\":\"acme\"}"),
        cli("client", "spec", "create", "--id", "b1", "--title", "T", "--project", "acme"),
        socket("client", "POST", "/v1/specs", "id=b1&title=T&project=acme"));
  }

  @Test
  void row6ClientCommentsOnE1() throws Exception {
    assertEveryDoor(
        Expected.ALLOWED,
        web("uday", "client", "POST", "/v1/rooms/e1/messages", "{\"body\": \"hi\"}"),
        cli("client", "spec", "comment", "e1", "--body", "hi"),
        socket("client", "POST", "/v1/specs/e1/messages", "body=hi"));
  }

  @Test
  void row7ClientEditingE1IsRefusedNamingEng() throws Exception {
    assertEveryDoor(
        Expected.refused(
            403,
            "forbidden_not_assignee",
            "Spec 'e1' is assigned to 'eng', not you.",
            "Ask eng to make this change, or have an admin do it."),
        web("uday", "client", "PUT", "/v1/specs/e1", "{\"title\": \"retitled\"}"),
        cli("client", "spec", "edit", "e1", "--title", "retitled"),
        socket("client", "PUT", "/v1/specs/e1", "title=retitled"));
  }

  @Test
  void row8ClientClaimsAnUnassignedSpecButNotOneBornInR1() throws Exception {
    assertEveryDoor(
        Expected.ALLOWED,
        web("uday", "client", "PUT", "/v1/specs/a2", "{\"assignee\": \"client\"}"),
        cli("client", "spec", "edit", "a2", "--assignee", "client"),
        socket("client", "PUT", "/v1/specs/a2", "assignee=client"));
    assertEveryDoor(
        Expected.refused(
            403,
            "forbidden_admin_only",
            "'r1' belongs to eng: only eng or an admin may start work there, not you.",
            "Ask an admin to assign it to you."),
        web("uday", "client", "PUT", "/v1/specs/in-r1", "{\"assignee\": \"client\"}"),
        cli("client", "spec", "edit", "in-r1", "--assignee", "client"),
        socket("client", "PUT", "/v1/specs/in-r1", "assignee=client"));
  }

  @Test
  void row9ClientReassigningE1IsAdminOnly() throws Exception {
    assertEveryDoor(
        Expected.refused(
            403,
            "forbidden_admin_only",
            "Reassigning spec 'e1' moves work between FDEs and is an admin-only action"
                + " (currently 'eng').",
            "Ask an admin to reassign it. You may grab a spec only while it is unassigned."),
        web("uday", "client", "PUT", "/v1/specs/e1", "{\"assignee\": \"client\"}"),
        cli("client", "spec", "edit", "e1", "--assignee", "client"),
        socket("client", "PUT", "/v1/specs/e1", "assignee=client"));
  }

  /**
   * Row 10, at the web door as a dry run, which claims the spec as a dispatch does, so e1 is
   * pending again before the host CLI dispatches it.
   */
  @Test
  void row10EngDispatchesE1FromHisBoxAndUdaysBoxIsNotWhereItRuns() throws Exception {
    var dryRun = "{\"spec_id\": \"e1\", \"dry_run\": true}";
    var web = web("eng", "eng", "POST", "/v1/projects/acme/dispatch", dryRun);
    db.execute("UPDATE specs SET status = 'pending' WHERE id = 'e1'");
    assertEveryDoor(Expected.ALLOWED, web, dispatch("eng"));
    assertEveryDoor(
        Expected.refused(
                403,
                "runs_on_other_node",
                "Spec 'e1' is assigned to 'eng', which runs on that FDE's devbox, not this node"
                    + " ('uday').",
                "Reassign it to dispatch here: spec update e1 --assignee uday")
            .onTheTerminal("Reassign it to dispatch here: sail spec update e1 --assignee uday"),
        web("uday", "uday", "POST", "/v1/projects/acme/dispatch", dryRun),
        dispatch("uday"));
  }

  @Test
  void row22ClientRetellingReviewCompletedOnE1IsNotEngsOrAnAdmins() throws Exception {
    assertEveryDoor(
        Expected.refused(
            403,
            "forbidden_not_assignee",
            "Event type 'review_completed' makes this box act on 'e1' (owned by 'eng'), which"
                + " you may not drive.",
            "Only its owner or an admin may publish it."),
        web(
            "uday",
            "client",
            "POST",
            "/v1/events",
            Event.of("acme", "e1", "review_completed", "sail", "host").toJsonLine()));
  }

  @Test
  void aMemberIsRefusedAnotherBoxsSyncHealthAndBusStatsAndItsRounds() throws Exception {
    var notTheirs =
        Expected.refused(
            403,
            "forbidden_not_assignee",
            "Box 'uday' belongs to 'uday', not you.",
            "Ask uday to operate it, or have an admin do it.");
    assertEveryDoor(notTheirs, web("uday", "eng", "GET", "/v1/sync", null));
    assertEveryDoor(notTheirs, web("uday", "eng", "GET", "/v1/events/stats", null));
    assertEveryDoor(notTheirs, web("uday", "eng", "POST", "/v1/sync", "{}"));
  }

  @Test
  void theBoxsOwnPersonAndAnAdminReadItsSyncHealthAndBusStats() throws Exception {
    for (var person : List.of("eng", "uday")) {
      assertEveryDoor(
          Expected.ALLOWED,
          web(person, person, "GET", "/v1/sync", null),
          socket(person, "GET", "/v1/sync", ""));
      assertEveryDoor(Expected.ALLOWED, web(person, person, "GET", "/v1/events/stats", null));
    }
    assertEveryDoor(
        Expected.ALLOWED,
        web("obs", "obs", "GET", "/v1/sync", null),
        socket("obs", "GET", "/v1/sync", ""));
  }

  @Test
  void aScopedPersonListsOnlyTheSpecsOfItsProjectsAtEveryDoor() throws Exception {
    var acme = List.of("a2", "e1", "in-r1");

    assertEquals(acme, ids(webBody("uday", "client", "/v1/specs")));
    assertEquals(
        acme,
        ids(
            YamlUtil.parseMap(
                SailCli.run(
                        "spec",
                        "list",
                        "--all-projects",
                        "--json",
                        "--server",
                        boxes.get("client").url(),
                        "--token",
                        hostToken,
                        "--no-sync")
                    .out())));
    var box = boxes.get("client");
    assertEquals(
        acme,
        ids(
            SocketDoor.ask(
                    box.operations(),
                    box::person,
                    "GET",
                    "/v1/specs",
                    new BoxCredentialStore(db).replace("client"),
                    "")
                .body()));
  }

  /** What each door said, by door. */
  private record Said(String door, String text) {}

  private static void assertEveryDoor(Expected expected, Said... said) {
    var expectedByDoor = new LinkedHashMap<String, String>();
    var saidByDoor = new LinkedHashMap<String, String>();
    for (var answer : said) {
      expectedByDoor.put(answer.door(), expected.at(answer.door()));
      saidByDoor.put(answer.door(), answer.text());
    }
    assertEquals(expectedByDoor, saidByDoor);
  }

  /** {@code person}'s own token at the web door of {@code at}'s box. */
  private Said web(String at, String person, String method, String path, String json)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(boxes.get(at).url() + path))
            .header("Authorization", "Bearer " + tokens.get(person))
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Said(
          "web",
          response.statusCode() / 100 == 2
              ? "allowed"
              : refusal(
                  response.statusCode(),
                  (Map<?, ?>) YamlUtil.parseMap(response.body()).get("error")));
    }
  }

  /** {@code sail <args>} on {@code person}'s own box. */
  private Said cli(String person, String... args) {
    var argv = new ArrayList<>(List.of(args));
    argv.addAll(List.of("--server", boxes.get(person).url(), "--token", hostToken, "--no-sync"));
    var ran = SailCli.run(argv.toArray(String[]::new));
    return new Said("cli", ran.exit() == 0 ? "allowed" : ran.err().strip());
  }

  /** The box credential at {@code person}'s own box's socket. */
  private Said socket(String person, String method, String path, String form) {
    var box = boxes.get(person);
    var response =
        SocketDoor.ask(
            box.operations(),
            box::person,
            method,
            path,
            new BoxCredentialStore(db).replace(person),
            form);
    return new Said(
        "socket",
        response.status() / 100 == 2
            ? "allowed"
            : refusal(response.status(), (Map<?, ?>) response.body().get("error")));
  }

  /**
   * {@code sail spec dispatch} of e1 in the background on {@code person}'s own box, its operator
   * the box's person, its agent exiting at once. The host CLI's dry run asks no container whether
   * it runs, so it can say only a refusal that comes first; a real dispatch says the whole answer.
   */
  private Said dispatch(String person) {
    var config = boxes.get(person).config();
    var command =
        new DispatchCommand(
            dryRun -> new AcmeRuns(),
            (shell, hooks) ->
                operations(
                    OperationsFactory.create(
                        db,
                        shell,
                        new OperationHooks(
                            event -> {},
                            new WatcherSpawner(shell, (spawned, logPath) -> 4242L),
                            hooks.snapshotter(),
                            launched -> 0,
                            hooks.dispatchListener(),
                            hooks.stopListener()),
                        SessionYield.NONE),
                    config),
            () -> person);
    var ran =
        SailCli.run(
            command, "-p", "acme", "--spec", "e1", "--background", "--no-sync", "--no-snapshot");
    return new Said("host cli", ran.exit() == 0 ? "allowed" : ran.err().strip());
  }

  /** The body {@code person}'s own token reads at {@code path} of {@code at}'s web door. */
  private Map<String, Object> webBody(String at, String person, String path) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(boxes.get(at).url() + path))
            .header("Authorization", "Bearer " + tokens.get(person))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return YamlUtil.parseMap(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }
  }

  private static List<String> ids(Map<String, Object> listed) {
    return ((List<?>) listed.get("specs"))
        .stream().map(spec -> (String) ((Map<?, ?>) spec).get("id")).sorted().toList();
  }

  private static String refusal(int status, Map<?, ?> error) {
    return status
        + " "
        + error.get("code")
        + " "
        + error.get("message")
        + " | "
        + error.get("action");
  }

  private Box box(String person, SyncConfig config) throws IOException {
    var operations =
        operations(
            OperationsFactory.create(
                db,
                new AcmeRuns(),
                new EventBus(),
                null,
                SyncScheduler.disabled(),
                SessionYield.NONE),
            config);
    var auth = TestAuth.sessions(db, config);
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/",
        new ApiRouter(
            operations, auth, new RateLimitGate(), new AgentLogStreamer(auth), () -> person));
    http.start();
    return new Box(person, config, operations, http);
  }

  /** {@code operations} on the box {@code config} configures, over the one control plane. */
  private SailOperations operations(SailOperations operations, SyncConfig config) {
    return operations.useControlPlane(
        db,
        tempDir,
        new SyncOperations(
            db,
            config.boxId(),
            tempDir,
            () -> config,
            target -> {
              throw new IOException("main is not asked here");
            }));
  }

  private static SpecStore.SpecRow spec(String id, String project, String assignee) {
    return new SpecStore.SpecRow(
        id,
        project,
        id,
        SpecStatus.PENDING,
        assignee,
        null,
        null,
        null,
        null,
        0,
        null,
        null,
        null,
        null,
        List.of(),
        List.of());
  }

  /** A host whose only container is acme's, running; every other command does nothing. */
  private static final class AcmeRuns implements ShellExec {
    @Override
    public Result exec(List<String> command) {
      return String.join(" ", command).startsWith("incus list")
          ? new Result(0, "[{\"name\": \"acme\", \"status\": \"Running\", \"state\": {}}]", "")
          : new Result(0, "", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
