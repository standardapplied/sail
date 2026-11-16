/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.AuthorizedKeysRenderer;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BoxDatabase;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TestLogins;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncSession;
import ai.singlr.sail.sync.SyncWire;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Main's {@code _sync} server as a token resolves it: the session of a scoped FDE is served its
 * granted projects and the roster of the FDEs who share them, and a session that names no FDE is
 * served nothing.
 */
class ScopedSyncServerTest {

  @TempDir Path tempDir;
  private SyncDatabase main;
  private Sqlite mainDb;
  private Sqlite nodeDb;
  private FdeStore fdes;

  @BeforeEach
  void setUp() {
    var mainPath = tempDir.resolve("main.db");
    try (var db = Sqlite.open(mainPath)) {
      BoxDatabase.prepare(db);
    }
    main = SyncDatabase.converge(mainPath, "main");
    mainDb = main.db();
    nodeDb = Sqlite.open(tempDir.resolve("node.db"));
    new SchemaManager(nodeDb).migrate();
    fdes = new FdeStore(mainDb);
    fdes.add("ada", "Ada", "ada@x.dev", "admin");
    fdes.add("uma", "Uma", "uma@x.dev", "member");
    for (var scoped : List.of("nia", "sam", "yan", "bob")) {
      fdes.add(scoped, scoped, scoped + "@x.dev", "member");
      fdes.restrict(scoped);
    }
    fdes.grant("nia", "alpha");
    fdes.grant("sam", "alpha");
    fdes.grant("sam", "beta");
    fdes.grant("yan", "beta");
    Acting.system(
        () -> {
          var specs = new SpecStore(mainDb);
          specs.create(spec("a1", "alpha", "sam"));
          specs.create(spec("b1", "beta", "yan"));
        });
  }

  @AfterEach
  void tearDown() {
    nodeDb.close();
    main.close();
  }

  @Test
  void aScopedFdesSessionPullsItsProjectsAndTheRosterOfThoseWhoShareThem() throws Exception {
    var node = new FdeStore(nodeDb);

    var round = roundAs(tokenOf("nia"));

    assertEquals(ProjectScope.of(List.of("alpha")), round.scope());
    assertEquals(List.of("a1"), nodeDb.query("SELECT id FROM specs", row -> row.text(0)));
    assertEquals(
        List.of(
            entry("ada", "Ada", "ada@x.dev", "admin"),
            entry("nia", "nia", "nia@x.dev", "member"),
            entry("sam", "sam", "sam@x.dev", "member"),
            entry("uma", "Uma", "uma@x.dev", "member")),
        listed(round));
    assertEquals(
        List.of(Set.of("id"), Set.of("id")),
        round.roster().stream().filter(fde -> !fde.containsKey("handle")).map(Map::keySet).toList(),
        "everyone else main holds is said by id alone");

    assertEquals(List.of(), SyncOperations.applyFdes(node, round.roster(), "nia", notice -> {}));

    assertTrue(node.byHandle("sam").orElseThrow().active(), "an assignee in alpha stays enabled");
    assertTrue(node.byHandle("ada").orElseThrow().active());
    assertTrue(node.byHandle("yan").isEmpty(), "no one outside alpha is on a scoped box");
    assertEquals(
        "sam@x.dev",
        node.byHandle("sam").orElseThrow().email(),
        "a person who shares its projects commits there under their email");
    assertEquals("nia@x.dev", node.byHandle("nia").orElseThrow().email());
    assertEquals(ProjectScope.all(), node.scopeOf("nia"), "a node scopes no one");
  }

