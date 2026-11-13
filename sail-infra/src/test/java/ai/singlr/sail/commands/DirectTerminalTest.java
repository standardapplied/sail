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
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyChildShim;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.PtyWire;
import ai.singlr.sail.pty.SdNotify;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A client on the box's private network reaches the API and a terminal with no SSH: the API on its
 * private address answers as on loopback, a ticket asked of it is decided by the terminal host as
 * that host decides the open or attach, and the ticket, presented on the host's TCP listener,
 * admits one connection for that one thing, speaking the unix socket's frames. Each scenario runs
 * through the real router and a real terminal host over loopback TCP, on a node whose own person is
 * ada, a member granted acme alone; bob, a member granted acme, and vic, a viewer, are on its
 * roster but do not operate it. A session the host opens in a project runs {@code cat} under the
 * real session shim in place of the container's command, which needs an Incus. Time runs on the
 * host's injected clock; every wait is bounded by the class's timeout.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DirectTerminalTest {

  private static final SyncConfig NODE =
      new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "ada", "node-box");
  private static final InetSocketAddress ANY_LOOPBACK_PORT =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
  private static final String OPEN_ACME = "{\"action\":\"open\",\"project\":\"acme\"}";
  private static final String ATTACH_LOUNGE = "{\"action\":\"attach\",\"session\":\"lounge\"}";
  private static final String FORGED = "tkt_" + "0".repeat(64);
  private static final String NOT_ISSUED =
      "This is not a ticket this host issued; ask the API for a new one.";

  @TempDir Path dir;
  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private Sqlite db;
  private FdeStore roster;
  private SailOperations operations;
  private PtySessionHost host;
  private SailApiServer api;
  private String ada;
  private String bob;
  private String vic;

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    roster = new FdeStore(db);
    var adaRow = roster.add("ada", "Ada", "ada@x.dev", "member");
    var bobRow = roster.add("bob", "Bob", "bob@x.dev", "member");
    var vicRow = roster.add("vic", "Vic", "vic@x.dev", "viewer");
    Acting.system(
        () -> {
          var projects = new ProjectStore(db);
          projects.upsert(TestDefinitions.named("acme"));
          projects.upsert(TestDefinitions.named("beta"));
        });
    for (var handle : List.of("ada", "bob")) {
      roster.restrict(handle);
      roster.grant(handle, "acme");
    }
    ada = token("ada-mac", "member", adaRow.id());
    bob = token("bob-mac", "member", bobRow.id());
    vic = token("vic-mac", "viewer", vicRow.id());
    operations =
        DoorBox.wired(
            OperationsFactory.create(
                db, DoorBox.acmeRuns(), null, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            dir,
            NODE);
    host = terminalHost(network(ANY_LOOPBACK_PORT), socket());
    host.start();
    api = apiServer(Optional.of(ANY_LOOPBACK_PORT), host.tcpAddress().orElseThrow(), socket());
    api.start();
  }

  @AfterEach
  void tearDown() {
    api.close();
    host.close();
    operations.close();
    db.close();
  }

  /**
   * Scenario 1 and N1: with neither setting nothing more is bound, the ticket route is no route,
   * and the unix socket answers as on main: an API token is no credential of its hello.
   */
  @Test
  void withNeitherSettingNothingMoreIsBoundAndEveryAnswerIsMains() throws Exception {
    var alone = dir.resolve("alone.sock");
    var hostAlone = terminalHost(PtySessionHost.Network.NONE, alone);
    hostAlone.start();
    try (hostAlone;
        var apiAlone = apiServer(Optional.empty(), null, alone)) {
      apiAlone.start();
      var base = "http://127.0.0.1:" + apiAlone.port();

      assertEquals(Optional.empty(), hostAlone.tcpAddress());
      assertEquals(Optional.empty(), apiAlone.privateAddress());
      var absent = "404 not_found API endpoint was not found.";
      assertEquals(
          absent, statusAndError(send(base, "POST", "/v1/terminal/tickets", ada, OPEN_ACME)));
      assertEquals(absent, statusAndError(send(base, "GET", "/v1/terminal/tickets", ada, null)));
      assertEquals(
          "Session token is not valid or has expired.",
          assertThrows(IOException.class, () -> SessionClient.connect(alone, ada).close())
              .getMessage());
      assertEquals(
          new PtyMessage.TicketRefused(
              PtyMessage.TicketRefusal.UNAVAILABLE,
              "This host listens for no terminal over the network. Name an address with 'sail"
                  + " host config set terminal-listen <address>:<port>' and restart the"
                  + " sail-pty-host service."),
          plain(SessionClient.ticket(alone, ada, PtyMessage.TicketAction.OPEN, "acme")));
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

  /** Scenario 4 and N6: a wildcard is refused naming its setting, before anything binds. */
  @Test
  void aWildcardAddressIsRefusedNamingItsSetting() {
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
  }

  /**
   * N6: an address that cannot be bound yet takes nothing down. Loopback and the unix socket serve;
   * the private doors are not listening, and a ticket asked meanwhile is the host's outage; once
   * the address is free each binds at its next tick.
   */
  @Test
  void anAddressNotUpYetTakesNothingDownAndIsBoundOnceItIs() throws Exception {
    var heldApi = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    var heldTerminal = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    var apiAddress =
        new InetSocketAddress(InetAddress.getLoopbackAddress(), heldApi.getLocalPort());
    var terminalAddress =
        new InetSocketAddress(InetAddress.getLoopbackAddress(), heldTerminal.getLocalPort());
    var waiting = Files.createDirectories(dir.resolve("waiting")).resolve("host.sock");
    var apiSays = SailApiServer.listeningOf(waiting.resolveSibling("api.sock"));
    var terminalSays = PtySessionHost.listeningOf(waiting);
    var hostWaiting = terminalHost(network(terminalAddress), waiting);
    hostWaiting.start();
    try (hostWaiting;
        var apiWaiting = apiServer(Optional.of(apiAddress), terminalAddress, waiting)) {
      apiWaiting.start();
      var loopback = "http://127.0.0.1:" + apiWaiting.port();

      assertEquals(200, send(loopback, "GET", "/v1/whoami", ada, null).statusCode());
      assertEquals(Optional.empty(), apiWaiting.privateAddress());
      assertEquals(Optional.empty(), hostWaiting.tcpAddress());
      assertEquals(Optional.empty(), ListenConfig.said(apiSays));
      assertEquals(Optional.empty(), ListenConfig.said(terminalSays));
      try (var unix = SessionClient.connect(waiting, "")) {
        unix.list();
      }
      assertEquals(
          "503 terminal_unavailable The terminal's private address ('terminal-listen') is not"
              + " listening yet; ask again once the private network is up.",
          statusAndError(send(loopback, "POST", "/v1/terminal/tickets", ada, OPEN_ACME)));

      heldApi.close();
      heldTerminal.close();
      apiWaiting.tick(System.nanoTime() + Duration.ofMinutes(2).toNanos());
      hostWaiting.tick(clock.get() + Duration.ofMinutes(2).toNanos());

      assertEquals(Optional.of(apiAddress), apiWaiting.privateAddress());
      assertEquals(Optional.of(terminalAddress), hostWaiting.tcpAddress());
      assertEquals(Optional.of(ListenConfig.hostPort(apiAddress)), ListenConfig.said(apiSays));
      assertEquals(
          Optional.of(ListenConfig.hostPort(terminalAddress)), ListenConfig.said(terminalSays));
      var privately = "http://" + ListenConfig.hostPort(apiAddress);
      assertEquals(
          "ada",
          YamlUtil.parseMap(send(privately, "GET", "/v1/whoami", ada, null).body()).get("fde"));
      var late =
          YamlUtil.parseMap(
              body(send(privately, "POST", "/v1/terminal/tickets", ada, OPEN_ACME), 201));
      try (var tcp = SocketChannel.open(terminalAddress)) {
        PtyWire.handshake(tcp, tcp);
        PtyWire.write(tcp, new PtyMessage.Hello(late.get("ticket").toString()));
        assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      }
    }
    assertEquals(Optional.empty(), ListenConfig.said(apiSays), "a stopped API says no more");
    assertEquals(Optional.empty(), ListenConfig.said(terminalSays), "nor a stopped host");
  }

  /**
   * Scenario 5: the box's own person, holding acme, is answered a ticket and where to present it.
   */
  @Test
  void theBoxsOwnPersonIsAnsweredATicketToOpenInAProjectItHolds() throws Exception {
    var answer = YamlUtil.parseMap(body(post(ada, OPEN_ACME), 201));

    assertTrue(answer.get("ticket").toString().matches("tkt_[0-9a-f]{64}"), answer.toString());
    assertEquals(ListenConfig.hostPort(host.tcpAddress().orElseThrow()), answer.get("address"));
    assertEquals(
        DateTimeUtils.format(DateTimeUtils.now().plus(PtyMessage.TICKET_LIFE)).substring(0, 16),
        answer.get("expires_at").toString().substring(0, 16));
  }

  /**
   * Scenario 5b and N7: a member or a viewer who holds acme but does not operate this box is
   * refused, as the unix socket refuses them: opening a terminal needs the project on your own box.
   */
  @Test
  void aMemberOrViewerWhoDoesNotOperateThisBoxOpensNothingOnIt() throws Exception {
    for (var other : List.of(bob, vic)) {
      var refused = post(other, OPEN_ACME);
      assertEquals(403, refused.statusCode(), refused.body());
      assertEquals("forbidden", error(refused).get("code"));
    }
    var bobSession = new FdeStore(db).byHandle("bob").orElseThrow();
    var session = new AuthSessionStore(db).create(bobSession.id(), Duration.ofHours(1));
    try (var unix = SessionClient.connect(socket(), session.token())) {
      var unixSays =
          assertThrows(
                  IOException.class,
                  () -> unix.create("bobs", List.of(), "/tmp", "acme", "", 80, 24))
              .getMessage();
      assertEquals(unixSays, error(post(bob, OPEN_ACME)).get("message"));
    }
    assertEquals(0, host.sessionCount());
  }

  /** Scenario 6: a project the caller does not hold is not found, in the terminal host's words. */
  @Test
  void aProjectTheCallerDoesNotHoldIsNotFoundInTheTerminalHostsWords() throws Exception {
    var answer = post(ada, "{\"action\":\"open\",\"project\":\"beta\"}");

    try (var unix = SessionClient.connect(socket(), "")) {
      var hostSays =
          assertThrows(
                  IOException.class, () -> unix.create("b1", List.of(), "/tmp", "beta", "", 80, 24))
              .getMessage();
      assertEquals(
          "Project 'beta' is not in the catalog. Create it with 'sail project create beta', or"
              + " sync it from main with 'sail sync'.",
          hostSays);
      assertEquals("404 not_found " + hostSays, statusAndError(answer));
    }
  }

  /**
   * Scenario 7: a viewer is refused a ticket to attach another's session, and hears it only as a
   * session it may not attach: the network does not tell one that exists from one that does not.
   */
  @Test
  void aTicketToAttachAnothersSessionIsRefusedAsNotFoundLikeOneThatDoesNotExist() throws Exception {
    openOnTheSocket("lounge");

    assertEquals(
        "404 not_found There is no session 'lounge' on this box that you may attach.",
        statusAndError(post(vic, ATTACH_LOUNGE)));
    assertEquals(
        "404 not_found There is no session 'ghost' on this box that you may attach.",
        statusAndError(post(vic, "{\"action\":\"attach\",\"session\":\"ghost\"}")));
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

  /** N3: one ticket presented on many connections at once admits exactly one of them. */
  @Test
  void oneTicketPresentedOnManyConnectionsAtOnceAdmitsExactlyOne() throws Exception {
    for (var round = 0; round < 20; round++) {
      var ticket = ticket(ada, OPEN_ACME);
      var racers = new ArrayList<SocketChannel>();
      for (var i = 0; i < 6; i++) {
        var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
        PtyWire.handshake(channel, channel);
        racers.add(channel);
      }
      var go = new CountDownLatch(1);
      try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        var answers = new ArrayList<Future<PtyMessage>>();
        for (var channel : racers) {
          answers.add(
              pool.submit(
                  () -> {
                    go.await();
                    PtyWire.write(channel, new PtyMessage.Hello(ticket));
                    return PtyWire.read(channel);
                  }));
        }
        go.countDown();
        var welcomed = 0;
        for (var answer : answers) {
          if (answer.get(30, TimeUnit.SECONDS) instanceof PtyMessage.Welcome) {
            welcomed++;
          }
        }
        assertEquals(1, welcomed, "round " + round);
      } finally {
        for (var channel : racers) {
          channel.close();
        }
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
    var refusal =
        new PtyMessage.Err(
            "This connection's ticket is to attach to session 'lounge', and it admits nothing"
                + " else; ask the API for a ticket for that.");

    try (var opening = present(ticket(ada, ATTACH_LOUNGE))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(opening));
      PtyWire.write(opening, new PtyMessage.Create("den", List.of(), "/tmp", "acme", "", 80, 24));
      assertEquals(refusal, PtyWire.read(opening));
      assertClosed(opening);
    }
    try (var elsewhere = present(ticket(ada, ATTACH_LOUNGE))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(elsewhere));
      PtyWire.write(elsewhere, new PtyMessage.Attach("study", true));
      assertEquals(refusal, PtyWire.read(elsewhere));
      assertClosed(elsewhere);
    }
    assertEquals(2, host.sessionCount());
  }

  /**
   * After an open ticket, no lateral move: each frame outside it closes the connection, and a
   * second create is refused.
   */
  @Test
  void anOpenTicketAdmitsOneCreateInItsProjectAndNoLateralMove() throws Exception {
    openOnTheSocket("adas");
    var lateral =
        List.<PtyMessage>of(
            new PtyMessage.Kill("adas"),
            new PtyMessage.Create("b1", List.of(), "/tmp", "beta", "", 80, 24),
            new PtyMessage.Create("h1", List.of(), "/tmp", "", "", 80, 24),
            new PtyMessage.Attach("adas", true),
            new PtyMessage.ListSessions("", 16),
            new PtyMessage.Yield("adas", "x"),
            new PtyMessage.IssueTicket(PtyMessage.TicketAction.OPEN, "acme", ada),
            new PtyMessage.Hello(""));
    for (var frame : lateral) {
      try (var tcp = present(ticket(ada, OPEN_ACME))) {
        assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
        PtyWire.write(tcp, frame);
        assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp), frame.toString());
        assertClosed(tcp);
      }
    }
    try (var tcp = present(ticket(ada, OPEN_ACME))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      PtyWire.write(tcp, new PtyMessage.Resize(10, 10));
      PtyWire.write(tcp, new PtyMessage.Create("c1", List.of(), "/tmp", "acme", "", 80, 24));
      assertEquals(new PtyMessage.Ok(), PtyWire.read(tcp));
      PtyWire.write(tcp, new PtyMessage.Create("c2", List.of(), "/tmp", "acme", "", 80, 24));
      assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp), "a second create");
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
    try (var forged = present(FORGED)) {
      assertEquals(new PtyMessage.Err(NOT_ISSUED), PtyWire.read(forged));
      assertClosed(forged);
    }
    assertEquals(0, host.sessionCount());
  }

  /** A ticket split into one-byte writes is still read whole, within the deadline. */
  @Test
  void aTicketTrickledOneByteAtATimeIsRead() throws Exception {
    var ticket = ticket(ada, OPEN_ACME);
    try (var tcp = SocketChannel.open(host.tcpAddress().orElseThrow())) {
      PtyWire.handshake(tcp, tcp);
      for (var b : framed(new PtyMessage.Hello(ticket))) {
        tcp.write(ByteBuffer.wrap(new byte[] {b}));
      }
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
    }
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

  /** N2: the sweep severs a ticketed connection once its person is disabled. */
  @Test
  void theSweepSeversATicketedConnectionOnceItsPersonIsDisabled() throws Exception {
    try (var tcp = present(ticket(ada, OPEN_ACME))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'ada'");

      host.sweep(clock.get());

      assertSevered(tcp);
    }
  }

  /**
   * N2: a ticket is a record of a decision, never a credential that outlives it: a person disabled
   * or a token revoked since is refused when it is presented, and a grant revoked since opens and
   * attaches nothing, each frame asking the one decision again.
   */
  @Test
  void aTicketWhosePersonTokenOrGrantChangedSinceAdmitsNothing() throws Exception {
    var disabledSince = ticket(ada, OPEN_ACME);
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'ada'");
    try (var tcp = present(disabledSince)) {
      assertEquals(
          "FDE 'ada' is disabled or not in this box's roster. Ask an admin to re-enable it, or"
              + " run 'sail sync' on a node that has not synced its roster.",
          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp)).message());
      assertClosed(tcp);
    }
    db.execute("UPDATE fdes SET status = 'active' WHERE handle = 'ada'");

    var revokedSince = ticket(ada, OPEN_ACME);
    new TokenStore(db).revoke("ada-mac");
    try (var tcp = present(revokedSince)) {
      assertEquals(
          "The credential is not valid or has expired.",
          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp)).message());
      assertClosed(tcp);
    }
  }

  /** N2: a grant revoked after the ticket was issued: the create and the attach are refused. */
  @Test
  void aGrantRevokedAfterTheTicketOpensAndAttachesNothing() throws Exception {
    openOnTheSocket("lounge");
    var open = ticket(ada, OPEN_ACME);
    var attach = ticket(ada, ATTACH_LOUNGE);
    roster.revoke("ada", "acme");

    try (var tcp = present(open)) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      PtyWire.write(tcp, new PtyMessage.Create("late", List.of(), "/tmp", "acme", "", 80, 24));
      assertEquals(
          "Project 'acme' is not in the catalog. Create it with 'sail project create acme', or"
              + " sync it from main with 'sail sync'.",
          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp)).message());
    }
    try (var tcp = present(attach)) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      PtyWire.write(tcp, new PtyMessage.Attach("lounge", true));
      assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp));
    }
    assertEquals(1, host.sessionCount());
  }

  /**
   * Scenario 14, M4 and N4: an agent's run credential, the box credential and no credential are
   * refused at both doors, from any peer, a project container's included, so agents open no
   * terminals this way; and the TCP door takes nothing but a ticket, an FDE's API token and login
   * session refused there as any other token is.
   */
  @Test
  void aRunCredentialTheBoxCredentialOrNoneIsRefusedAtBothDoorsAndTheTcpDoorTakesOnlyATicket()
      throws Exception {
    var run = runCredential();
    var box = new BoxCredentialStore(db).replace("ada");

    assertEquals(
        "403 invalid_bearer_token Bearer token is invalid.", statusAndError(post(run, OPEN_ACME)));
    assertEquals(
        "403 invalid_bearer_token Bearer token is invalid.", statusAndError(post(box, OPEN_ACME)));
    assertEquals(
        "401 missing_bearer_token Missing bearer token.", statusAndError(post(null, OPEN_ACME)));
    assertEquals(
        404,
        new SocketDoor(operations).ask("POST", "/v1/terminal/tickets", run, OPEN_ACME).status());
    var login =
        new AuthSessionStore(db)
            .create(roster.byHandle("ada").orElseThrow().id(), Duration.ofHours(1))
            .token();
    for (var credential : List.of(run, box, "", ada, login)) {
      try (var tcp = present(credential)) {
        assertEquals(new PtyMessage.Err(NOT_ISSUED), PtyWire.read(tcp));
        assertClosed(tcp);
      }
    }
    for (var credential : List.of(run, box)) {
      assertThrows(IOException.class, () -> SessionClient.connect(socket(), credential).close());
    }
  }

  /**
   * A ticket is asked by the box's own process alone, on a connection that presented no token: on a
   * connection a session token opened, it is refused, whoever's credential it carries.
   */
  @Test
  void aTicketAskedOnAConnectionThatPresentedATokenIsRefused() throws Exception {
    var bobSession =
        new AuthSessionStore(db)
            .create(roster.byHandle("bob").orElseThrow().id(), Duration.ofHours(1))
            .token();
    try (var channel = SocketChannel.open(UnixDomainSocketAddress.of(socket()))) {
      PtyWire.handshake(channel, channel);
      PtyWire.write(channel, new PtyMessage.Hello(bobSession));
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
      PtyWire.write(channel, new PtyMessage.IssueTicket(PtyMessage.TicketAction.OPEN, "acme", ada));

      assertEquals(
          new PtyMessage.TicketRefused(
              PtyMessage.TicketRefusal.FORBIDDEN,
              "A ticket is asked by this box's own process alone, on a connection that presented"
                  + " no token."),
          plain(PtyWire.read(channel)));
    }
  }

  /**
   * Major A of review 2: one peer holding silent connections from many addresses fills no more than
   * the pool of connections with no ticket, whose oldest is let go for a newcomer, so a ticketed
   * client still gets in. On Linux, where every 127.0.0.0/8 address is the loopback.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void onePeerWithManyAddressesCannotLockTheDoorAgainstATicketedClient() throws Exception {
    var ticket = ticket(ada, OPEN_ACME);
    var silent = new ArrayList<SocketChannel>();
    try {
      for (var a = 10; a < 43; a++) {
        for (var i = 0; i < 8; i++) {
          var channel = SocketChannel.open();
          channel.bind(new InetSocketAddress(InetAddress.ofLiteral("127.0.0." + a), 0));
          channel.connect(host.tcpAddress().orElseThrow());
          silent.add(channel);
        }
      }
      for (var channel : silent) {
        var magic = ByteBuffer.allocate(8);
        var read = 0;
        while (magic.hasRemaining() && read >= 0) {
          read = channel.read(magic);
        }
      }

      try (var tcp = present(ticket)) {
        assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      }
    } finally {
      for (var channel : silent) {
        channel.close();
      }
    }
  }

  /**
   * N2: a login session ended since the ticket was issued admits nothing, and one ended while its
   * ticketed connection is open is severed at the sweep.
   */
  @Test
  void aLoginSessionEndedSinceTheTicketAdmitsNothingAndIsSevered() throws Exception {
    var adaRow = roster.byHandle("ada").orElseThrow();
    var sessions = new AuthSessionStore(db);
    var early = ticket(sessions.create(adaRow.id(), Duration.ofHours(1)).token(), OPEN_ACME);
    db.execute("DELETE FROM sessions WHERE fde_id = ?", adaRow.id());
    try (var tcp = present(early)) {
      assertEquals(
          "The credential is not valid or has expired.",
          assertInstanceOf(PtyMessage.Err.class, PtyWire.read(tcp)).message());
    }
    var again = sessions.create(adaRow.id(), Duration.ofHours(1)).token();
    try (var tcp = present(ticket(again, OPEN_ACME))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      db.execute("DELETE FROM sessions WHERE fde_id = ?", adaRow.id());

      host.sweep(clock.get());

      assertSevered(tcp);
    }
  }

  /**
   * Minor 1 of review 1: every refusal of a ticket to attach is one answer, byte for byte, whether
   * the session is absent, not the caller's, or in a project the caller does not hold.
   */
  @Test
  void everyRefusalOfATicketToAttachIsOneAnswerByteForByte() throws Exception {
    roster.grant("ada", "beta");
    try (var unix = SessionClient.connect(socket(), "")) {
      unix.create("secret", List.of(), "/tmp", "beta", "", 80, 24);
      unix.create("lounge", List.of(), "/tmp", "acme", "", 80, 24);
    }
    roster.revoke("ada", "beta");
    var shapes = new ArrayList<String>();
    for (var asked :
        List.of(
            Map.entry("secret", post(ada, "{\"action\":\"attach\",\"session\":\"secret\"}")),
            Map.entry("lounge", post(vic, ATTACH_LOUNGE)),
            Map.entry("ghost1", post(vic, "{\"action\":\"attach\",\"session\":\"ghost1\"}")),
            Map.entry("ghost1", post(ada, "{\"action\":\"attach\",\"session\":\"ghost1\"}")))) {
      var answer = asked.getValue();
      shapes.add(
          answer.statusCode()
              + " "
              + answer.headers().firstValue("content-length").orElse("?")
              + " "
              + answer.body().replace(asked.getKey(), "NAME"));
    }

    assertEquals(1, shapes.stream().distinct().count(), shapes.toString());
  }

  /**
   * Scenario 15: connections that never present a ticket are each closed at the deadline, counted
   * from the connect and not reset by a trickle, hold at most a ticket's frame meanwhile, and crowd
   * out neither the unix socket nor a ticketed client.
   */
  @Test
  void connectionsThatNeverPresentATicketAreClosedAtTheDeadlineAndTheHostStaysResponsive()
      throws Exception {
    var silent = new ArrayList<SocketChannel>();
    try {
      for (var i = 0; i < 4; i++) {
        var channel = SocketChannel.open(host.tcpAddress().orElseThrow());
        readMagic(channel);
        silent.add(channel);
      }
      var trickling = SocketChannel.open(host.tcpAddress().orElseThrow());
      silent.add(trickling);
      PtyWire.handshake(trickling, trickling);
      trickling.write(ByteBuffer.wrap(new byte[] {0, 0}));
      try (var oversized = SocketChannel.open(host.tcpAddress().orElseThrow())) {
        PtyWire.handshake(oversized, oversized);
        oversized.write(ByteBuffer.allocate(4).putInt(1 << 20).flip());
        assertClosed(oversized);
      }
      try (var unix = SessionClient.connect(socket(), "");
          var ticketed = present(ticket(ada, OPEN_ACME))) {
        unix.list();
        assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(ticketed));
      }

      clock.addAndGet(deadline() / 2);
      trickling.write(ByteBuffer.wrap(new byte[] {0}));
      host.tick(clock.get() + deadline() / 2);

      for (var channel : silent) {
        assertTrue(channel.isOpen());
      }
      host.tick(clock.get() + deadline() / 2 + 1);

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
   * N8: one peer holding silent connections does not lock another out; on Linux, where every
   * 127.0.0.0/8 address is the loopback, a second peer address is at hand.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void onePeerHoldingSilentConnectionsDoesNotLockAnotherPeerOut() throws Exception {
    var silent = new ArrayList<SocketChannel>();
    try {
      for (var i = 0; i < 20; i++) {
        var channel = SocketChannel.open();
        channel.bind(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.2"), 0));
        channel.connect(host.tcpAddress().orElseThrow());
        silent.add(channel);
      }
      var served = 0;
      for (var channel : silent) {
        if (channel.read(ByteBuffer.allocate(8)) > 0) {
          served++;
        }
      }
      assertEquals(8, served, "one peer holds at most 8 connections that presented no ticket");
      try (var tcp = present(ticket(ada, OPEN_ACME))) {
        assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      }
    } finally {
      for (var channel : silent) {
        channel.close();
      }
    }
  }

  /**
   * N8: one credential holds at most 8 tickets not yet presented; the ninth is a cap reached, and
   * another credential, its own person's other token included, is still served.
   */
  @Test
  void oneCredentialCannotKeepAnotherFromATicket() throws Exception {
    for (var i = 0; i < 8; i++) {
      assertEquals(201, post(ada, OPEN_ACME).statusCode(), "ticket " + i);
    }
    assertEquals(
        "429 rate_limited This credential holds 8 tickets not yet presented; present one or let it"
            + " expire, then ask again.",
        statusAndError(post(ada, OPEN_ACME)));
    var adaRow = roster.byHandle("ada").orElseThrow();
    var other = token("ada-phone", "member", adaRow.id());
    assertEquals(201, post(other, OPEN_ACME).statusCode());
  }

  /**
   * N4: the box's own person exists only on the unix socket, so no ticket is issued for no
   * credential, for the host's own token, or for a token that acts for no FDE.
   */
  @Test
  void noTicketIsIssuedForNoCredentialTheHostTokenOrAMachineToken() throws Exception {
    assertEquals(
        new PtyMessage.TicketRefused(
            PtyMessage.TicketRefusal.FORBIDDEN,
            "A ticket is issued for the credential that asks for it, and this request carries none:"
                + " the box's own person exists only on the unix socket."),
        plain(SessionClient.ticket(socket(), "", PtyMessage.TicketAction.OPEN, "acme")));
    var hostToken = new TokenStore(db).create(HostToken.NAME, "admin").token();
    var machine = new TokenStore(db).create("ci", "admin").token();
    assertEquals(
        "403 forbidden Token 'host' is bound to no FDE, so it is issued no ticket: the host's own"
            + " token is the box's person, who exists only on the unix socket, and a machine token"
            + " acts for no one. Use a token of your own.",
        statusAndError(post(hostToken, OPEN_ACME)).replace(HostToken.NAME, "host"));
    assertEquals(403, post(machine, OPEN_ACME).statusCode());
  }

  /** M5: a ticketed connection that ends releases its attachment and its write token. */
  @Test
  void aTicketedConnectionThatEndsReleasesItsAttachmentAndWriteToken() throws Exception {
    try (var tcp = present(ticket(ada, OPEN_ACME))) {
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(tcp));
      PtyWire.write(tcp, new PtyMessage.Create("lounge", List.of(), "/tmp", "acme", "", 80, 24));
      assertEquals(new PtyMessage.Ok(), PtyWire.read(tcp));
      PtyWire.write(tcp, new PtyMessage.Attach("lounge", true));
      assertEquals(new PtyMessage.Ok(), PtyWire.read(tcp));
    }
    try (var unix = SessionClient.connect(socket(), "")) {
      var listed = unix.list().getFirst();
      while (listed.attached() > 0 || !listed.writerFde().isEmpty()) {
        listed = unix.list().getFirst();
      }
      assertEquals(0, listed.attached());
      assertEquals("", listed.writerFde());
    }
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

  private String token(String name, String role, String fdeId) {
    return new TokenStore(db).create(name, role, fdeId, null).token();
  }

  private PtySessionHost.Network network(InetSocketAddress address) {
    return PtySessionHost.Network.listening(address, clock::get, operations.pty()::holder);
  }

  /**
   * A terminal host on the node's control plane, whose sessions in a project run {@code cat} under
   * the real shim: the container's {@code incus exec} that follows is the ignored arguments of
   * {@code sh -c}.
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
          public void box(PtyIdentity.Person who) throws IOException {
            pty.admitBox(who);
          }

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

  /**
   * The API, on loopback and on {@code privately} when named, asking the host at {@code socket} for
   * tickets presented at {@code terminal}, when named.
   */
  private SailApiServer apiServer(
      Optional<InetSocketAddress> privately, InetSocketAddress terminal, Path socket)
      throws IOException {
    return new SailApiServer(
        "127.0.0.1",
        0,
        operations,
        TestAuth.sessions(db, NODE),
        null,
        null,
        socket.resolveSibling("api.sock"),
        null,
        null,
        null,
        new RateLimitGate(),
        project -> null,
        SseHandler.RoomTurns.NONE,
        new SailApiServer.PrivateNetwork(
            privately,
            Optional.ofNullable(terminal)
                .map(
                    address ->
                        new TerminalTickets(
                            (credential, action, target) ->
                                SessionClient.ticket(socket, credential, action, target),
                            address))));
  }

  /** Opens {@code name} in acme as the box's own person, on the unix socket. */
  private void openOnTheSocket(String name) throws IOException {
    try (var unix = SessionClient.connect(socket(), "")) {
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
    return send(
        "http://" + ListenConfig.hostPort(api.privateAddress().orElseThrow()),
        "POST",
        "/v1/terminal/tickets",
        bearer,
        json);
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

  private static Map<?, ?> error(HttpResponse<String> response) {
    return (Map<?, ?>) YamlUtil.parseMap(response.body()).get("error");
  }

  /** The status of {@code response}, and its refusal's code and message. */
  private static String statusAndError(HttpResponse<String> response) {
    var error = error(response);
    return response.statusCode() + " " + error.get("code") + " " + error.get("message");
  }

  private static String body(HttpResponse<String> response, int status) {
    assertEquals(status, response.statusCode(), response.body());
    return response.body();
  }

  /** {@code answer}, a refusal's words read as plain words, so it compares by what it says. */
  private static PtyMessage plain(PtyMessage answer) {
    return answer instanceof PtyMessage.TicketRefused(var kind, var detail)
        ? new PtyMessage.TicketRefused(kind, detail.plain())
        : answer;
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

  private static byte[] framed(PtyMessage message) throws IOException {
    var sink = new ByteArrayOutputStream();
    PtyWire.write(Channels.newChannel(sink), message);
    return sink.toByteArray();
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
