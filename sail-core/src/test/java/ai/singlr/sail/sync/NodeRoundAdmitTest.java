/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.DatabaseCatalog;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M1 and M2 at the node's door: a node admits only the main database it synced with. */
class NodeRoundAdmitTest {

  private Sqlite db;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private static SyncSession welcomedBy(String catalog) {
    var welcome =
        new SyncWire.Welcome(
            SyncWire.PROTOCOL, "0.53.0", "main", "nia", 0, ProjectScope.all(), catalog);
    return SyncSession.open(
        new ByteStreams.Input(SyncWire.encode(welcome) + "\n"),
        new ByteStreams.Output(),
        SyncWire.Hello.of("0.53.0", "node-box"),
        ignored -> {},
        ignored -> {},
        OperatorCommand.PLAIN);
  }

  private void admit(String catalog) {
    try (var session = welcomedBy(catalog)) {
      NodeRound.admit(session, db, "sail@main");
    }
  }

  private static String refusal(String now) {
    return "Main at sail@main is not the database this box synced with (catalog aaaa, now "
        + now
        + "). Restore main's database (/var/lib/sail/sail.db) from a copy, then sync again.";
  }

  @Test
  void aMainThatNamesNoCatalogIsAskedToUpgradeBeforeTheNodeHasRecordedOne() {
    var refused = assertThrows(SyncTransportException.class, () -> admit(null));

    assertEquals("refused", refused.kind());
    assertEquals(
        "Main at sail@main names no database catalog, so this box cannot tell whether it is the"
            + " database it synced with: upgrade main first, then sync again.",
        refused.getMessage());
    assertEquals(Optional.empty(), new DatabaseCatalog(db).main());
  }

  @Test
  void theFirstCatalogIsRecordedAndTheSameOneIsAdmittedAgain() {
    admit("aaaa");

    assertDoesNotThrow(() -> admit("aaaa"));
    assertEquals(Optional.of("aaaa"), new DatabaseCatalog(db).main());
  }

  @Test
  void anotherCatalogIsRefusedAndNeverRecorded() {
    admit("aaaa");

    var refused = assertThrows(SyncTransportException.class, () -> admit("bbbb"));

    assertEquals("refused", refused.kind());
    assertEquals(refusal("bbbb"), refused.getMessage());
    assertEquals(Optional.of("aaaa"), new DatabaseCatalog(db).main());
  }

  @Test
  void aMainThatNamesNoneOnceTheNodeRecordedOneIsAnotherDatabase() {
    admit("aaaa");

    var refused = assertThrows(SyncTransportException.class, () -> admit(null));

    assertEquals(refusal("none"), refused.getMessage());
  }
}
