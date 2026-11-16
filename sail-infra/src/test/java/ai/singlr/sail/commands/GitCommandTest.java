/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.api.TestControlPlane;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code sail git login} over a running API, as a person runs it: the token read from stdin, stored
 * as theirs for its host, and never printed back; listed and removed by host.
 */
class GitCommandTest {

  private static final String KEY = "ghp_cliValue777777";

  @TempDir Path tempDir;
  private Sqlite db;
  private SailApiServer server;
  private String token;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    var fde = new FdeStore(db).add("uday", "Uday", "uday@example.com", "member");
    token = new TokenStore(db).create("uday", "member", fde.id(), null).token();
    var operations =
        TestControlPlane.standalone(
            OperationsFactory.create(
                db,
                new ScriptedShellExecutor(new ShellExec.Result(0, "[]", "")),
                new EventBus(),
                SyncScheduler.disabled(),
                SessionYield.NONE),
            db,
            tempDir);
    server =
        new SailApiServer(
            "127.0.0.1", 0, operations, TestAuth.tokens(db), new EventBus(), null, null, null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    ConsoleStub.restore();
    System.clearProperty("SAIL_SERVER");
    System.clearProperty("SAIL_TOKEN");
    server.close();
    db.close();
  }

  private SailCli.Ran sail(String stdin, String... args) {
    ConsoleStub.answering(stdin, false);
    System.setProperty("SAIL_SERVER", "http://127.0.0.1:" + server.port());
    System.setProperty("SAIL_TOKEN", token);
    var ran = SailCli.run(new GitCommand(), args);
    assertFalse(ran.out().contains(KEY), ran.out());
    assertFalse(ran.err().contains(KEY), ran.err());
    return ran;
  }

  @SuppressWarnings("unchecked")
  @Test
  void aPipedTokenIsStoredAsThePersonsForItsHostAndOnlyItsFingerprintIsEverShown() {
    var stored = sail(KEY + "\n", "login", "GitHub.com");

    assertEquals(0, stored.exit(), stored.err());
    assertEquals("  Reading the github.com token from stdin.\n", stored.err());
    var uday = new FdeStore(db).byHandle("uday").orElseThrow().id();
    assertEquals(Map.of("github.com", KEY), new FdeStore(db).logins().gitTokens(uday));
    var print = new FdeStore(db).logins().list(uday).getFirst().fingerprint();
    assertEquals(
        "  ✓ Stored git login 'github.com' (token), fingerprint " + print + ".\n", stored.out());

    var listed = sail("", "login", "list", "--json");
    var tokens = (List<Map<String, Object>>) YamlUtil.parseMap(listed.out()).get("logins");
    assertEquals(List.of("github.com"), tokens.stream().map(t -> t.get("name")).toList());
    assertTrue(sail("", "login", "list").out().contains("github.com"));

    var removed = sail("", "login", "remove", "github.com");
    var again = sail("", "login", "remove", "github.com");

    assertEquals(
        "  ✓ Removed git login 'github.com' (token), fingerprint " + print + ".\n", removed.out());
    assertEquals(
        "  No git token for github.com is stored for uday; nothing was removed.\n", again.out());
    assertTrue(
        sail("", "login", "list").out().contains("No git tokens stored for uday."),
        "the list says none is kept");
  }

  @Test
  void aBadHostIsRefusedBeforeAnyValueIsReadAndABadValueNamingTheRule() throws Exception {
    var host = sail(KEY, "login", "git_hub.com");
    var unread = System.in.available();
    var value = sail("has a space", "login", "github.com");

    assertEquals(1, host.exit());
    assertTrue(host.err().contains("Invalid host: 'git_hub.com'"), host.err());
    assertEquals(KEY.length(), unread, "a refused host reads no value");
    assertEquals(1, value.exit());
    assertTrue(value.err().contains("printable ASCII with no space"), value.err());
    assertFalse(value.err().contains("has a space"));
  }