  /**
   * M2: on a scoped node, a person who leaves its projects is out of its sight, not removed: their
   * row is disabled and their name and email forgotten, but what they stored stays, and works again
   * once they are listed again. One main removes is erased.
   */
  @Test
  void aPersonWhoLeavesAScopedNodesProjectsIsWithdrawnAndComesBackWithTheirLogins()
      throws Exception {
    syncAs("nia");
    var sam = TestLogins.store(nodeDb, "sam");
    var node = new FdeStore(nodeDb);
    Acting.system(() -> fdes.revoke("sam", "alpha"));

    syncAs("nia");
    var withdrawn = node.byHandle("sam").orElseThrow();
    Acting.system(() -> fdes.grant("sam", "alpha"));
    syncAs("nia");
    var back = node.byHandle("sam").orElseThrow();
    Acting.system(() -> fdes.remove(fdes.byHandle("sam").orElseThrow().id()));
    syncAs("nia");

    assertEquals("disabled", withdrawn.status());
    assertEquals(null, withdrawn.email());
    assertEquals(null, withdrawn.displayName());
    assertEquals(sam, withdrawn.id());
    assertTrue(back.active());
    assertEquals(sam, back.id());
    assertEquals("sam", back.displayName());
    assertTrue(node.byHandle("sam").isEmpty());
    assertEquals(List.of(), node.logins().list(sam));
  }

  /** m2: a roster that does not name this box's own person removes and withdraws no one. */
  @Test
  void aRosterWithoutTheBoxsOwnPersonRemovesNoOne() throws Exception {
    syncAs("uma");
    var yan = TestLogins.store(nodeDb, "yan");
    var node = new FdeStore(nodeDb);

    SyncOperations.applyFdes(node, List.of(), "uma", notice -> {});

    assertEquals(yan, nodeId("yan"));
    assertTrue(node.byHandle("yan").orElseThrow().active());
    assertEquals(2, node.logins().list(yan).size());
  }

  /**
   * M1: at this box's first round, a person made here under a handle main gives someone else
   * (another email) is refused before anything changes, naming both emails and the ways out, each a
   * command that parses as pasted.
   */
  @Test
  void aFirstRoundRefusesAHandleMainGivesSomeoneElseAndChangesNothing() throws Exception {
    var node = new FdeStore(nodeDb);
    var kim = node.add("kim", "Kim Local", "kim@home.example", "member").id();
    TestLogins.store(nodeDb, "kim");
    fdes.add("kim", "Kim", "kim@x.dev", "member");
    var round = roundAs(tokenOf("uma"));

    var refused =
        assertThrows(
            ApiException.class,
            () -> SyncOperations.applyFdes(node, round.roster(), "uma", notice -> {}));

    assertEquals(
        "This box's first round with this main changes no one: a handle it holds is someone else's"
            + " on main.",
        refused.getMessage());
    assertEquals(
        "kim on this box (kim@home.example) is not main's kim (kim@x.dev): main gave the handle to"
            + " someone else. If they are different people, remove kim here: 'sail fde rm kim"
            + " --force'. If they are the same person, correct the email that is wrong: here 'sail"
            + " fde update kim --email kim@x.dev', or on main 'sail fde update kim --email"
            + " kim@home.example'.",
        refused.remedy().plain().strip());
    for (var command : refused.remedy().commands()) {
      assertTrue(
          new CommandLine(new Sail()).parseArgs(command.line().split(" ")).hasSubcommand(),
          command.line());
    }
    assertEquals(kim, nodeId("kim"));
    assertEquals("kim@home.example", node.byHandle("kim").orElseThrow().email());
    assertTrue(node.undescribed("kim"));
    assertEquals(2, node.logins().list(kim).size());
    assertTrue(node.byHandle("ada").isEmpty(), "nothing of main's roster was written");
  }

