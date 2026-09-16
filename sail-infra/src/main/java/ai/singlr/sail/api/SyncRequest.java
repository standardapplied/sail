/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

public record SyncRequest(String main) {
  public SyncRequest {
    if (main != null && (main.startsWith("-") || main.indexOf('\0') >= 0)) {
      throw new IllegalArgumentException(
          "Invalid main target: '" + main + "'. Expected an SSH target, e.g. sail@maindevbox.");
    }
  }
}