  /**
   * Medium 1 at the gateway, which runs this CLI as the session's person: a store names no other
   * person, so {@code --fde} on a login is refused before any value is read, never ignored.
   */
  @Test
  void aLoginNamingAnotherPersonIsRefusedBeforeAnyValueIsRead() {
    var git = sail(KEY + "\n", "login", "github.com", "--fde", "sam");
    var harness = SailCli.run(new AgentLoginCommand(), "claude-code", "--fde", "sam");

    assertEquals(2, git.exit(), git.out());
    assertTrue(git.err().contains("Unknown options: '--fde', 'sam'"), git.err());
    assertEquals(2, harness.exit(), harness.out());
    assertTrue(harness.err().contains("Unknown options: '--fde', 'sam'"), harness.err());
    var fdes = new FdeStore(db);
    assertEquals(Map.of(), fdes.logins().gitTokens(fdes.byHandle("uday").orElseThrow().id()));
  }

  @Test
  void withoutAHostItPrintsItsUsage() {
    var ran = sail("", "login");

    assertEquals(0, ran.exit());
    assertTrue(ran.out().contains("Usage:"), ran.out());
  }

  /**
   * Each command a refused workspace owner is told to run is one this sail parses as printed,
   * placeholder filled: the email it sets and the git login it stores.
   */
  @Test
  void theCommandsAnOwnersRefusalsNameParseAsPrinted() {
    var config =
        new ProjectDefinition(
            "acme",
            null,
            null,
            null,
            null,
            null,
            List.of(new ProjectDefinition.Repo("https://github.com/acme/api.git", "api", null)),
            null,
            null,
            null,
            null);
    var noEmail = new WorkspaceOwner("ana", "Ana", "", Map.of(), List.of());
    var noToken = new WorkspaceOwner("ana", "Ana", "ana@sail.invalid", Map.of(), List.of());
    var commands = new ArrayList<Remedy.Command>();
    for (var owner : List.of(noEmail, noToken)) {
      var refused =
          assertThrows(IllegalStateException.class, () -> owner.require(config, url -> true));
      commands.addAll(Remedied.remedyOf(refused).commands());
    }

    assertEquals(
        List.of("fde update ana --email <email>", "git login github.com"),
        commands.stream().map(Remedy.Command::line).toList());
    for (var command : commands) {
      var args = new ArrayList<String>();
      args.addAll(List.of(command.verb().split(" ")));
      command.args().forEach(arg -> args.add(arg.equals("<email>") ? "ana@sail.invalid" : arg));
      var parsed = new CommandLine(new Sail()).parseArgs(args.toArray(String[]::new));
      assertTrue(parsed.hasSubcommand(), String.join(" ", args));
    }
  }

  /**
   * Review minor 7: the refusal for a repo on a host with a port names a {@code sail git login}
   * that works as printed, pasted with the token on stdin.
   */
  @Test
  void theRefusalForAHostWithAPortNamesALoginThatWorksAsPasted() {
    var config =
        new ProjectDefinition(
            "acme",
            null,
            null,
            null,
            null,
            null,
            List.of(
                new ProjectDefinition.Repo(
                    "https://git.example.com:8443/acme/api.git", "api", null)),
            null,
            null,
            null,
            null);
    var owner = new WorkspaceOwner("uday", "Uday", "uday@example.com", Map.of(), List.of());
    var refused =
        assertThrows(IllegalStateException.class, () -> owner.require(config, url -> true));
    var printed = Remedied.remedyOf(refused).commands().getFirst();

    var args = new ArrayList<>(List.of(printed.verb().split(" ")));
    args.addAll(printed.args());
    var stored = sail(KEY, args.subList(1, args.size()).toArray(String[]::new));

    assertEquals("git login git.example.com:8443", printed.line());
    assertEquals(0, stored.exit(), stored.err());
    var uday = new FdeStore(db).byHandle("uday").orElseThrow().id();
    assertEquals(Map.of("git.example.com:8443", KEY), new FdeStore(db).logins().gitTokens(uday));
  }

  /** Review minor 6: a {@code --json} refusal is the API's error, its code included, on stdout. */
  @SuppressWarnings("unchecked")
  @Test
  void aJsonRefusalCarriesTheApisErrorCode() {
    var refused = sail("", "login", "remove", "github.com", "--fde", "nobody", "--json");

    assertEquals(1, refused.exit());
    var error = (Map<String, Object>) YamlUtil.parseMap(refused.out()).get("error");
    assertEquals("forbidden_admin_only", error.get("code"), refused.out());
  }
}
