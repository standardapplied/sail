/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.List;
import java.util.Map;

/** Owners of workspaces as tests give them: a person with an email, no token and no key. */
public final class TestOwners {

  private TestOwners() {}

  public static WorkspaceOwner of(String handle) {
    return new WorkspaceOwner(handle, handle, handle + "@sail.invalid", Map.of(), List.of());
  }
}
