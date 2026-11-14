/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.api.TestControlPlane;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code sail agent login} over a running API, as a person runs it: the value read from stdin,
 * stored as theirs, and never printed back; listed, switched and removed by name.
 */
class AgentLoginCommandTest {

  private static final String KEY = "sk-ant-api03-cli-value-777";

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
                new ShellExecutor(false),
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
    var ran = SailCli.run(new AgentCommand(), args);
    assertFalse(ran.out().contains(KEY), ran.out());
    assertFalse(ran.err().contains(KEY), ran.err());
    return ran;
  }

  @SuppressWarnings("unchecked")
  @Test
  void aPipedKeyIsStoredAsThePersonsAndOnlyItsFingerprintIsEverShown() {
    var stored = sail(KEY + "\n", "login", "claude-code", "--kind", "api-key");

    assertEquals(0, stored.exit(), stored.err());
    var uday = new FdeStore(db).byHandle("uday").orElseThrow().id();
    var login =
        new FdeStore(db).logins().defaultFor(uday, Harnesses.of("claude-code")).orElseThrow();
    assertEquals(KEY, login.value(), "the trailing newline a pipe adds is not the value's");
    assertEquals(
        "  ✓ Stored claude-code login 'default' (api-key, the default), fingerprint "
            + login.row().fingerprint()
            + ".\n",
        stored.out());

    assertEquals(0, sail("tok-2", "login", "claude-code", "--name", "personal").exit());
    var listed = sail("", "login", "list", "--json");
    var logins = (List<Map<String, Object>>) YamlUtil.parseMap(listed.out()).get("logins");
    assertEquals(
        List.of("default api-key true", "personal token false"),
        logins.stream()
            .map(row -> row.get("name") + " " + row.get("kind") + " " + row.get("default"))
            .toList());

    var used = sail("", "login", "use", "claude-code", "personal");
    var removed = sail("", "login", "remove", "claude-code", "default");

    assertEquals(0, used.exit(), used.err());
    assertTrue(used.out().contains("Using claude-code login 'personal' (token, the default)"));
    assertEquals(0, removed.exit(), removed.err());
    assertTrue(sail("", "login", "list").out().contains("personal"));
  }

  @Test
  void aValueBreakingTheRulesIsRefusedNamingTheRuleAndNothingIsStored() {
    var refused = sail("has a space", "login", "codex");
    var unknown = sail(KEY, "login", "gemini");

    assertEquals(1, refused.exit());
    assertTrue(
        refused
            .err()
            .contains(
                "A login's value is printable ASCII with no space, quote, backslash, $ or"
                    + " backtick."),
        refused.err());
    assertFalse(refused.err().contains("has a space"));
    assertEquals(1, unknown.exit());
    assertEquals(
        List.of(),
        new FdeStore(db).logins().list(new FdeStore(db).byHandle("uday").orElseThrow().id()));
  }

  @Test
  void stdinIsReadWholeItsOneTrailingNewlineDroppedAndMoreThanALoginHoldsRefused()
      throws Exception {
    assertEquals("a", AgentLoginCommand.trimmed(stream("a\n")));
    assertEquals("a", AgentLoginCommand.trimmed(stream("a\r\n")));
    assertEquals("a\nb", AgentLoginCommand.trimmed(stream("a\nb\n")));
    assertEquals(
        "x".repeat(16 * 1024 + 2), AgentLoginCommand.trimmed(stream("x".repeat(16 * 1024 + 2))));
    assertThrows(
        CliCommand.Refused.class,
        () -> AgentLoginCommand.trimmed(stream("x".repeat(16 * 1024 + 3))));
  }

  @Test
  void withoutAHarnessItPrintsItsUsage() {
    var ran = sail("", "login");

    assertEquals(0, ran.exit());
    assertTrue(ran.out().contains("Usage:"), ran.out());
  }

  private static ByteArrayInputStream stream(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }
}