  /**
   * F1, after the first round: main adds someone under a handle a person made here holds (another
   * email). Main never described the local person, so nothing of theirs is deleted: they are moved
   * aside, renamed and disabled under the same id with everything they stored, said once with the
   * command that removes them, and main's person takes the handle. The same email, in any case, is
   * the same person, who keeps everything.
   */
  @Test
  void laterMainsPersonTakesAHandleMadeHereButTheSameEmailIsTheSamePerson() throws Exception {
    syncAs("uma");
    var node = new FdeStore(nodeDb);
    var kim = node.add("kim", "Kim Local", "kim@home.example", "member").id();
    var lee = node.add("lee", "Lee", "Lee@X.dev", "member").id();
    TestLogins.store(nodeDb, "kim");
    TestLogins.store(nodeDb, "lee");
    var kimsLine = keyLine(kim, "kim@laptop");
    fdes.add("kim", "Kim", "kim@x.dev", "member");
    fdes.add("lee", "Lee", "lee@x.dev", "member");
    notices.clear();
    syncAs("uma");

    var aside = node.byId(kim).orElseThrow();
    assertTrue(aside.handle().matches("kim\\.local-[0-9a-f]{4}"), aside.handle());
    assertEquals("disabled", aside.status());
    assertEquals("kim@home.example", aside.email());
    assertEquals(2, node.logins().list(kim).size());
    assertNotEquals(kim, nodeId("kim"));
    assertEquals("kim@x.dev", node.byHandle("kim").orElseThrow().email());
    assertEquals(
        new SshGateway.Rejected(SshGateway.NO_LONGER_HERE),
        opens(kimsLine, "uma"),
        "the moved-aside Kim's key does not open main's kim's door");
    assertEquals(nodeId("kim"), sessionOf(opens(keyLine(nodeId("kim"), "kim@work"), "uma")));
    assertEquals(lee, nodeId("lee"));
    assertEquals(2, node.logins().list(lee).size());
    assertEquals(
        List.of(
            "roster: kim on this box (kim@home.example) is not main's kim (kim@x.dev): main gave"
                + " the handle to someone else. It is kept as "
                + aside.handle()
                + ", disabled, with what it stored, and main's kim takes the handle. Remove it with"
                + " 'sail fde rm "
                + aside.handle()
                + " --force', or leave it: it stays, unusable."),
        notices.stream().map(Remedy::plain).toList());
    assertTrue(
        new CommandLine(new Sail())
            .parseArgs("fde", "rm", aside.handle(), "--force")
            .hasSubcommand());
  }

  /**
   * F1: the first round is per main. A box whose people another main described (ids this main does
   * not hold) meets this main for the first time, and a handle it made itself that this main gives
   * someone else is refused, not moved aside.
   */
  @Test
  void aBoxPointedAtAnotherMainIsRefusedAtItsFirstRoundWithIt() throws Exception {
    syncAs("uma");
    nodeDb.execute("UPDATE fdes SET main_id = 'fde_other_main_' || handle");
    var node = new FdeStore(nodeDb);
    node.add("kim", "Kim Local", "kim@home.example", "member");
    fdes.add("kim", "Kim", "kim@x.dev", "member");
    var round = roundAs(tokenOf("uma"));

    assertThrows(
        ApiException.class,
        () -> SyncOperations.applyFdes(node, round.roster(), "uma", notice -> {}));
    assertTrue(node.undescribed("kim"));
  }

  /**
   * m6 and m5: a blank email is no one's, so two blank emails are not the same person; and where
   * this box cannot see both emails (a scoped node sees only its own), the refusal offers only what
   * can work there, the removal, saying what it deletes.
   */
  @Test
  void aBlankEmailMatchesNoOneAndARefusalThatCannotSeeEmailsOffersTheRemoval() throws Exception {
    var node = new FdeStore(nodeDb);
    var lee = node.add("lee", "Lee", "", "member").id();
    node.logins().storeGit(lee, "github.com", "ghp_local_lee_0123456789");
    fdes.add("lee", "Lee", null, "member");
    var round = roundAs(tokenOf("uma"));

    var refused =
        assertThrows(
            ApiException.class,
            () -> SyncOperations.applyFdes(node, round.roster(), "uma", notice -> {}));

    assertTrue(node.undescribed("lee"), "a blank email adopts no one");
    assertEquals(Map.of("github.com", "ghp_local_lee_0123456789"), node.logins().gitTokens(lee));
    assertEquals(
        "lee on this box () is not main's lee (): main gave the handle to someone else. This box"
            + " cannot see both emails, so it cannot tell whether they are the same person. Remove"
            + " lee here, which deletes what it stored on this box: 'sail fde rm lee --force'.",
        refused.remedy().plain().strip());
    assertEquals(
        List.of("fde rm lee --force"),
        refused.remedy().commands().stream().map(Remedy.Command::line).toList());
  }

  @Test
  void anUnscopedNodeDisablesAnFdeMainRemovedAndKeepsItsRow() throws Exception {
    var node = new FdeStore(nodeDb);
    node.add("gone", "Gone", "gone@x.dev", "member");
    var round = roundAs(tokenOf("uma"));

    SyncOperations.applyFdes(node, round.roster(), "uma", notice -> {});

    var gone = node.byHandle("gone").orElseThrow();
    assertEquals("disabled", gone.status());
    assertEquals("gone@x.dev", gone.email());
  }

