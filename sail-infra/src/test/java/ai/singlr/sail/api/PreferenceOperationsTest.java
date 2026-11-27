/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.PreferenceStore.StartsIn;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A person's own preference on this box: {@code chat} until they set another, each person's their
 * own, a viewer's too; three words and one field taken, anything else refused; no agent, container
 * credential or credential of no person reaches one.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PreferenceOperationsTest {

  private Sqlite db;
  private PreferenceOperations preferences;

  @BeforeEach
  void aBoxWithAMemberAViewerAndAnAdmin() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    var fdes = new FdeStore(db);
    Acting.system(
        () -> {
          fdes.add("maya", "Maya", null, "member");
          fdes.add("vik", "Vik", null, "viewer");
          return fdes.add("ana", "Ana", null, "admin");
        });
    preferences = new PreferenceOperations(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  /** L8: a person who never set it starts in a chat. */
  @Test
  void aNewPersonStartsInAChat() {
    assertEquals(
        Map.of("session_form", "chat"), as("maya", Role.MEMBER, () -> preferences.read()).toMap());
  }

  /** L9: each person sets their own, a viewer too, and reads back what they set. */
  @Test
  void eachPersonSetsTheirOwnAViewerToo() {
    var set = as("vik", Role.VIEWER, () -> preferences.keep(Map.of("session_form", "terminal")));
    as("ana", Role.ADMIN, () -> preferences.keep(Map.of("session_form", "both")));

    assertEquals(Map.of("session_form", "terminal"), set.toMap());
    assertEquals(
        List.of(StartsIn.TERMINAL, StartsIn.BOTH, StartsIn.CHAT),
        List.of(
            as("vik", Role.VIEWER, () -> preferences.read()).sessionForm(),
            as("ana", Role.ADMIN, () -> preferences.read()).sessionForm(),
            as("maya", Role.MEMBER, () -> preferences.read()).sessionForm()));
    as("vik", Role.VIEWER, () -> preferences.keep(Map.of("session_form", "chat")));
    assertEquals(StartsIn.CHAT, as("vik", Role.VIEWER, () -> preferences.read()).sessionForm());
  }

  /** I5, I9: three fixed words and one field; anything else is refused and nothing kept. */
  @Test
  void anyOtherValueOrFieldIsRefusedAndNothingKept() {
    var extra = new HashMap<String, Object>(Map.of("session_form", "chat", "theme", "dark"));
    var none = new HashMap<String, Object>();
    none.put("session_form", null);
    var refusals =
        List.<Map<String, Object>>of(
                Map.of("session_form", "tab"),
                Map.of("session_form", "Chat"),
                Map.of("session_form", " chat"),
                Map.of("session_form", 1),
                none,
                Map.of(),
                extra)
            .stream()
            .map(
                body ->
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> as("maya", Role.MEMBER, () -> preferences.keep(body)))
                        .getMessage())
            .toList();

    var value = "session_form is chat, terminal or both.";
    assertEquals(
        List.of(value, value, value, value, value, value, "A preference names only session_form."),
        refusals);
    assertEquals(StartsIn.CHAT, as("maya", Role.MEMBER, () -> preferences.read()).sessionForm());
  }

  /** I5: no agent and no credential in a container keeps one, in the access table's words. */
  @Test
  void noAgentOrContainerCredentialReachesAPreference() {
    for (var lane : List.of(Actor.Lane.AGENT, Actor.Lane.ROOM, Actor.Lane.CONTAINER)) {
      for (Supplier<Object> door :
          List.<Supplier<Object>>of(
              () -> preferences.read(),
              () -> preferences.keep(Map.of("session_form", "terminal")))) {
        var refused =
            assertThrows(
                ApiException.class,
                () -> Actor.call(new Actor("maya", Role.MEMBER, lane), door::get));
        assertEquals(
            lane == Actor.Lane.CONTAINER
                ? List.of(
                    "agent_lane_forbidden",
                    "The credential in a project container cannot keep preferences.")
                : List.of("agent_lane_forbidden", "Agent principals cannot keep preferences."),
            List.of(refused.code(), refused.reason()),
            lane.toString());
      }
    }
    assertEquals(StartsIn.CHAT, as("maya", Role.MEMBER, () -> preferences.read()).sessionForm());
  }

  /** I9: a credential that acts for no person holds no preference to read or set. */
  @Test
  void aCredentialOfNoPersonHoldsNone() {
    for (Supplier<Object> door :
        List.<Supplier<Object>>of(
            () -> preferences.read(), () -> preferences.keep(Map.of("session_form", "chat")))) {
      var refused = assertThrows(ApiException.class, () -> as("ci", Role.MEMBER, door));
      assertEquals(
          List.of(
              "forbidden",
              "This credential acts for no person, so it holds no preferences.",
              "Use a credential of your own."),
          List.of(refused.code(), refused.reason(), refused.remedy().plain()));
    }
  }

  private static <T> T as(String handle, Role role, Supplier<T> work) {
    return Actor.call(new Actor(handle, role, Actor.Lane.API), work::get);
  }
}
