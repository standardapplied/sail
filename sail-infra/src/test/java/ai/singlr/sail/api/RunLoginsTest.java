/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RL5: a build's login of a kind with a known lifetime, Claude Code's long-lived token (365 days
 * from the day it was stored), is said in the spec's room in its last 14 days as a warning, with
 * how to replace it; the run is not refused, and no other lane nor kind says it.
 */
class RunLoginsTest {

  private static final Instant STORED = Instant.parse("2026-01-01T09:00:00Z");

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdes;
  private final List<String> told = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
    var uday = Acting.system(() -> fdes.add("uday", "Uday", null, "member")).id();
    fdes.logins().store(uday, Harnesses.of("claude-code"), null, "token", "sk-ant-oat01-tok");
    fdes.logins().store(uday, Harnesses.of("codex"), null, "api-key", "sk-codex-key");
    try (var files = Files.walk(tempDir.resolve("logins"))) {
      for (var file : files.filter(Files::isRegularFile).toList()) {
        Files.setLastModifiedTime(file, FileTime.from(STORED));
      }
    }
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private List<String> dispatched(Duration sinceStored, String agent, String role) {
    told.clear();
    new RunLogins(
            fdes, (spec, body) -> told.add(spec + ": " + body), () -> STORED.plus(sinceStored))
        .forRun("auth", "uday", agent, role);
    return List.copyOf(told);
  }

  @Test
  void aBuildsTokenInItsLastFourteenDaysIsSaidWithTheDayItEndsAndHowToReplaceIt() {
    var remedy =
        " Run claude setup-token on a machine where Claude Code is signed in, then sail agent"
            + " login claude-code.";

    assertEquals(
        List.of(
            "auth: Claude Code will refuse uday's login 'default' (long-lived token) from"
                + " 2027-01-01, in 5 days."
                + remedy),
        dispatched(Duration.ofDays(360), "claude-code", "build"));
    assertEquals(
        List.of(
            "auth: Claude Code will refuse uday's login 'default' (long-lived token) from"
                + " 2027-01-01, in 1 day."
                + remedy),
        dispatched(Duration.ofDays(364), "claude-code", "build"));
    assertEquals(
        List.of(
            "auth: Claude Code will refuse uday's login 'default' (long-lived token) from"
                + " 2027-01-01, within a day."
                + remedy),
        dispatched(Duration.ofDays(365).minusHours(2), "claude-code", "build"));
    assertEquals(
        List.of(
            "auth: Claude Code refuses uday's login 'default' (long-lived token) since 2027-01-01."
                + remedy),
        dispatched(Duration.ofDays(400), "claude-code", "build"));
  }

  @Test
  void aTokenWithMoreThanFourteenDaysLeftAReviewersAndAKeyWithNoKnownEndSayNothing() {
    assertEquals(List.of(), dispatched(Duration.ofDays(350), "claude-code", "build"));
    assertEquals(List.of(), dispatched(Duration.ofDays(360), "claude-code", "review"));
    assertEquals(List.of(), dispatched(Duration.ofDays(360), "claude-code", "room"));
    assertEquals(List.of(), dispatched(Duration.ofDays(400), "codex", "build"));
  }
}
