/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.PrivateDirectory;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoginStoreTest {

  private static final String UDAY = "fde_uday0000000000000000";
  private static final String ANA = "fde_ana00000000000000000";
  private static final String OTHER_KEY = "fake-other-key-AAAA_bbbb-1234";
  private static final String KEY = "sk-ant-api03-ZZZZ";
  private static final String VALUE_RULE =
      "A key is printable ASCII with no space, quote, backslash, $ or backtick.";

  private static final Harness CLAUDE = Harnesses.of("claude-code");
  private static final Harness CODEX = Harnesses.of("codex");

  @TempDir Path dir;

  private LoginStore store() {
    return new LoginStore(dir.resolve("logins"));
  }

  @Test
  void theFirstLoginStoredIsTheDefaultAndItsValueIsNeverShown() {
    var clock = DateTimeUtils.clock;
    var at = Instant.parse("2026-11-11T09:00:00Z");
    DateTimeUtils.clock = Clock.fixed(at, ZoneOffset.UTC);
    LoginStore.Row stored;
    try {
      stored = store().store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);
    } finally {
      DateTimeUtils.clock = clock;
    }

    assertEquals(
        new LoginStore.Row(
            "claude-code", "default", "api-key", true, at, LoginStore.fingerprint(OTHER_KEY)),
        stored,
        "stored when the box's clock says, as every other time it answers");
    assertEquals(List.of(stored), store().list(UDAY));
    assertEquals(8, stored.fingerprint().length());
    assertTrue(stored.fingerprint().matches("[0-9a-f]{8}"));
    assertFalse(stored.toString().contains(OTHER_KEY));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void underASetgidParentEveryDirectoryIsStillThisAccountsAloneAndOneFoundOtherwiseIsRepaired()
      throws Exception {
    Files.setAttribute(dir, "unix:mode", 02770);
    var logins = dir.resolve("logins");
    var person = logins.resolve(UDAY);
    var codex = Files.createDirectories(person.resolve("codex"));
    Files.setAttribute(codex, "unix:mode", 02755);

    store().store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);
    store().store(UDAY, CODEX, null, null, KEY);

    var self = PrivateDirectory.ProcessOwner.current().orElseThrow();
    for (var each : List.of(logins, person, person.resolve("claude-code"), codex)) {
      assertEquals(0700, (int) Files.getAttribute(each, "unix:mode") & 07777, each.toString());
      assertEquals(self.uid(), Files.getAttribute(each, "unix:uid"), each.toString());
      assertEquals(self.gid(), Files.getAttribute(each, "unix:gid"), each.toString());
    }
  }

  @Test
  void aLinkWhereADirectoryBelongsIsRefused() throws Exception {
    Files.createSymbolicLink(
        dir.resolve("logins"), Files.createDirectory(dir.resolve("elsewhere")));

    assertThrows(
        UncheckedIOException.class, () -> store().store(UDAY, CLAUDE, null, "api-key", OTHER_KEY));
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void everyDirectoryIsOwnerOnlyAndEveryFileOwnerReadWriteWithNoTemporaryFileLeft()
      throws Exception {
    store().store(UDAY, CLAUDE, "work", "api-key", OTHER_KEY);

    var person = dir.resolve("logins").resolve(UDAY);
    for (var each : List.of(dir.resolve("logins"), person, person.resolve("claude-code"))) {
      assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(each)));
    }
    for (var file : List.of("work", ".default")) {
      assertEquals(
          "rw-------",
          PosixFilePermissions.toString(
              Files.getPosixFilePermissions(person.resolve("claude-code").resolve(file))));
    }
    try (Stream<Path> files = Files.list(person.resolve("claude-code"))) {
      assertEquals(
          Set.of("work", ".default"),
          Set.copyOf(files.map(file -> file.getFileName().toString()).toList()));
    }
    assertEquals(
        List.of("api-key", OTHER_KEY),
        Files.readAllLines(person.resolve("claude-code").resolve("work")));
    assertEquals(List.of("work"), Files.readAllLines(person.resolve("claude-code/.default")));
  }

  @Test
  void aSecondLoginLeavesTheDefaultAndUseSwitchesIt() {
    var store = store();
    store.store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);
    store.store(UDAY, CLAUDE, "personal", "api-key", KEY);

    assertEquals(
        List.of("claude-code/default api-key default", "claude-code/personal api-key"),
        described(store.list(UDAY)));
    assertEquals(OTHER_KEY, store.defaultFor(UDAY, CLAUDE).orElseThrow().value());

    var used = store.use(UDAY, CLAUDE, "personal");

    assertTrue(used.isDefault());
    assertEquals(
        List.of("claude-code/default api-key", "claude-code/personal api-key default"),
        described(store.list(UDAY)));
    var login = store.defaultFor(UDAY, CLAUDE).orElseThrow();
    assertEquals(KEY, login.value());
    assertEquals("ANTHROPIC_API_KEY", login.kind().variable());
    assertFalse(login.toString().contains(KEY));
  }

  @Test
  void storingANameAgainReplacesItsValueAndKind() {
    var store = store();
    store.store(UDAY, CLAUDE, "work", "api-key", OTHER_KEY);

    var replaced = store.store(UDAY, CLAUDE, "work", "api-key", KEY);

    assertEquals(LoginStore.fingerprint(KEY), replaced.fingerprint());
    assertEquals("api-key", replaced.kind());
    assertEquals(List.of("claude-code/work api-key default"), described(store.list(UDAY)));
  }

  @Test
  void eachHarnessTakesItsOwnKindsAndItsFirstWhenNoneIsGiven() {
    var store = store();
    store.store(UDAY, CODEX, null, null, "sk-proj-1");

    assertEquals("CODEX_API_KEY", store.defaultFor(UDAY, CODEX).orElseThrow().kind().variable());
    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> store.store(UDAY, CODEX, "x", "session", "v"));
    assertEquals(
        "Codex CLI takes no login of kind 'session'; it takes api-key.", refused.getMessage());
    assertEquals(
        "Codex CLI takes no login of kind 'token'; it takes api-key.",
        assertThrows(
                IllegalArgumentException.class, () -> store.store(UDAY, CODEX, "x", "token", "v"))
            .getMessage());
    assertEquals(Optional.empty(), store.defaultFor(UDAY, CLAUDE));
  }

  @Test
  void removingTheDefaultLeavesNoDefaultAndTheNextStoredBecomesIt() {
    var store = store();
    store.store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);
    store.store(UDAY, CLAUDE, "personal", null, KEY);

    var removed = store.remove(UDAY, CLAUDE, "default").orElseThrow();

    assertTrue(removed.isDefault());
    assertEquals(List.of("claude-code/personal api-key"), described(store.list(UDAY)));
    assertEquals(Optional.empty(), store.defaultFor(UDAY, CLAUDE));

    store.store(UDAY, CLAUDE, "work", "api-key", OTHER_KEY);

    assertEquals("work", store.defaultFor(UDAY, CLAUDE).orElseThrow().row().name());
  }

  @Test
  void usingALoginNotStoredIsRefusedNamingItAndRemovingOneIsNothingToDo() {
    var store = store();
    store.store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);

    assertEquals(Optional.empty(), store.remove(UDAY, CLAUDE, "work"));
    assertEquals(Optional.empty(), new LoginStore(null).remove(UDAY, CLAUDE, "work"));
    assertTrue(store.remove(UDAY, CLAUDE, "default").isPresent());
    assertEquals(Optional.empty(), store.remove(UDAY, CLAUDE, "default"), "removing twice is once");
    for (var asked :
        List.<Runnable>of(
            () -> store.use(UDAY, CLAUDE, "work"), () -> store.use(ANA, CLAUDE, "default"))) {
      var refused = assertThrows(NoSuchElementException.class, asked::run);
      assertTrue(
          refused.getMessage().startsWith("No Claude Code login named '"), refused.getMessage());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "has space",
        "quote\"d",
        "single'q",
        "back\\slash",
        "dollar$x",
        "back`tick",
        "tab\tx",
        "unicode-é"
      })
  void aValueBreakingTheCharacterRuleIsRefusedNamingTheRuleNotTheValue(String value) {
    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> store().store(UDAY, CLAUDE, null, null, value));

    assertEquals(VALUE_RULE, refused.getMessage());
    assertEquals(List.of(), store().list(UDAY));
  }

  @Test
  void aBlankAMultiLineAndAnOversizedValueAreEachRefusedByTheirOwnRule() {
    var store = store();

    assertEquals(
        "A key is empty: paste the key.",
        assertThrows(
                IllegalArgumentException.class, () -> store.store(UDAY, CLAUDE, null, null, " "))
            .getMessage());
    assertEquals(
        "A key is one line: paste only the key.",
        assertThrows(
                IllegalArgumentException.class, () -> store.store(UDAY, CLAUDE, null, null, "a\nb"))
            .getMessage());
    assertEquals(
        "A key is at most 16 KiB.",
        assertThrows(
                IllegalArgumentException.class,
                () -> store.store(UDAY, CLAUDE, null, null, "a".repeat(16 * 1024 + 1)))
            .getMessage());
    store.store(UDAY, CLAUDE, null, null, "a".repeat(16 * 1024));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Work", "-work", "a/b", "../x", "x.y", "uday@example.com", ".default"})
  void aNameOutsideTheRuleIsRefused(String name) {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> store().store(UDAY, CLAUDE, name, "api-key", OTHER_KEY));

    assertEquals(
        "A key's name is 1 to 32 lowercase letters, digits and hyphens, starting with a letter"
            + " or digit, such as work or personal.",
        refused.getMessage());
  }

  @Test
  void aNameOfThirtyTwoIsTakenAndThirtyThreeIsNot() {
    store().store(UDAY, CLAUDE, "a".repeat(32), "api-key", OTHER_KEY);

    assertThrows(
        IllegalArgumentException.class,
        () -> store().store(UDAY, CLAUDE, "a".repeat(33), "api-key", OTHER_KEY));
  }

  @ParameterizedTest
  @ValueSource(strings = {"..", "a/b", "", "-x"})
  void anFdeIdThatIsNotOneNeverNamesAPath(String fdeId) {
    assertThrows(
        IllegalArgumentException.class,
        () -> store().store(fdeId, CLAUDE, null, "api-key", OTHER_KEY));
  }

  @Test
  void eachPersonKeepsOnlyTheirOwn() {
    var store = store();
    store.store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);
    store.store(ANA, CLAUDE, null, null, KEY);

    assertEquals(OTHER_KEY, store.defaultFor(UDAY, CLAUDE).orElseThrow().value());
    assertEquals(KEY, store.defaultFor(ANA, CLAUDE).orElseThrow().value());
  }

  @Test
  void forgetDeletesAPersonsLoginsAndTheSweepDeletesThoseOfPeopleNoLongerHeld() {
    var store = store();
    store.store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);
    store.store(UDAY, CODEX, null, null, KEY);
    store.store(ANA, CLAUDE, null, null, KEY);

    store.forget(UDAY);

    assertEquals(List.of(), store.list(UDAY));
    assertFalse(Files.exists(dir.resolve("logins").resolve(UDAY)));
    store.store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);

    assertEquals(1, store.sweep(Set.of(ANA)::contains));
    assertEquals(0, store.sweep(Set.of(ANA)::contains));
    assertEquals(List.of(), store.list(UDAY));
    assertEquals(1, store.list(ANA).size());
  }

  /**
   * The sweep asks of each person just before deleting, under the lock a store takes: a person the
   * roster holds by then keeps their logins, whatever was read of the roster before.
   */
  @Test
  void theSweepAsksOfEachPersonAsItReachesThemSoOneAddedSinceIsKept() {
    var store = store();
    store.store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);
    store.store(ANA, CLAUDE, null, null, KEY);
    var asked = new ArrayList<String>();

    var swept =
        store.sweep(
            id -> {
              asked.add(id);
              return id.equals(ANA);
            });

    assertEquals(1, swept);
    assertEquals(List.of(ANA, UDAY), asked);
    assertEquals(1, store.list(ANA).size());
  }

  @Test
  void aStoreBesideAnInMemoryDatabaseKeepsNothingAndRefusesToStore() {
    try (var db = Sqlite.openMemory()) {
      var store = LoginStore.beside(db);

      assertEquals(List.of(), store.list(UDAY));
      assertEquals(Optional.empty(), store.defaultFor(UDAY, CLAUDE));
      assertEquals(0, store.sweep(id -> false));
      store.forget(UDAY);
      var refused =
          assertThrows(
              IllegalStateException.class,
              () -> store.store(UDAY, CLAUDE, null, "api-key", OTHER_KEY));
      assertEquals(
          "This box keeps its database in memory, so it keeps no logins.", refused.getMessage());
    }
  }

  @Test
  void aStoreBesideADatabaseFileKeepsItsLoginsBesideIt() {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      LoginStore.beside(db).store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);
    }

    assertEquals(1, store().list(UDAY).size());
  }

  @Test
  void aFileThatIsNotALoginIsNotListedOrUsed() throws Exception {
    var store = store();
    store.store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);
    var harness = dir.resolve("logins").resolve(UDAY).resolve("claude-code");
    Files.writeString(harness.resolve("default"), "api-key\n");
    Files.createDirectories(dir.resolve("logins").resolve(UDAY).resolve("unknown-harness"));
    Files.writeString(harness.resolve("Not.A.Name"), "api-key\nx\n");

    assertEquals(List.of(), store.list(UDAY));
    assertEquals(Optional.empty(), store.defaultFor(UDAY, CLAUDE));
  }

  @Test
  void aGitTokenIsKeptByHostBesideTheLoginsAndOnlyItsHostDateAndFingerprintAreShown()
      throws Exception {
    var store = store();
    store.store(UDAY, CLAUDE, null, "api-key", OTHER_KEY);
    var stored = store.storeGit(UDAY, "GitHub.com", "ghp_uday_0123456789ab");
    store.storeGit(UDAY, "gitlab.example", "glpat-uday_0123456789ab");

    assertEquals(
        new LoginStore.Row(
            "git",
            "github.com",
            "token",
            false,
            stored.storedAt(),
            LoginStore.fingerprint("ghp_uday_0123456789ab")),
        stored);
    assertEquals(
        Map.of("github.com", "ghp_uday_0123456789ab", "gitlab.example", "glpat-uday_0123456789ab"),
        store.gitTokens(UDAY));
    assertEquals(
        List.of(
            "claude-code/default api-key default",
            "git/github.com token",
            "git/gitlab.example token"),
        described(store.list(UDAY)));
    assertFalse(store.list(UDAY).toString().contains("ghp_uday_0123456789ab"));
    var git = dir.resolve("logins").resolve(UDAY).resolve("git");
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(git)));
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(git.resolve("github.com"))));
    assertEquals("ghp_uday_0123456789ab\n", Files.readString(git.resolve("github.com")));
    try (Stream<Path> files = Files.list(git)) {
      assertEquals(2, files.count());
    }
  }

  @Test
  void aGitTokenStoredAgainIsReplacedAndOneRemovedTwiceIsRemovedOnce() {
    var store = store();
    store.storeGit(UDAY, "github.com", "ghp_old_0123456789ab");
    store.storeGit(UDAY, "github.com", "ghp_new_0123456789ab");

    assertEquals(Map.of("github.com", "ghp_new_0123456789ab"), store.gitTokens(UDAY));
    assertTrue(store.removeGit(UDAY, "github.com").isPresent());
    assertEquals(Optional.empty(), store.removeGit(UDAY, "github.com"));
    assertEquals(List.of(), store.list(UDAY));
    assertEquals(Map.of(), store.gitTokens(ANA));
  }

  @Test
  void aGitTokenTakesTheLoginValueRuleAndAHostOutsideTheHostRuleIsRefused() {
    var store = store();
    var value =
        assertThrows(
            IllegalArgumentException.class, () -> store.storeGit(UDAY, "github.com", "a b"));
    assertEquals(VALUE_RULE, value.getMessage());
    assertEquals("git.example.com:8443", LoginStore.requireHost("Git.Example.com:8443"));
    for (var host : List.of("../x", "-github.com", "git hub", "", "h:0", "h:65536", "h:x")) {
      assertThrows(
          IllegalArgumentException.class, () -> store.storeGit(UDAY, host, "ghp_x_0123456789ab"));
    }
    assertEquals(List.of(), store.list(UDAY));
  }

  /**
   * F3: a git token shorter than any forge's is refused at the store, and no harness is named git,
   * so a harness's logins and the git tokens never share a directory.
   */
  @Test
  void aGitTokenShorterThanAnyForgesIsRefusedAndNoHarnessIsNamedGit() {
    var store = store();

    var refused =
        assertThrows(IllegalArgumentException.class, () -> store.storeGit(UDAY, "github.com", "a"));

    assertEquals(
        "A git token is at least 16 characters, as every forge's is: this value is too short to be"
            + " one.",
        refused.getMessage());
    assertEquals(Map.of(), store.gitTokens(UDAY));
    assertTrue(Harnesses.all().stream().noneMatch(h -> h.yamlName().equals(LoginStore.GIT)));
  }

  @Test
  void forgettingAPersonDeletesTheirGitTokensWithTheirLogins() {
    var store = store();
    store.storeGit(UDAY, "github.com", "ghp_uday_0123456789ab");
    store.storeGit(ANA, "github.com", "ghp_ana_0123456789ab");

    store.forget(UDAY);
    assertEquals(Map.of(), store.gitTokens(UDAY));
    assertEquals(1, store.sweep(id -> false));
    assertEquals(Map.of(), store.gitTokens(ANA));
  }

  private static List<String> described(List<LoginStore.Row> rows) {
    return rows.stream()
        .map(
            row ->
                row.harness()
                    + "/"
                    + row.name()
                    + " "
                    + row.kind()
                    + (row.isDefault() ? " default" : ""))
        .toList();
  }
}
