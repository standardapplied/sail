/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ApiRouterSelfFilterTest {

  @Test
  void meExpandsToTheActor() {
    assertEquals("uday", ApiRouter.resolveAssignee("me", "uday"));
  }

  @Test
  void meStaysLiteralWhenActorUnknown() {
    assertEquals("me", ApiRouter.resolveAssignee("me", null));
  }

  @Test
  void otherAssigneesPassThrough() {
    assertEquals("alice", ApiRouter.resolveAssignee("alice", "uday"));
  }

  @Test
  void nullAssigneeStaysNull() {
    assertNull(ApiRouter.resolveAssignee(null, "uday"));
  }
}
