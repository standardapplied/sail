/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ActorTest {

  @Test
  void prefersTheRealEngineerUnderSudo() {
    assertEquals("mady", Actor.resolve("mady", "root"));
  }

  @Test
  void fallsBackToProcessOwnerWhenNotSudo() {
    assertEquals("uday", Actor.resolve(null, "uday"));
  }

  @Test
  void ignoresASudoUserOfRoot() {
    assertEquals("uday", Actor.resolve("root", "uday"));
  }

  @Test
  void ignoresABlankSudoUser() {
    assertEquals("uday", Actor.resolve("  ", "uday"));
  }

  @Test
  void fallsBackToLocalWhenNothingIsKnown() {
    assertEquals("local", Actor.resolve(null, null));
    assertEquals("local", Actor.resolve("root", "  "));
  }
}
