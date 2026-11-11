/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.OperationHooks;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.MainReplica;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncSession;
import ai.singlr.sail.sync.SyncTransitionSink;
import ai.singlr.sail.sync.SyncWire;
import ai.singlr.sail.webauthn.TestAuthenticator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A2 at the doors: each row of the Access arc's scenario matrix, asked through every door that
 * offers it by the same person with the same kind of credential, gets the same decision in the same
 * words. The cast is the fleet's: uday is main's admin, eng a member of every project, client a
 * member of acme only, obs a viewer of acme and beta ({@link DoorFleet}). The web door is the
 * person's own token at main; the host CLI is {@code sail} on the person's own box, its host token
 * at that box's web door; the socket is the box credential at that box's socket. Every credential
 * is resolved as production resolves it. A door says a refusal its own way: the web and the socket
 * as an error's code, words and fix; the CLI as one line, its fix's commands rendered for the
 * terminal and the status it was refused with.
 */
class DoorParityTest {

  private static final SyncConfig MAIN = DoorFleet.MAIN;

  private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

  @TempDir Path tempDir;
  private DoorFleet fleet;
  private SyncDatabase main;
  private Sqlite db;
  private String hostToken;
  private Map<String, DoorBox> boxes;
  private Map<String, String> tokens;

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
        case "gateway" -> message + " " + fix;
        case "sync" -> message;
        default -> api();
      };
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    fleet = DoorFleet.open(tempDir);
    main = fleet.main();
    db = fleet.db();
    hostToken = fleet.hostToken();
    boxes = fleet.boxes();
    tokens = fleet.tokens();
  }

  @AfterEach
  void tearDown() {
    fleet.close();
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

  /** Row 20: eng's credential in the container comments on e1 as eng does outside it. */
  @Test
  void row20EngsCredentialInTheContainerCommentsOnE1AsHeDoesOutside() throws Exception {
    assertEveryDoor(
        Expected.ALLOWED,
        web("uday", "eng", "POST", "/v1/rooms/e1/messages", "{\"body\": \"hi\"}"),
        cli("eng", "spec", "comment", "e1", "--body", "hi"),
        socket("eng", "POST", "/v1/specs/e1/messages", "body=hi"));
  }

  /** A person's terminal in the container creates a spec and claims it as at the doors outside. */
  @Test
  void aPersonsTerminalCreatesAndClaimsAtTheSocketAsOutside() throws Exception {
    assertEveryDoor(
        Expected.ALLOWED,
        web(
            "uday",
            "eng",
            "POST",
            "/v1/specs",
            "{\"id\": \"t-web\", \"project\": \"acme\", \"title\": \"t-web\"}"),
        cli("eng", "spec", "create", "--id", "t-cli", "--project", "acme", "--title", "t-cli"),
        socket("eng", "POST", "/v1/specs", "id=t-socket&project=acme&title=t-socket"));
    assertEveryDoor(
        Expected.ALLOWED,
        web("uday", "eng", "PUT", "/v1/specs/t-web", "{\"assignee\": \"eng\"}"),
        cli("eng", "spec", "edit", "t-cli", "--assignee", "eng"),
        socket("eng", "PUT", "/v1/specs/t-socket", "assignee=eng"));
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
      assertEveryDoor(Expected.ALLOWED, web(person, person, "GET", "/v1/sync", null));
      assertEveryDoor(Expected.ALLOWED, web(person, person, "GET", "/v1/events/stats", null));
    }
    assertEveryDoor(Expected.ALLOWED, web("obs", "obs", "GET", "/v1/sync", null));
  }

  @Test
  void aScopedPersonListsOnlyTheSpecsOfItsProjectsAtEveryDoor() throws Exception {
    var acme = List.of("a2", "e1", "in-r1");

    assertEquals(acme, ids(webBody("uday", "client", "GET", "/v1/specs", null)));
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
    assertEquals(acme, ids(boxes.get("client").socket("GET", "/v1/specs", "").body()));
  }

  /**
   * Row 7 at sync. A node pushes only what it holds as its own, so client's push is of c7, which it
   * wrote and which uday gave eng while client was not syncing; main's refusal is the web door's.
   */
  @Test
  void row7ClientsPushOfAnEditToEngsSpecIsRefusedAtSyncNamingEng() throws Exception {
    try (var clients = new GatewayNode(tempDir, main, MAIN, boxes.get("client").config())) {
      clients.round();
      Acting.as(
          "client",
          () -> new SpecStore(clients.db()).create(DoorFleet.spec("c7", "acme", "client", "c7")));
      clients.round();
      assertEquals(
          "allowed", web("uday", "uday", "PUT", "/v1/specs/c7", "{\"assignee\": \"eng\"}").text());
      Acting.as(
          "client",
          () ->
              new SpecStore(clients.db())
                  .update(DoorFleet.spec("c7", "acme", "client", "retitled")));

      var denials = clients.round().denials();

      assertEveryDoor(
          Expected.refused(
              403,
              "forbidden_not_assignee",
              "Spec 'c7' is assigned to 'eng', not you.",
              "Ask eng to make this change, or have an admin do it."),
          web("uday", "client", "PUT", "/v1/specs/c7", "{\"title\": \"retitled\"}"),
          new Said(
              "sync",
              denials.stream()
                  .filter(denial -> denial.id().equals("c7"))
                  .map(SyncSession.Denial::reason)
                  .findFirst()
                  .orElse("no denial")));
    }
  }

  /**
   * Row 4, at sync, the one door that takes a push. Client's box holds nothing of beta, so no node
   * of main's pushes a beta spec of client's; the push is forged, a revision of b1 offered over a
   * session main's gateway minted for client, and main's sync server refuses it as in no project
   * client is granted.
   */
  @Test
  void row4ClientsPushOfABetaSpecIsRefusedInNoProjectItIsGranted() throws Exception {
    var retitled = new LinkedHashMap<>(new SpecStore(db).currentForSync("b1"));
    retitled.put("title", "retitled");

    var denied =
        forgedPush(
            "client",
            new SyncWire.Push(
                "spec",
                List.of(new MainReplica.Offer("b1", retitled, new SpecStore(db).latestRev("b1")))));

    assertEveryDoor(
        Expected.refused(0, null, "Spec 'b1' is in no project this FDE is granted.", null),
        new Said("sync", denied));
    assertEquals("b1", new SpecStore(db).findById("b1").orElseThrow().title(), "main took none");
  }

  /**
   * What main's sync server answers {@code push} over a session its gateway minted for {@code
   * person}, said as a denial's reason: the client is forged, speaking the wire itself.
   */
  private String forgedPush(String person, SyncWire.Push push) throws Exception {
    var token =
        assertInstanceOf(
                SshGateway.Authorized.class, GatewayNode.gateway(main, MAIN, "sail _sync", person))
            .sessionToken();
    var toMain = new PipedOutputStream();
    var mainIn = new PipedInputStream(toMain, SyncWire.MAX_FRAME);
    var fromMain = new PipedOutputStream();
    var nodeIn = new PipedInputStream(fromMain, SyncWire.MAX_FRAME);
    var served =
        Executors.newVirtualThreadPerTaskExecutor()
            .submit(
                () -> {
                  try (fromMain) {
                    return SyncServerCommand.serve(
                        main, "main", token, mainIn, fromMain, SyncTransitionSink.NONE, () -> MAIN);
                  }
                });
    try (toMain) {
      assertInstanceOf(
          SyncWire.Welcome.class,
          exchange(toMain, nodeIn, SyncWire.Hello.of(SailVersion.version(), person)));
      var results = assertInstanceOf(SyncWire.Results.class, exchange(toMain, nodeIn, push));
      send(toMain, new SyncWire.Bye());
      return results.results().stream()
          .map(result -> assertInstanceOf(SyncWire.Denied.class, result).reason())
          .findFirst()
          .orElse("no denial");
    } finally {
      served.get();
    }
  }

  private static SyncWire.Response exchange(
      OutputStream toMain, InputStream fromMain, SyncWire.Request request) throws IOException {
    send(toMain, request);
    return SyncWire.decodeResponse(SyncWire.readFramed(fromMain));
  }

  private static void send(OutputStream toMain, SyncWire.Request request) throws IOException {
    toMain.write((SyncWire.encode(request) + "\n").getBytes(StandardCharsets.UTF_8));
    toMain.flush();
  }

  @Test
  void row25EngAddingAPersonIsAdminOnlyAtEveryDoorThatManagesPeople() throws Exception {
    assertEveryDoor(
        Expected.refused(
            403,
            "forbidden_admin_only",
            "Only an admin may manage people.",
            "Ask an admin to do it."),
        web("uday", "eng", "POST", "/v1/auth/register/ticket", "{\"fde\": \"zed\"}"),
        hostCli(
            SailCli.run(
                () -> CliOperator.of(boxes.get("eng").config(), new FdeStore(db)),
                fde("eng"),
                "add",
                "zed",
                "--name",
                "Zed")),
        new Said(
            "gateway",
            ((SshGateway.Rejected)
                    GatewayNode.gateway(main, MAIN, "sail fde add zed --name Zed", "eng"))
                .reason()));
  }

  /**
   * At the gateway, the {@code agent} verbs that read the control plane directly would run as the
   * box's own person, so a viewer asking one is refused there, while a verb that asks the API runs
   * as the viewer.
   */
  @Test
  void theGatewayRefusesAnAgentVerbThatWouldRunAsTheBoxsOwnPerson() {
    assertEveryDoor(
        Expected.refused(
            0,
            null,
            "'agent stop' reads this box's control plane directly, as the box's own person, so it"
                + " is not available over an SSH-key session.",
            "Follow the work with 'sail spec' and the board, or SSH to the host directly to run"
                + " it."),
        new Said(
            "gateway",
            ((SshGateway.Rejected)
                    GatewayNode.gateway(main, MAIN, "sail agent stop beta --dry-run", "obs"))
                .reason()));
    assertInstanceOf(
        SshGateway.Authorized.class,
        GatewayNode.gateway(main, MAIN, "sail agent sessions acme", "obs"),
        "an agent verb that asks the API runs as the viewer");
  }

  /**
   * Row 26, at the passkey routes, the one door that enrolls: a person the roster disables gets no
   * ticket, uses none minted before, starts and finishes no registration, and signs in to no
   * session. The admin minting the ticket did authenticate, so is told it as a conflict.
   */
  @Test
  void row26ADisabledPersonEnrollsRegistersAndSignsInToNothing() throws Exception {
    var ticket =
        (String)
            webBody("uday", "uday", "POST", "/v1/auth/register/ticket", "{\"fde\":\"obs\"}")
                .get("ticket");
    var started = webBody("uday", "uday", "POST", "/v1/auth/register/start", "{\"fde\":\"obs\"}");
    var authenticator = new TestAuthenticator(DoorBox.RP_ID);
    var registered =
        webBody(
            "uday",
            "uday",
            "POST",
            "/v1/auth/register/finish",
            registration(
                webBody("uday", "uday", "POST", "/v1/auth/register/start", "{\"fde\":\"obs\"}"),
                authenticator));
    assertEquals("registered", registered.get("status"));
    var login = webBody("uday", null, "POST", "/v1/auth/login/start", "{}");
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'obs'");
    var disabled =
        Expected.refused(
            401,
            "authentication_failed",
            "FDE 'obs' is disabled. Ask an admin to re-enable it.",
            null);

    var conflict = Expected.refused(409, "conflict", "FDE 'obs' is disabled.", null);

    assertEveryDoor(
        conflict, web("uday", "uday", "POST", "/v1/auth/register/ticket", "{\"fde\":\"obs\"}"));
    assertEveryDoor(disabled, passkey("POST", "/v1/auth/register/start", ticket, "{}"));
    assertEveryDoor(
        conflict, web("uday", "uday", "POST", "/v1/auth/register/start", "{\"fde\":\"obs\"}"));
    assertEveryDoor(
        conflict,
        web(
            "uday",
            "uday",
            "POST",
            "/v1/auth/register/finish",
            registration(started, new TestAuthenticator(DoorBox.RP_ID))));
    assertEveryDoor(
        disabled,
        web("uday", null, "POST", "/v1/auth/login/finish", assertion(login, authenticator)));
  }

  @Test
  void row27MainServesNoSyncSessionWhoseTokenIsNoSession() throws Exception {
    var err = new ByteArrayOutputStream();
    var out = new ByteArrayOutputStream();
    var original = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    int exit;
    try {
      exit =
          SyncServerCommand.serve(
              main,
              "main",
              "bogus",
              new ByteArrayInputStream(new byte[0]),
              out,
              SyncTransitionSink.NONE,
              () -> MAIN);
    } finally {
      System.setErr(original);
    }

    assertEquals(1, exit);
    assertEquals(0, out.size(), "nothing is served");
    assertEquals(SyncServerCommand.REFUSED + "\n", err.toString(StandardCharsets.UTF_8));
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
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              request(at, person, method, path, json), HttpResponse.BodyHandlers.ofString());
      return new Said(
          "web",
          response.statusCode() / 100 == 2
              ? "allowed"
              : refusal(
                  response.statusCode(),
                  (Map<?, ?>) YamlUtil.parseMap(response.body()).get("error")));
    }
  }

  /** What {@code sail} printed of a refusal, its lines as one, or that it was allowed. */
  private static Said hostCli(SailCli.Ran ran) {
    return new Said(
        "host cli",
        ran.exit() == 0
            ? "allowed"
            : String.join(" ", ran.err().lines().map(String::strip).toList()));
  }

  /** {@code sail fde} on {@code person}'s own box, a node of main's. */
  private FdeCommand fde(String person) {
    return new FdeCommand(
        () ->
            new FdeCommand.Box(
                tempDir.resolve("sail.db"),
                "root",
                boxes.get(person).config(),
                tempDir,
                tempDir.resolve("ssh_host_ed25519_key.pub"),
                tempDir.resolve("workstation_key.pub"),
                DoorBox.acmeRuns()));
  }

  /**
   * {@code body} posted to main's passkey route {@code path} with enrollment ticket {@code ticket}.
   */
  private Said passkey(String method, String path, String ticket, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(boxes.get("uday").url() + path))
            .header("X-Enrollment-Ticket", ticket)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body));
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Said(
          "web",
          refusal(
              response.statusCode(), (Map<?, ?>) YamlUtil.parseMap(response.body()).get("error")));
    }
  }

  private static String registration(Map<String, Object> started, TestAuthenticator authenticator) {
    var registered = authenticator.register(challenge(started), DoorBox.ORIGIN);
    return "{\"challenge_id\":\""
        + started.get("challenge_id")
        + "\",\"client_data_json\":\""
        + BASE64.encodeToString(registered.clientDataJson())
        + "\",\"attestation_object\":\""
        + BASE64.encodeToString(registered.attestationObject())
        + "\"}";
  }

  private static String assertion(Map<String, Object> login, TestAuthenticator authenticator) {
    var asserted = authenticator.assertResponse(challenge(login), DoorBox.ORIGIN);
    return "{\"challenge_id\":\""
        + login.get("challenge_id")
        + "\",\"credential_id\":\""
        + BASE64.encodeToString(asserted.credentialId())
        + "\",\"client_data_json\":\""
        + BASE64.encodeToString(asserted.clientDataJson())
        + "\",\"authenticator_data\":\""
        + BASE64.encodeToString(asserted.authenticatorData())
        + "\",\"signature\":\""
        + BASE64.encodeToString(asserted.signature())
        + "\"}";
  }

  private static byte[] challenge(Map<String, Object> ceremony) {
    var publicKey = (Map<?, ?>) ceremony.get("public_key");
    return Base64.getUrlDecoder().decode((String) publicKey.get("challenge"));
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
    var response = boxes.get(person).socket(method, path, form);
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
            dryRun -> DoorBox.acmeRuns(),
            (shell, hooks) ->
                DoorBox.wired(
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
                    db,
                    tempDir,
                    config),
            () -> person);
    var ran =
        SailCli.run(
            command, "-p", "acme", "--spec", "e1", "--background", "--no-sync", "--no-snapshot");
    return hostCli(ran);
  }

  /** The body {@code at}'s web door answers {@code person}'s request, as {@link #web} asks it. */
  private Map<String, Object> webBody(
      String at, String person, String method, String path, String json) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return YamlUtil.parseMap(
          client
              .send(request(at, person, method, path, json), HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  /** {@code person}'s request with their own token, or with none for a {@code null} person. */
  private HttpRequest request(String at, String person, String method, String path, String json) {
    var request =
        HttpRequest.newBuilder(URI.create(boxes.get(at).url() + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    if (person != null) {
      request.header("Authorization", "Bearer " + tokens.get(person));
    }
    return request.build();
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
}
