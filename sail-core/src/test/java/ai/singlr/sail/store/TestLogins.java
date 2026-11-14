/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;

/**
 * Stores a person's harness login through the production store, as {@code sail agent login} does:
 * the login a test's spec runs start with, without which they are refused before reserving.
 */
public final class TestLogins {

  /** The value every login a test stores holds, so an assertion can look for it anywhere. */
  public static final String VALUE = "sk-test-login-0123456789";

  private TestLogins() {}

  /**
   * Stores {@link #VALUE} as {@code handle}'s default login for every harness, adding {@code
   * handle} to the roster as a member when it is not on it. Returns its FDE id.
   */
  public static String store(Sqlite db, String handle) {
    var fdes = new FdeStore(db);
    var fde =
        fdes.byHandle(handle)
            .orElseGet(() -> Acting.system(() -> fdes.add(handle, handle, null, "member")));
    for (var harness : Harnesses.all()) {
      fdes.logins().store(fde.id(), harness, null, null, VALUE);
    }
    return fde.id();
  }
}
