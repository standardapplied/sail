/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.OperationHooks;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncSession;
import ai.singlr.sail.sync.SyncTransitionSink;
import ai.singlr.sail.webauthn.TestAuthenticator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
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

  private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

  @TempDir Path tempDir;
  private SyncDatabase main;
  private Sqlite db;
  private String hostToken;
  private final Map<String, DoorBox> boxes = new HashMap<>();
  private final Map<String, String> tokens = new HashMap<>();

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
    main = GatewayNode.main(tempDir.resolve("sail.db"));
    db = main.db();
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
    boxes.put("uday", DoorBox.open(db, tempDir, MAIN));
    for (var person : List.of("eng", "client", "obs")) {
      boxes.put(
          person,
          DoorBox.open(
              db, tempDir, new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", person, person)));
    }
  }

  @AfterEach
  void tearDown() {
    boxes.values().forEach(DoorBox::close);
    main.close();
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
          "client", () -> new SpecStore(clients.db()).create(spec("c7", "acme", "client", "c7")));
      clients.round();
      assertEquals(
          "allowed", web("uday", "uday", "PUT", "/v1/specs/c7", "{\"assignee\": \"eng\"}").text());
      Acting.as(
          "client",
          () -> new SpecStore(clients.db()).update(spec("c7", "acme", "client", "retitled")));

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

  @Test
  void row25EngAddingAPersonIsAdminOnlyAtEveryDoorThatManagesPeople() throws Exception {
    assertEveryDoor(
        Expected.refused(
            403,
            "forbidden_admin_only",
            "Only an admin may manage people.",
            "Ask an admin to do it."),
        web("uday", "eng", "POST", "/v1/auth/register/ticket", "{\"fde\": \"zed\"}"),
        hostCli(SailCli.run(fde("eng"), "add", "zed", "--name", "Zed")),
        new Said(
            "gateway",
            ((SshGateway.Rejected)
                    GatewayNode.gateway(main, MAIN, "sail fde add zed --name Zed", "eng"))
                .reason()));
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

    assertEveryDoor(
        Expected.refused(
            409, "conflict", "FDE 'obs' is disabled. Ask an admin to re-enable it.", null),
        web("uday", "uday", "POST", "/v1/auth/register/ticket", "{\"fde\":\"obs\"}"));
    assertEveryDoor(disabled, passkey("POST", "/v1/auth/register/start", ticket, "{}"));
    assertEveryDoor(
        disabled, web("uday", "uday", "POST", "/v1/auth/register/start", "{\"fde\":\"obs\"}"));
    assertEveryDoor(
        disabled,
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
                new DoorBox.AcmeRuns()));
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
            dryRun -> new DoorBox.AcmeRuns(),
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

  private static SpecStore.SpecRow spec(String id, String project, String assignee) {
    return spec(id, project, assignee, id);
  }

  private static SpecStore.SpecRow spec(String id, String project, String assignee, String title) {
    return new SpecStore.SpecRow(
        id,
        project,
        title,
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
}
