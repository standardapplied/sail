/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
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
 * from the day it was stored), is said in its last 14 days as a warning, with how to replace it, in
 * the dispatch's own output, as an event and in the spec's room; the run is not refused, and no
 * other lane nor kind says it. Its count of days is the count its date reads, in UTC.
 */
class RunLoginsTest {

  private static final Instant STORED = Instant.parse("2026-01-01T09:00:00Z");

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdes;
  private RunStore runs;
  private final List<String> told = new ArrayList<>();
  private final List<String> printed = new ArrayList<>();
  private final List<Event> published = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
    runs = new RunStore(db);
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

  private List<String> dispatched(Instant now, String agent, String role) {
    told.clear();
    var logins =
        new RunLogins(
            fdes,
            (spec, body) -> told.add(spec + ": " + body),
            new DispatchOperations.Listener() {
              @Override
              public void loginEnding(String said) {
                printed.add(said);
              }
            },
            published::add,
            () -> now);
    var stored = logins.forRun("auth", "uday", agent).orElseThrow();
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runs.create(id, "acme", "auth", "uday", role, agent, "b", "t", null, null, "/l", "u"));
    logins.ending(runs.findById(id).orElseThrow(), stored);
    return List.copyOf(told);
  }

  private static String warning(String when) {
    return "auth: Claude Code will refuse uday's login 'default' (long-lived token) from "
        + when
        + ". Run claude setup-token on a machine where Claude Code is signed in, then sail agent"
        + " login claude-code.";
  }

  /**
   * Low 4: the days are counted by date in UTC, so the count and the date agree at every hour: five
   * days less an hour is "in 5 days" when the date is five days on; across midnight UTC and a
   * month's end too.
   */
  @Test
  void aBuildsTokenInItsLastFourteenDaysIsSaidWithTheDayItEndsCountedByItsDate() {
    var end = Instant.parse("2027-01-01T09:00:00Z");

    assertEquals(
        List.of(warning("2027-01-01, in 5 days")),
        dispatched(end.minus(Duration.ofDays(5)).plusSeconds(3600), "claude-code", "build"));
    assertEquals(
        List.of(warning("2027-01-01, in 5 days")),
        dispatched(Instant.parse("2026-12-27T00:00:01Z"), "claude-code", "build"));
    assertEquals(
        List.of(warning("2027-01-01, tomorrow")),
        dispatched(Instant.parse("2026-12-31T23:59:00Z"), "claude-code", "build"));
    assertEquals(
        List.of(warning("2027-01-01, today")),
        dispatched(Instant.parse("2027-01-01T00:00:00Z"), "claude-code", "build"));
    assertEquals(
        List.of(warning("2027-01-01, in 2 days")),
        dispatched(Instant.parse("2026-12-30T08:59:00Z"), "claude-code", "build"));
    assertEquals(
        List.of(
            "auth: Claude Code refuses uday's login 'default' (long-lived token) since"
                + " 2027-01-01. Run claude setup-token on a machine where Claude Code is signed in,"
                + " then sail agent login claude-code."),
        dispatched(end.plus(Duration.ofDays(35)), "claude-code", "build"));
  }

  /** The warning reaches the dispatch's own output and the bus with the same words as the room. */
  @Test
  void theWarningIsTheDispatchsOwnOutputAndAnEventAsWellAsTheRoomsLine() {
    var said = dispatched(Instant.parse("2026-12-27T09:00:00Z"), "claude-code", "build");

    assertEquals(List.of(warning("2027-01-01, in 5 days")), said);
    assertEquals(List.of(said.getFirst().substring("auth: ".length())), printed);
    assertEquals(
        List.of("ending " + printed.getFirst()),
        published.stream()
            .map(event -> event.data().get("outcome") + " " + event.data().get("detail"))
            .toList());
  }

  @Test
  void aTokenWithMoreThanFourteenDaysLeftAReviewersAndAKeyWithNoKnownEndSayNothing() {
    var end = Instant.parse("2027-01-01T09:00:00Z");
    assertEquals(List.of(), dispatched(end.minus(Duration.ofDays(15)), "claude-code", "build"));
    assertEquals(List.of(), dispatched(end.minus(Duration.ofDays(5)), "claude-code", "review"));
    assertEquals(List.of(), dispatched(end.minus(Duration.ofDays(5)), "claude-code", "room"));
    assertEquals(List.of(), dispatched(end.plus(Duration.ofDays(35)), "codex", "build"));
    assertEquals(List.of(), printed);
    assertEquals(List.of(), published);
  }
}
