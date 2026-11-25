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
import ai.singlr.sail.api.RunReports;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.api.TestControlPlane;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.LoginOutcome;
import ai.singlr.sail.harness.RunReport;
import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

    assertEquals(0, sail("fake-key-2", "login", "claude-code", "--name", "personal").exit());
    var listed = sail("", "login", "list", "--json");
    var logins = (List<Map<String, Object>>) YamlUtil.parseMap(listed.out()).get("logins");
    assertEquals(
        List.of("default api-key true", "personal api-key false"),
        logins.stream()
            .map(row -> row.get("name") + " " + row.get("kind") + " " + row.get("default"))
            .toList());

    var used = sail("", "login", "use", "claude-code", "personal");
    var removed = sail("", "login", "remove", "claude-code", "default");

    assertEquals(0, used.exit(), used.err());
    assertTrue(used.out().contains("Using claude-code login 'personal' (api-key, the default)"));
    assertEquals(0, removed.exit(), removed.err());
    assertTrue(sail("", "login", "list").out().contains("personal"));
  }

  /**
   * Every command a run's login outcome names works as pasted: the refusal's for a named key and
   * for the default key replace exactly the login they name, which stays as it was otherwise (the
   * default stays the default).
   */
  @Test
  void theCommandARefusalNamesReplacesTheLoginItNamesWhenPastedAsPrinted() {
    var uday = new FdeStore(db).byHandle("uday").orElseThrow().id();
    var claude = Harnesses.of("claude-code");
    new FdeStore(db).logins().store(uday, claude, null, "api-key", "sk-ant-api03-default-old");
    new FdeStore(db).logins().store(uday, claude, "work", "api-key", "sk-ant-api03-old");
    var told = new ArrayList<String>();
    var refused = new RunReport(RunUsage.NONE, new LoginOutcome.Refused());
    var reports = new RunReports(run -> refused, (spec, body) -> told.add(body), event -> {});
    var runs = new RunStore(db);
    for (var login :
        List.of(
            new RunStore.RunLogin("work", "api-key"),
            new RunStore.RunLogin("default", "api-key"))) {
      var id = DateTimeUtils.newId().toString();
      Acting.system(
          () -> {
            runs.create(
                id,
                "acme",
                "auth",
                "uday",
                "build",
                "claude-code",
                "b",
                "t",
                null,
                null,
                "/l",
                "u");
            runs.recordLaunch(id, new RunStore.Launched(null, null, null, login, null));
          });
      reports.finish(runs, runs.findById(id).orElseThrow(), report -> true);
    }

    for (var sentence : told) {
      var printed =
          sentence.substring(sentence.indexOf(", then ") + 7, sentence.indexOf(". Then build"));
      var words = List.of(printed.split(" "));
      assertEquals(List.of("sail", "agent", "login"), words.subList(0, 3), printed);
      var ran = sail(KEY, words.subList(2, words.size()).toArray(String[]::new));
      assertEquals(0, ran.exit(), printed + ": " + ran.err());
      var next = sentence.substring(sentence.indexOf("again: ") + 7, sentence.length() - 1);
      var dispatch = new DispatchCommand();
      var parsed = List.of(next.split(" "));
      assertEquals(List.of("sail", "spec", "dispatch"), parsed.subList(0, 3), next);
      new CommandLine(dispatch).parseArgs(parsed.subList(3, parsed.size()).toArray(String[]::new));
      assertEquals(
          "-p acme --spec auth --restart",
          String.join(" ", parsed.subList(3, parsed.size())),
          "the build's next step, as the CLI prints it for a spec not pending");
    }

    var logins = new FdeStore(db).logins();
    assertEquals(KEY, logins.defaultFor(uday, claude).orElseThrow().value());
    assertEquals("default", logins.defaultFor(uday, claude).orElseThrow().row().name());
    assertEquals(
        List.of("default api-key true", "work api-key false"),
        logins.list(uday).stream()
            .filter(row -> row.harness().equals("claude-code"))
            .map(row -> row.name() + " " + row.kind() + " " + row.isDefault())
            .toList());
  }

  @Test
  void aPipedValueIsSaidToBeReadFromStdinSoAPersonWaitingKnowsWhy() {
    var stored = sail(KEY, "login", "codex", "--kind", "api-key");

    assertEquals(0, stored.exit(), stored.err());
    assertEquals("  Reading the Codex CLI api-key from stdin.\n", stored.err());
  }

  @Test
  void aDryRunSaysWhatWouldBeStoredReadsNoValueAndStoresNothing() throws Exception {
    var first = sail(KEY, "login", "claude-code", "--dry-run");
    var unread = System.in.available();
    sail(KEY, "login", "claude-code");
    var second =
        sail(KEY, "login", "claude-code", "--name", "work", "--kind", "api-key", "--dry-run");
    var again = sail(KEY, "login", "claude-code", "--dry-run", "--json");

    assertEquals(0, first.exit(), first.err());
    assertEquals(
        "  Would store claude-code login 'default' (api-key, the default); nothing was stored.\n",
        first.out());
    assertEquals(KEY.length(), unread, "a dry run reads no value");
    assertEquals("", first.err());
    assertEquals(
        "  Would store claude-code login 'work' (api-key, not the default); nothing was stored.\n",
        second.out());
    assertEquals(
        Map.of(
            "dry_run",
            true,
            "harness",
            "claude-code",
            "name",
            "default",
            "kind",
            "api-key",
            "default",
            true,
            "replaces",
            true),
        YamlUtil.parseMap(again.out()),
        "it would replace the default stored a moment ago");
    var uday = new FdeStore(db).byHandle("uday").orElseThrow().id();
    assertEquals(
        List.of("default"),
        new FdeStore(db).logins().list(uday).stream().map(row -> row.name()).toList());
  }

  @Test
  void removingALoginNotStoredSaysThereWasNothingToRemove() {
    var removed = sail("", "login", "remove", "codex", "work");

    assertEquals(0, removed.exit(), removed.err());
    assertEquals(
        "  No Codex CLI login named 'work' is stored for uday; nothing was removed.\n",
        removed.out());
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
                "A key is printable ASCII with no space, quote, backslash, $ or" + " backtick."),
        refused.err());
    assertFalse(refused.err().contains("has a space"));
    assertEquals(1, unknown.exit());
    assertEquals(
        List.of(),
        new FdeStore(db).logins().list(new FdeStore(db).byHandle("uday").orElseThrow().id()));
  }

  /**
   * L1 at the CLI's door: each deleted kind is refused naming the one kind that exists, and nothing
   * is stored, read from stdin or not.
   */
  @Test
  void eachDeletedKindIsRefusedNamingTheKindThatExistsAndNothingIsStored() {
    for (var deleted : List.of("token", "glm")) {
      var refused = sail("fake-deleted-value", "login", "claude-code", "--kind", deleted);

      assertEquals(1, refused.exit());
      assertTrue(
          refused
              .err()
              .contains("Claude Code takes no login of kind '" + deleted + "'; it takes api-key."),
          refused.err());
    }
    assertEquals(
        List.of(),
        new FdeStore(db).logins().list(new FdeStore(db).byHandle("uday").orElseThrow().id()));
  }

  /**
   * Under {@code --json} a refusal of {@code login}, {@code use} and {@code remove} is JSON in the
   * API's error envelope, its code in it, and nothing but that is printed.
   */
  @Test
  void aRefusalOfEachChangeUnderJsonIsTheApisErrorEnvelopeWithItsCode() {
    var kind = sail("fake-glm-value", "login", "claude-code", "--kind", "glm", "--json");
    var use = sail("", "login", "use", "claude-code", "missing", "--json");
    var remove = sail("", "login", "remove", "claude-code", "default", "--fde", "ana", "--json");

    assertEquals("invalid_request", error(kind).get("code"), kind.out());
    assertEquals(
        "Claude Code takes no login of kind 'glm'; it takes api-key.", error(kind).get("message"));
    assertEquals("not_found", error(use).get("code"), use.out());
    assertEquals("forbidden_admin_only", error(remove).get("code"), remove.out());
    for (var ran : List.of(kind, use, remove)) {
      assertEquals(1, ran.exit(), ran.out());
      assertEquals("", ran.err(), "nothing but the JSON");
    }
  }

  private static Map<?, ?> error(SailCli.Ran ran) {
    return (Map<?, ?>) YamlUtil.parseMap(ran.out()).get("error");
  }

  @Test
  void stdinIsReadWholeItsOneTrailingNewlineDroppedAndMoreThanALoginHoldsRefused()
      throws Exception {
    assertEquals("a", AgentLoginCommand.trimmed(stream("a\n"), "key"));
    assertEquals("a", AgentLoginCommand.trimmed(stream("a\r\n"), "key"));
    assertEquals("a\nb", AgentLoginCommand.trimmed(stream("a\nb\n"), "key"));
    assertEquals(
        "x".repeat(16 * 1024 + 2),
        AgentLoginCommand.trimmed(stream("x".repeat(16 * 1024 + 2)), "key"));
    assertEquals(
        List.of("A key is at most 16 KiB.", "A git token is at most 16 KiB."),
        List.of("key", "git token").stream()
            .map(
                noun ->
                    assertThrows(
                            CliCommand.Refused.class,
                            () ->
                                AgentLoginCommand.trimmed(stream("x".repeat(16 * 1024 + 3)), noun))
                        .getMessage())
            .toList(),
        "said of what is read, a key or a git token");
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
