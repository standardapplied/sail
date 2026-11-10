/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.common.Remedy;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A refusal is a kind, a message and a fix, and the facts every rule shares are worded once. */
class RefusalTest {

  @Test
  void aRefusalNamesItsKindAndMessageAndAMissingFixIsNone() {
    assertEquals(
        new Refusal(Refusal.Kind.FIXED, "no.", Remedy.NONE),
        new Refusal(Refusal.Kind.FIXED, "no.", (String) null));
    assertEquals(
        new Refusal(Refusal.Kind.FIXED, "no.", Remedy.NONE),
        new Refusal(Refusal.Kind.FIXED, "no.", (Remedy) null));
    assertThrows(NullPointerException.class, () -> new Refusal(null, "no.", "Fix it."));
    assertThrows(NullPointerException.class, () -> new Refusal(Refusal.Kind.FIXED, null, "x"));
  }

  @Test
  void theSharedFactsAreWordedOnce() {
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.NO_CONVERSATION,
                "No conversation 'attic' is on this box: it was deleted, or never here.",
                "Check the spec or room id.")),
        Refusal.noConversation("attic"));
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.FIXED,
                "The room_id of spec 's-1' is set when it is created and never changes.",
                "Create a new spec instead.")),
        Refusal.fixed("spec", "s-1", "room_id"));
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.READ_ONLY,
                "Your credential is read-only and cannot change specs.",
                "Ask an admin for a member or admin credential.")),
        Refusal.readOnly("change specs"));
  }
}