  @Test
  void aScopedFdeWithNoGrantPullsNothingButItsOwnRosterEntry() throws Exception {
    var round = roundAs(tokenOf("bob"));

    assertEquals(ProjectScope.of(List.of()), round.scope());
    assertEquals(0L, count("SELECT count(*) FROM change_log"));
    assertEquals(List.of(entry("bob", "bob", "bob@x.dev", "member")), listed(round));
  }

  @Test
  void anUnscopedFdesSessionPullsEverythingAndTheWholeRosterWithEmails() throws Exception {
    var round = roundAs(tokenOf("uma"));

    assertEquals(ProjectScope.all(), round.scope());
    assertEquals(
        List.of("a1", "b1"), nodeDb.query("SELECT id FROM specs ORDER BY id", row -> row.text(0)));
    assertEquals(SyncServerCommand.roster(mainDb, Actor.main()), round.roster());
    assertEquals(
        List.of("ada@x.dev", "bob@x.dev", "nia@x.dev", "sam@x.dev", "uma@x.dev", "yan@x.dev"),
        round.roster().stream().map(fde -> fde.get("email")).toList());
  }

  /** One unscoped round as the box of {@code own}, applied to the node as the sync applies it. */
  private final List<Remedy> notices = new ArrayList<>();

  private FdeStore syncAs(String own) throws Exception {
    var round = roundAs(tokenOf(own));
    var node = new FdeStore(nodeDb);
    assertEquals(List.of(), SyncOperations.applyFdes(node, round.roster(), own, notices::add));
    return node;
  }

  private String nodeId(String handle) {
    return new FdeStore(nodeDb).byHandle(handle).orElseThrow().id();
  }

  /**
   * Scenario 1 (N1): main removes tmp and gives the handle to a new hire within one round. The
   * node's row is a new person's: none of the old tmp's logins, git tokens or API tokens, and a new
   * id the pass disowns the old one's workspaces by.
   */
  @Test
  void aHandleMainGivesToSomeoneNewInOneRoundCarriesNothingOfTheLastHolders() throws Exception {
    fdes.add("tmp", "Tmp", "tmp@x.dev", "member");
    syncAs("uma");
    var old = TestLogins.store(nodeDb, "tmp");
    new FdeStore(nodeDb).logins().storeGit(old, "github.com", "ghp_old_tmp_0123456789");
    new TokenStore(nodeDb).create("mast-tmp", "member", old, null);
    var oldLine = keyLine(old, "tmp@laptop");
    Acting.system(() -> fdes.remove(fdes.byHandle("tmp").orElseThrow().id()));
    fdes.add("tmp", "New Hire", "newhire@x.dev", "member");

    var node = syncAs("uma");

    var hire = node.byHandle("tmp").orElseThrow();
    assertNotEquals(old, hire.id());
    assertEquals("newhire@x.dev", hire.email());
    assertEquals(List.of(), node.logins().list(hire.id()));
    assertEquals(List.of(), node.logins().list(old));
    assertEquals(Map.of(), node.logins().gitTokens(old));
    assertEquals(0L, count("SELECT count(*) FROM api_tokens WHERE name = 'mast-tmp'"));
    assertEquals(
        new SshGateway.Rejected(SshGateway.NO_LONGER_HERE),
        opens(oldLine, "uma"),
        "the old tmp's key line, written before the round, does not open the new tmp's door");
    assertEquals(hire.id(), sessionOf(opens(keyLine(hire.id(), "hire@laptop"), "uma")));
  }

  private static final Pattern PINNED = Pattern.compile("_gateway --fde-id ([^\"]+)\"");

  /**
   * The gateway's {@code authorized_keys} line for a key registered now to the node's person {@code
   * fdeId}, as the file renders it.
   */
  private String keyLine(String fdeId, String comment) {
    var keys = new FdeSshKeyStore(nodeDb);
    keys.add(fdeId, SshPublicKey.parse(TestSshKeys.ed25519(comment, comment)));
    return AuthorizedKeysRenderer.render(
            keys.list().stream().filter(key -> key.fdeId().equals(fdeId)).toList(), "sail")
        .lines()
        .skip(1)
        .findFirst()
        .orElseThrow();
  }

