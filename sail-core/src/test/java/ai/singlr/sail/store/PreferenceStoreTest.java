/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.PreferenceStore.StartsIn;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A person's preferences on this box: none until set, replaced by the next, one of three words the
 * table itself holds to, and erased with their person.
 */
@ActingAs(value = Actor.Lane.CLI, handle = "uday")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PreferenceStoreTest {

  private Sqlite db;
  private PreferenceStore preferences;
  private String maya;
  private String ana;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    preferences = new PreferenceStore(db);
    var fdes = new FdeStore(db);
    Acting.system(() -> fdes.add("uday", "Uday", null, "admin"));
    maya = Acting.system(() -> fdes.add("maya", "Maya", null, "member")).id();
    ana = Acting.system(() -> fdes.add("ana", "Ana", null, "member")).id();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aPreferenceIsNoneUntilSetAndTheNextReplacesIt() {
    assertEquals(Optional.empty(), preferences.sessionForm(maya));

    preferences.sessionForm(maya, StartsIn.TERMINAL);
    preferences.sessionForm(ana, StartsIn.BOTH);
    preferences.sessionForm(maya, StartsIn.CHAT);

    assertEquals(
        List.of(Optional.of(StartsIn.CHAT), Optional.of(StartsIn.BOTH)),
        List.of(preferences.sessionForm(maya), preferences.sessionForm(ana)));
  }

  @Test
  void theTableHoldsOnlyTheThreeWordsAndOnlyAPersonOfTheRoster() {
    assertThrows(
        RuntimeException.class,
        () -> db.execute("INSERT INTO preferences (person, session_form) VALUES (?, 'tab')", maya));
    assertThrows(
        RuntimeException.class,
        () -> preferences.sessionForm("fde_nobody", StartsIn.CHAT),
        "only a person the roster holds");
    assertEquals(Optional.empty(), preferences.sessionForm(maya));
  }

  /** I8: a person's preferences go with them, and no one else's. */
  @Test
  void removingAPersonErasesTheirPreferences() {
    preferences.sessionForm(maya, StartsIn.TERMINAL);
    preferences.sessionForm(ana, StartsIn.BOTH);

    Acting.system(() -> new FdeStore(db).remove(maya));

    assertEquals(
        List.of(0L, Optional.of(StartsIn.BOTH)),
        List.of(
            db.queryOne(
                    "SELECT COUNT(*) FROM preferences WHERE person = ?",
                    row -> row.integer(0),
                    maya)
                .orElseThrow(),
            preferences.sessionForm(ana)));
  }
}
