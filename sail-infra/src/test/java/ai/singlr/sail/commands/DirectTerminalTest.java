/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.RateLimitGate;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SocketDoor;
import ai.singlr.sail.api.SseHandler;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TerminalTickets;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ListenConfig;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyChildShim;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.PtyWire;
import ai.singlr.sail.pty.SdNotify;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A client on the box's private network reaches the API and a terminal with no SSH: the API on its
 * private address answers as on loopback, a ticket asked of it is decided by the terminal host as
 * that host decides the open or attach, and the ticket, presented on the host's TCP listener,
 * admits one connection for that one thing, speaking the unix socket's frames. Each scenario runs
 * through the real router and a real terminal host over loopback TCP, on main's control plane:
 * uday, its admin; ada, a member granted acme alone; vic, a viewer. A session the host opens in a
 * project runs {@code cat} under the real session shim in place of the container's command, which
 * needs an Incus. Time runs on the host's injected clock; every wait is bounded by the class's
 * timeout.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DirectTerminalTest {

  private static final SyncConfig MAIN = new SyncConfig(SyncConfig.ROLE_MAIN, null, "uday", "main");
  private static final InetSocketAddress ANY_LOOPBACK_PORT =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
  private static final String OPEN_ACME = "{\"action\":\"open\",\"project\":\"acme\"}";

  @TempDir Path dir;
  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private Sqlite db;
  private FdeStore roster;
  private SailOperations operations;
  private PtySessionHost host;
  private SailApiServer api;
  private String ada;
  private String vic;

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    roster = new FdeStore(db);
    roster.add("uday", "Uday", "uday@x.dev", "admin");
    var adaRow = roster.add("ada", "Ada", "ada@x.dev", "member");
    var vicRow = roster.add("vic", "Vic", "vic@x.dev", "viewer");
    Acting.system(
        () -> {
          var projects = new ProjectStore(db);
          projects.upsert(TestDefinitions.named("acme"));
          projects.upsert(TestDefinitions.named("beta"));
        });
    roster.restrict("ada");
    roster.grant("ada", "acme");
    var tokens = new TokenStore(db);
    ada = tokens.create("ada-mac", "member", adaRow.id(), null).token();
    vic = tokens.create("vic-mac", "viewer", vicRow.id(), null).token();
    operations =
        DoorBox.wired(
            OperationsFactory.create(
                db, DoorBox.acmeRuns(), null, null, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            dir,
            MAIN);
    host = terminalHost(new PtySessionHost.Network(Optional.of(ANY_LOOPBACK_PORT), clock::get));
    host.start();
    api =
        apiServer(
            new SailApiServer.PrivateNetwork(
                Optional.of(ANY_LOOPBACK_PORT),
                Optional.of(
                    new TerminalTickets(
                        new ProjectStore(db),
                        (credential, action, target) ->
                            SessionClient.ticket(socket(), credential, action, target),
                        host.tcpAddress().orElseThrow()))));
    api.start();
  }

  @AfterEach
  void tearDown() {
    api.close();
    host.close();
    operations.close();
    db.close();
  }

  /** Scenario 1: with neither setting nothing more is bound, and the ticket route is no route. */
  @Test
  void withNeitherSettingOnlyLoopbackAndTheUnixSocketAreBoundAndTheRouteIsAbsent()
      throws Exception {
    var hostAlone = terminalHost(PtySessionHost.Network.NONE, dir.resolve("alone.sock"));
    hostAlone.start();
    try (hostAlone;
        var apiAlone = apiServer(SailApiServer.PrivateNetwork.NONE)) {
      apiAlone.start();
      var base = "http://127.0.0.1:" + apiAlone.port();

      assertEquals(Optional.empty(), hostAlone.tcpAddress());
      assertEquals(Optional.empty(), apiAlone.privateAddress());
      assertEquals(ListenConfig.none(), ListenConfig.fromMap(null));
      var absent = "404 not_found API endpoint was not found.";
      assertEquals(
          absent, statusAndError(send(base, "POST", "/v1/terminal/tickets", ada, OPEN_ACME)));
      assertEquals(absent, statusAndError(send(base, "GET", "/v1/terminal/tickets", ada, null)));
      assertEquals(
          "This host listens for no terminal over the network. Name an address with 'sail host"
              + " config set terminal-listen <address>:<port>' and restart the sail-pty-host"
              + " service.",
          assertThrows(
                  IOException.class,
                  () ->
                      SessionClient.ticket(
                          dir.resolve("alone.sock"), ada, PtyMessage.TicketAction.OPEN, "acme"))
              .getMessage());
    }
  }

  /**
   * Scenarios 2 and 3: the private address answers whoami, and a missing credential, as loopback.
   */
  @Test
  void thePrivateAddressAnswersAsLoopbackDoesWithACredentialAndWithout() throws Exception {
    var privately = "http://" + ListenConfig.hostPort(api.privateAddress().orElseThrow());
    var loopback = "http://127.0.0.1:" + api.port();

    var whoami = send(loopback, "GET", "/v1/whoami", ada, null);
    assertEquals(200, whoami.statusCode());
    assertEquals("ada", YamlUtil.parseMap(whoami.body()).get("fde"));
    assertEquals(whoami.body(), send(privately, "GET", "/v1/whoami", ada, null).body());
    var unauthenticated = send(loopback, "GET", "/v1/whoami", null, null);
    assertEquals("401 missing_bearer_token Missing bearer token.", statusAndError(unauthenticated));
    assertEquals(unauthenticated.body(), send(privately, "GET", "/v1/whoami", null, null).body());
  }

  /**
   * Scenario 4, and N6: a wildcard or unparseable address is refused naming its setting, before
   * anything binds; one that cannot be bound fails the start.
   */
  @Test
  void aWildcardOrUnparseableAddressIsRefusedNamingItsSettingAndABindFailureFailsTheStart()
      throws Exception {
    assertEquals(
        "'api-listen' is '0.0.0.0:7071', a wildcard, which would listen on every network the box"
            + " is on. Name the box's address on the private network, e.g. 100.64.0.7:7071.",
        assertThrows(
                IllegalArgumentException.class,
                () -> ListenConfig.fromMap(Map.of("api", "0.0.0.0:7071")).apiAddress())
            .getMessage());
    assertEquals(
        "'terminal-listen' is '[::]:7072', a wildcard, which would listen on every network the"
            + " box is on. Name the box's address on the private network, e.g. 100.64.0.7:7071.",
        assertThrows(
                IllegalArgumentException.class,
                () -> ListenConfig.fromMap(Map.of("terminal", "[::]:7072")).terminalAddress())
            .getMessage());
    assertEquals(
        "'api-listen' is 'tailnet.example:7071', which is not an address and a port. Name the"
            + " box's address on the private network, e.g. 100.64.0.7:7071 or"
            + " [fd7a:115c:a1e0::7]:7071.",
        assertThrows(
                IllegalArgumentException.class,
                () -> ListenConfig.address(ListenConfig.API, "tailnet.example:7071"))
            .getMessage());

    var taken = api.privateAddress().orElseThrow();
    var refused =
        assertThrows(
            IOException.class,
            () ->
                apiServer(new SailApiServer.PrivateNetwork(Optional.of(taken), Optional.empty())));
    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "Could not listen on "
                    + ListenConfig.hostPort(taken)
                    + ", the API's private address ('api-listen'): "),
        refused.getMessage());
    var terminalTaken = host.tcpAddress().orElseThrow();
    try (var clash =
        terminalHost(
            new PtySessionHost.Network(Optional.of(terminalTaken), clock::get),
            dir.resolve("clash.sock"))) {
      assertThrows(IOException.class, clash::start);
    }
  }

  /** Scenario 5: a holder of acme is answered a ticket and where to present it. */
  @Test
  void aHolderOfAProjectIsAnsweredATicketToOpenInItAndTheTerminalAddress() throws Exception {
    var answer = YamlUtil.parseMap(body(post(ada, OPEN_ACME), 201));

    assertTrue(answer.get("ticket").toString().matches("tkt_[0-9a-f]{64}"), answer.toString());
    assertEquals(ListenConfig.hostPort(host.tcpAddress().orElseThrow()), answer.get("address"));
    assertEquals(
        DateTimeUtils.format(DateTimeUtils.now().plus(PtyMessage.TICKET_LIFE)).substring(0, 16),
        answer.get("expires_at").toString().substring(0, 16));
  }

  /** Scenario 6: a project the caller does not hold is not found, in the terminal host's words. */
  @Test
  void aProjectTheCallerDoesNotHoldIsNotFoundInTheTerminalHostsWords() throws Exception {
    var answer = post(ada, "{\"action\":\"open\",\"project\":\"beta\"}");

    assertEquals(404, answer.statusCode());
    var error = (Map<?, ?>) YamlUtil.parseMap(answer.body()).get("error");
    assertEquals("project_not_in_catalog", error.get("code"));
    try (var unix = SessionClient.connect(socket(), ada)) {
      var hostSays =
          assertThrows(
                  IOException.class, () -> unix.create("b1", List.of(), "/tmp", "beta", "", 80, 24))
              .getMessage();
      assertEquals(
          "Project 'beta' is not in the catalog. Create it with 'sail project create beta', or"
              + " sync it from main with 'sail sync'.",
          hostSays);
      assertEquals("Project 'beta' is not in the catalog.", error.get("message"));
      assertTrue(hostSays.startsWith(error.get("message") + " "), hostSays);
    }
  }

  /** Scenario 7: a viewer is refused a ticket to attach another's session, as the host refuses. */
  @Test
  void aViewerIsRefusedATicketToAttachAnothersSessionAsTheTerminalHostRefuses() throws Exception {
    openOnTheSocket("lounge");

    var answer = post(vic, "{\"action\":\"attach\",\"session\":\"lounge\"}");

    assertEquals("403 forbidden Session 'lounge' is not yours to attach.", statusAndError(answer));
    try (var unix = SessionClient.connect(socket(), vic)) {
      assertEquals(
          "Session 'lounge' is not yours to attach.",
          assertThrows(IOException.class, () -> unix.attach("lounge", true)).getMessage());
    }
  }

  /** Scenario 8: the ticket opens a session over TCP, and frames flow both ways. */
  @Test
  void aTicketPresentedOnTcpOpensASessionAndFramesFlowBothWays() throws Exception {
    try (var tcp = present(ticket(ada, OPEN_ACME))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      PtyWire.write(tcp, new PtyMessage.Create("lounge", List.of(), "/tmp", "acme", "", 80, 24));
      assertEquals(new PtyMessage.Ok(), PtyWire.read(tcp));
      PtyWire.write(tcp, new PtyMessage.Attach("lounge", true));
      assertEquals(new PtyMessage.Ok(), PtyWire.read(tcp));

      PtyWire.write(tcp, new PtyMessage.Input(1, "over tcp\n".getBytes(StandardCharsets.UTF_8)));

      awaitOutput(tcp, "over tcp");
    }
  }

  /** Scenario 9: the same ticket presented again is refused as used. */
  @Test
  void aTicketPresentedTwiceIsRefusedTheSecondTime() throws Exception {
    var ticket = ticket(ada, OPEN_ACME);
    try (var first = present(ticket)) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(first));

      try (var second = present(ticket)) {
        assertEquals(
            new PtyMessage.Err(
                "This ticket was presented already, and a ticket admits one connection; ask the"
                    + " API for a new one."),
            PtyWire.read(second));
        assertClosed(second);
      }
    }
  }

  /** Scenario 10: a ticket older than its life is refused as expired. */
  @Test
  void aTicketOlderThanItsLifeIsRefusedAsExpired() throws Exception {
    var ticket = ticket(ada, OPEN_ACME);
    clock.addAndGet(PtyMessage.TICKET_LIFE.toNanos() + 1);

    try (var late = present(ticket)) {
      assertEquals(
          new PtyMessage.Err(
              "This ticket expired: a ticket is presented within 30 s of being issued; ask the"
                  + " API for a new one."),
          PtyWire.read(late));
      assertClosed(late);
    }
  }

  /** Scenario 11: a ticket to attach session A opens nothing and attaches nothing else. */
  @Test
  void aTicketToAttachOneSessionNeitherOpensInAProjectNorAttachesAnother() throws Exception {
    openOnTheSocket("lounge");
    openOnTheSocket("study");
    var attachLounge = "{\"action\":\"attach\",\"session\":\"lounge\"}";
    var refusal =
        new PtyMessage.Err(
            "This connection's ticket is to attach to session 'lounge', and it admits nothing"
                + " else; ask the API for a ticket for that.");

    try (var opening = present(ticket(ada, attachLounge))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(opening));
      PtyWire.write(opening, new PtyMessage.Create("den", List.of(), "/tmp", "acme", "", 80, 24));
      assertEquals(refusal, PtyWire.read(opening));
      assertClosed(opening);
    }
    try (var elsewhere = present(ticket(ada, attachLounge))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(elsewhere));
      PtyWire.write(elsewhere, new PtyMessage.Attach("study", true));
      assertEquals(refusal, PtyWire.read(elsewhere));
      assertClosed(elsewhere);
    }
    try (var opener = present(ticket(ada, OPEN_ACME))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(opener));
      PtyWire.write(opener, new PtyMessage.Attach("lounge", true));
      assertEquals(
          new PtyMessage.Err(
              "This connection's ticket is to open a session in project 'acme', and it admits"
                  + " nothing else; ask the API for a ticket for that."),
          PtyWire.read(opener));
    }
    assertEquals(2, host.sessionCount());
  }

  /** Scenario 12: a frame before a ticket, or garbage, is closed unanswered and touches nothing. */
  @Test
  void aFrameBeforeATicketOrGarbageIsClosedWithNothingServed() throws Exception {
    try (var early = SocketChannel.open(host.tcpAddress().orElseThrow())) {
      PtyWire.handshake(early, early);
      PtyWire.write(early, new PtyMessage.Create("sneak", List.of(), "/tmp", "acme", "", 80, 24));
      assertClosed(early);
    }
    try (var garbage = SocketChannel.open(host.tcpAddress().orElseThrow())) {
      readMagic(garbage);
      garbage.write(ByteBuffer.wrap("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
      assertClosed(garbage);
    }
    try (var forged = present("tkt_" + "0".repeat(64))) {
      assertEquals(
          new PtyMessage.Err("This is not a ticket this host issued; ask the API for a new one."),
          PtyWire.read(forged));
      assertClosed(forged);
    }
    assertEquals(0, host.sessionCount());
  }

  /** Scenario 13: revoking the grant severs the ticketed connection at the sweep, as today's. */
  @Test
  void revokingTheGrantSeversATicketedConnectionAtTheSweep() throws Exception {
    try (var tcp = present(ticket(ada, OPEN_ACME))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      PtyWire.write(tcp, new PtyMessage.Create("lounge", List.of(), "/tmp", "acme", "", 80, 24));
      assertEquals(new PtyMessage.Ok(), PtyWire.read(tcp));
      PtyWire.write(tcp, new PtyMessage.Attach("lounge", true));
      assertEquals(new PtyMessage.Ok(), PtyWire.read(tcp));

      roster.revoke("ada", "acme");
      host.sweep(clock.get());

      assertSevered(tcp);
    }
    assertEquals(404, post(ada, OPEN_ACME).statusCode());
  }

  /**
   * Scenario 14: an agent's run credential is no credential of the web door, and the socket offers
   * no ticket route: agents open no terminals this way.
   */
  @Test
  void anAgentsRunCredentialIsRefusedATicket() throws Exception {
    var run = runCredential();

    assertEquals(
        "403 invalid_bearer_token Bearer token is invalid.", statusAndError(post(run, OPEN_ACME)));
    var socket = new SocketDoor(operations).ask("POST", "/v1/terminal/tickets", run, OPEN_ACME);
    assertEquals(404, socket.status());
  }

  /**
   * Scenario 15: connections that never present a ticket are each closed at the deadline, hold at
   * most a ticket's frame meanwhile, and crowd out neither the unix socket nor a ticketed client.
   */
  @Test
  void connectionsThatNeverPresentATicketAreClosedAtTheDeadlineAndTheHostStaysResponsive()
      throws Exception {
    var silent = new ArrayList<SocketChannel>();
    try {
      for (var i = 0; i < 32; i++) {
        var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
        readMagic(channel);
        silent.add(channel);
      }
      try (var oversized = SocketChannel.open(host.tcpAddress().orElseThrow())) {
        PtyWire.handshake(oversized, oversized);
        oversized.write(ByteBuffer.allocate(4).putInt(1 << 20).flip());
        assertClosed(oversized);
      }
      try (var unix = SessionClient.connect(socket(), ada);
          var ticketed = present(ticket(ada, OPEN_ACME))) {
        unix.list();
        assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(ticketed));
      }

      host.reapUnticketed(clock.get() + deadline());

      for (var channel : silent) {
        assertTrue(channel.isOpen());
      }
      host.reapUnticketed(clock.get() + deadline() + 1);

      for (var channel : silent) {
        assertClosed(channel);
      }
    } finally {
      for (var channel : silent) {
        channel.close();
      }
    }
  }

  /**
   * N2: a ticket is a record of a decision, never a credential that outlives it: the host resolves
   * its person again when it is presented, so a person disabled since is refused.
   */
  @Test
  void aTicketWhosePersonWasDisabledSinceIsRefusedWhenPresented() throws Exception {
    var ticket = ticket(ada, OPEN_ACME);
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'ada'");

    try (var tcp = present(ticket)) {
      assertEquals(
          "FDE 'ada' is disabled or not in this box's roster. Ask an admin to re-enable it, or"
              + " run 'sail sync' on a node that has not synced its roster.",
          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp)).message());
      assertClosed(tcp);
    }
  }

  /**
   * N4: the box's own person, a blank token, exists only on the unix socket, so it is issued no
   * ticket; nor is a token that acts for no FDE, whose session would be no one's.
   */
  @Test
  void noTicketIsIssuedToTheBlankTokenOrToATokenThatActsForNoOne() throws Exception {
    assertEquals(
        "A ticket carries the credential it is asked with, and this connection presented none.",
        assertThrows(
                IOException.class,
                () -> SessionClient.ticket(socket(), "", PtyMessage.TicketAction.OPEN, "acme"))
            .getMessage());
    var machine = new TokenStore(db).create("ci", "admin").token();
    assertEquals(
        "403 forbidden Token 'ci' acts for no FDE, so no terminal session would be anyone's; use"
            + " a token of your own.",
        statusAndError(post(machine, OPEN_ACME)));
  }

  /** A request that names no action, or no valid target, is refused before the host is asked. */
  @Test
  void aRequestNamingNoActionOrNoValidTargetIsRefused() throws Exception {
    assertEquals(
        "422 invalid_request A ticket opens or attaches; 'shell' is neither.",
        statusAndError(post(ada, "{\"action\":\"shell\"}")));
    assertEquals(
        "422 invalid_request Invalid project name: ''. Must match [a-z0-9][a-z0-9-]*, max 63"
            + " characters.",
        statusAndError(post(ada, "{\"action\":\"open\",\"project\":\"\"}")));
  }

  private static long deadline() {
    return PtySessionHost.UNTICKETED_DEADLINE.toNanos();
  }

  private Path socket() {
    return dir.resolve("pty.sock");
  }

  private PtySessionHost terminalHost(PtySessionHost.Network network) {
    return terminalHost(network, socket());
  }

  /**
   * A terminal host on main's control plane, whose sessions in a project run {@code cat} under the
   * real shim: the container's {@code incus exec} that follows is the ignored arguments of {@code
   * sh -c}.
   */
  private PtySessionHost terminalHost(PtySessionHost.Network network, Path socket) {
    var pty = operations.pty();
    return new PtySessionHost(
        socket,
        dir.resolve("sessions-" + socket.getFileName()),
        64 * 1024,
        pty::identity,
        new PtyAdmission() {
          @Override
          public void project(String project, PtyIdentity.Person who) throws IOException {
            pty.admitProject(project, who);
          }

          @Override
          public void room(String room, String project, PtyIdentity.Person who) throws IOException {
            pty.admitRoom(room, project, who);
          }
        },
        PtyEvents.NONE,
        "0.0.0-test",
        new PtySessionHost.Handoff(
            SdNotify.NONE,
            Map.of(),
            exit ->
                Stream.concat(
                        PtyChildShim.onClassPath(exit).stream(),
                        Stream.of("sh", "-c", "exec cat", "container"))
                    .toList()),
        network);
  }

  private SailApiServer apiServer(SailApiServer.PrivateNetwork network) throws IOException {
    return new SailApiServer(
        "127.0.0.1",
        0,
        operations,
        TestAuth.sessions(db, MAIN),
        null,
        null,
        null,
        null,
        null,
        null,
        new RateLimitGate(),
        project -> null,
        SseHandler.RoomTurns.NONE,
        network);
  }

  /** Opens {@code name} in acme as ada, on the unix socket. */
  private void openOnTheSocket(String name) throws IOException {
    try (var unix = SessionClient.connect(socket(), ada)) {
      unix.create(name, List.of(), "/tmp", "acme", "", 80, 24);
    }
  }

  private String runCredential() {
    var id = DateTimeUtils.newId().toString();
    var reservation =
        Acting.system(
            () ->
                new RunStore(db)
                    .reserveDispatch(
                        id,
                        "acme",
                        "",
                        null,
                        "ada",
                        Lane.ADHOC.wire(),
                        List.of(),
                        "claude-code",
                        "feat/" + id,
                        "work",
                        "/tmp/" + id + ".log",
                        "sail-agent-" + id,
                        Duration.ofHours(1)));
    return ((RunStore.Reservation.Reserved) reservation).credential();
  }

  private HttpResponse<String> post(String bearer, String json) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + api.port() + "/v1/terminal/tickets"))
            .header("Authorization", "Bearer " + bearer)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private static HttpResponse<String> send(
      String base, String method, String path, String bearer, String json) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  /** The status of {@code response}, and its refusal's code and message. */
  private static String statusAndError(HttpResponse<String> response) {
    var error = (Map<?, ?>) YamlUtil.parseMap(response.body()).get("error");
    return response.statusCode() + " " + error.get("code") + " " + error.get("message");
  }

  private static String body(HttpResponse<String> response, int status) {
    assertEquals(status, response.statusCode(), response.body());
    return response.body();
  }

  private String ticket(String bearer, String json) throws Exception {
    return YamlUtil.parseMap(body(post(bearer, json), 201)).get("ticket").toString();
  }

  /** A TCP connection to the terminal host that presented {@code ticket}. */
  private SocketChannel present(String ticket) throws IOException {
    var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
    PtyWire.handshake(channel, channel);
    PtyWire.write(channel, new PtyMessage.Hello(ticket));
    return channel;
  }

  private static void readMagic(SocketChannel channel) throws IOException {
    var magic = ByteBuffer.allocate(8);
    while (magic.hasRemaining()) {
      if (channel.read(magic) < 0) {
        throw new IOException("closed before the magic");
      }
    }
  }

  /** The host closed {@code channel} with nothing more said. */
  private static void assertClosed(SocketChannel channel) throws IOException {
    try {
      assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
    } catch (IOException reset) {
      assertTrue(reset.getMessage() != null, reset.toString());
    }
  }

  /** The host severed {@code channel}: whatever it had queued, then the end. */
  private static void assertSevered(SocketChannel channel) {
    assertThrows(
        IOException.class,
        () -> {
          while (true) {
            PtyWire.read(channel);
          }
        });
  }

  private static void awaitOutput(SocketChannel channel, String text) throws IOException {
    var seen = new StringBuilder();
    while (!seen.toString().contains(text)) {
      if (PtyWire.read(channel) instanceof PtyMessage.Output(var seq, var bytes)) {
        seen.append(new String(bytes, StandardCharsets.UTF_8));
      }
    }
  }
}