  /**
   * What the node's gateway decides for a session through {@code line}, on the box of {@code own}.
   */
  private SshGateway.Decision opens(String line, String own) {
    var pinned = PINNED.matcher(line);
    assertTrue(pinned.find(), line);
    var node = new FdeStore(nodeDb);
    return SshGateway.authorizeById(
        "sail spec list",
        pinned.group(1),
        node,
        new RoleRule(() -> new SyncConfig("node", "main", own, "node-box"), node),
        new AuthSessionStore(nodeDb));
  }

  private String sessionOf(SshGateway.Decision decision) {
    var token = assertInstanceOf(SshGateway.Authorized.class, decision).sessionToken();
    return new AuthSessionStore(nodeDb).validate(token).orElseThrow().fdeId();
  }

  /**
   * Scenario 2 (N2): a box with people of its own joins a main that has the same handles and the
   * same emails, in any case. Each row takes main's id and keeps everything; a row main never
   * described is only disabled, its logins kept. A row that took main's id goes, with its logins,
   * once main removes that person.
   */
  @Test
  void aBoxWithItsOwnPeopleJoiningAMainDeletesNobodysLogins() throws Exception {
    var node = new FdeStore(nodeDb);
    node.add("uma", "Uma", "uma@old.dev", "member");
    node.add("ada", "Ada", "ADA@x.dev", "member");
    node.add("zed", "Zed", "zed@old.dev", "member");
    node.add("sam", "Sam", "sam@x.dev", "member");
    var ada = TestLogins.store(nodeDb, "ada");
    var zed = TestLogins.store(nodeDb, "zed");
    var sam = TestLogins.store(nodeDb, "sam");

    syncAs("uma");
    syncAs("uma");

    assertEquals(ada, nodeId("ada"));
    assertEquals(zed, nodeId("zed"));
    assertEquals(sam, nodeId("sam"));
    assertEquals(2, node.logins().list(ada).size());
    assertEquals(2, node.logins().list(zed).size());
    assertEquals("disabled", node.byHandle("zed").orElseThrow().status());
    Acting.system(() -> fdes.remove(fdes.byHandle("sam").orElseThrow().id()));
    syncAs("uma");
    assertTrue(node.byHandle("sam").isEmpty());
    assertEquals(List.of(), node.logins().list(sam));
    assertEquals(2, node.logins().list(zed).size());
  }

