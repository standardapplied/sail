/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogNotificationsResolverTest {

  private static final String NOTIFYING =
      """
      name: light-grid
      agent:
        type: claude-code
        notifications:
          url: https://ntfy.sh/light-grid
          events:
            - guardrail_triggered
            - spec_dispatched
      """;

  @TempDir Path dir;
  private Sqlite db;
  private ProjectStore store;
  private CatalogNotificationsResolver resolver;

  @BeforeEach
  void openCatalog() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    store = new ProjectStore(db);
    resolver = new CatalogNotificationsResolver(ProjectReader.ofCatalog(store));
  }

  @AfterEach
  void closeCatalog() {
    db.close();
  }

  @Test
  void aProjectsNotificationsAreItsCatalogRowsAndARevisionIsWhatTheNextEventIsSentUnder() {
    describe("light-grid", NOTIFYING);
    var sent = new ArrayList<String>();
    var reactor =
        new WebhookReactor(
            resolver, url -> (event, project, title, message) -> sent.add(url + " " + event));

    var notifications = resolver.resolve("light-grid");
    assertEquals("https://ntfy.sh/light-grid", notifications.url());
    assertTrue(notifications.events().contains("guardrail_triggered"));

    describe(
        "light-grid", NOTIFYING.replace("https://ntfy.sh/light-grid", "https://ntfy.sh/moved"));
    reactor.onEvent(Event.of("light-grid", null, "spec_dispatched", "sail", "h").withId(1L));

    assertEquals(
        List.of("https://ntfy.sh/moved spec_dispatched"),
        sent,
        "the revision recorded in the catalog is what the next event is sent under, with nothing else run");
  }

  @Test
  void noRowNoAgentBlockAndNoNotificationsAreEachNull() {
    describe("bare", "name: bare\n");
    describe("quiet", "name: quiet\nagent:\n  type: claude-code\n");

    assertNull(resolver.resolve("absent"));
    assertNull(resolver.resolve("bare"));
    assertNull(resolver.resolve("quiet"));
  }

  @Test
  void aRowThatCannotBeReadWarnsAndSendsNothing() {
    describe("oops", NOTIFYING);
    db.execute(
        "UPDATE projects SET definition = ? WHERE name = ?", "not: valid: yaml: here:", "oops");

    var warned = captureStderr(() -> assertNull(resolver.resolve("oops")));

    assertTrue(
        warned.startsWith(
            "  [webhook] Warning: could not load notifications: The definition of project 'oops'"
                + " in the catalog could not be read: "),
        warned);
  }

  @Test
  void aFailureOfTheReaderItselfWarnsAndSendsNothing() {
    var failing =
        new CatalogNotificationsResolver(
            project -> {
              throw new IllegalStateException("database is locked");
            });

    var warned = captureStderr(() -> assertNull(failing.resolve("any")));

    assertEquals(
        "  [webhook] Warning: could not load notifications: database is locked",
        warned.stripTrailing());
  }

  @Test
  void constructorRejectsANullReader() {
    assertThrows(NullPointerException.class, () -> new CatalogNotificationsResolver(null));
  }

  private void describe(String project, String definition) {
    Acting.system(() -> store.upsert(project, definition));
  }

  private static String captureStderr(Runnable work) {
    var original = System.err;
    var buffer = new ByteArrayOutputStream();
    System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      work.run();
    } finally {
      System.setErr(original);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }
}