  /** A roster from a main that sends no ids, as one before this change, removes no one by id. */
  @Test
  void aRosterWithoutMainsIdsRemovesNoOne() throws Exception {
    syncAs("uma");
    var yan = TestLogins.store(nodeDb, "yan");
    var round = roundAs(tokenOf("uma"));
    var withoutIds =
        round.roster().stream()
            .filter(fde -> !"yan".equals(fde.get("handle")))
            .map(
                fde ->
                    fde.entrySet().stream()
                        .filter(field -> !"id".equals(field.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
            .toList();

    var node = new FdeStore(nodeDb);
    SyncOperations.applyFdes(node, withoutIds, "uma", notice -> {});

    assertEquals(yan, nodeId("yan"));
    assertEquals(2, node.logins().list(yan).size());
  }

  /**
   * Scenario 3: a main rebuilt by adding its people again mints new ids ({@code FdeStore.add}), so
   * each is a new person to a node and stores their logins again; the box's own person takes the
   * new id and keeps everything (N3).
   */
  @Test
  void aRebuiltMainsNewIdsAreNewPeopleButTheBoxsOwnPersonKeepsEverything() throws Exception {
    syncAs("uma");
    var bob = TestLogins.store(nodeDb, "bob");
    var uma = TestLogins.store(nodeDb, "uma");
    Acting.system(
        () -> {
          for (var handle : List.of("bob", "uma")) {
            fdes.remove(fdes.byHandle(handle).orElseThrow().id());
            fdes.add(handle, handle, handle + "@x.dev", "member");
          }
        });

    var node = syncAs("uma");

    assertNotEquals(bob, nodeId("bob"));
    assertEquals(List.of(), node.logins().list(bob));
    assertEquals(uma, nodeId("uma"));
    assertEquals(2, node.logins().list(uma).size());
    assertTrue(node.byHandle("uma").orElseThrow().active());
  }

  /** Scenario 4: a person main removed while the node missed rounds goes at its next round. */
  @Test
  void aPersonMainRemovedWhileTheNodeWasAwayIsErasedAtItsNextRound() throws Exception {
    syncAs("uma");
    var yan = TestLogins.store(nodeDb, "yan");
    Acting.system(() -> fdes.remove(fdes.byHandle("yan").orElseThrow().id()));
    fdes.add("late", "Late", "late@x.dev", "member");
    var err = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    FdeStore node;
    try {
      node = syncAs("uma");
    } finally {
      System.setErr(originalErr);
    }

    assertEquals(
        "  roster: removed yan: main removed them, or gave the handle to someone new.\n",
        err.toString(StandardCharsets.UTF_8),
        "the line names whom, never an email");

    assertTrue(node.byHandle("yan").isEmpty());
    assertEquals(List.of(), node.logins().list(yan));
    assertTrue(node.byHandle("late").orElseThrow().active());
  }

  /** Scenario 5 (N3): a round that leaves out the box's own person changes nothing of theirs. */
  @Test
  void aRoundWithoutTheBoxsOwnPersonChangesNothingOfTheirs() throws Exception {
    syncAs("uma");
    var uma = TestLogins.store(nodeDb, "uma");
    var round = roundAs(tokenOf("uma"));
    var withoutItsOwn =
        round.roster().stream().filter(fde -> !"uma".equals(fde.get("handle"))).toList();

    var node = new FdeStore(nodeDb);
    SyncOperations.applyFdes(node, withoutItsOwn, "uma", notice -> {});

    assertEquals(uma, nodeId("uma"));
    assertTrue(node.byHandle("uma").orElseThrow().active());
    assertEquals(2, node.logins().list(uma).size());
  }

  private record Round(ProjectScope scope, List<Map<String, Object>> roster) {}

  /** One round of every type over a session main authenticates with {@code token}. */
  private Round roundAs(String token) throws Exception {
    var toServer = new PipedOutputStream();
    var serverIn = new BufferedInputStream(new PipedInputStream(toServer));
    var toClient = new PipedOutputStream();
    var clientIn = new BufferedInputStream(new PipedInputStream(toClient));
    var server =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (toClient) {
                    SyncServerCommand.serve(main, "main", token, serverIn, toClient);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
    try (var session =
        SyncSession.open(
            clientIn,
            toServer,
            SyncWire.Hello.of("1.0.0", "node-box"),
            notice -> {},
            ignored -> {},
            nodeDb)) {
      var replicas = SyncedEntities.replicas(nodeDb, "node-box", "nia");
      for (var entity : SyncedEntities.all()) {
        Actor.call(
            Actor.main(), () -> session.reconcile(entity.type(), replicas.get(entity.type())));
      }
      return new Round(session.scope(), session.fetchFdes());
    } finally {
      server.join();
    }
  }

  private String tokenOf(String handle) {
    return new AuthSessionStore(mainDb)
        .create(fdes.byHandle(handle).orElseThrow().id(), Duration.ofMinutes(10))
        .token();
  }

  private static Map<String, Object> entry(String handle, String name, String email, String role) {
    return Map.of(
        "handle", handle, "display_name", name, "email", email, "role", role, "status", "active");
  }

  private static List<Map<String, Object>> listed(Round round) {
    return round.roster().stream()
        .filter(fde -> fde.containsKey("handle"))
        .map(ScopedSyncServerTest::withoutCreatedAt)
        .toList();
  }

  private static Map<String, Object> withoutCreatedAt(Map<String, Object> fde) {
    return fde.entrySet().stream()
        .filter(field -> !"created_at".equals(field.getKey()) && !"id".equals(field.getKey()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private long count(String sql) {
    return nodeDb.queryOne(sql, row -> row.integer(0)).orElseThrow();
  }

  private static SpecStore.SpecRow spec(String id, String project, String owner) {
    return new SpecStore.SpecRow(
        id,
        project,
        "the " + project + " spec",
        SpecStatus.PENDING,
        owner,
        null,
        null,
        null,
        null,
        0,
        owner,
        null,
        null,
        owner,
        List.of(),
        List.of());
  }
}
