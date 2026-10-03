/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.store.RunStore;
import java.util.List;

/** Review lanes for a test whose pipeline launches nothing: any use is the test's failure. */
final class NoReviewLanes implements ReviewLanes {

  @Override
  public String launch(Invocation invocation, String boxHandle) {
    throw new AssertionError("this test launches no " + invocation.lane().wire() + " run");
  }

  @Override
  public String output(RunStore.RunRow run) {
    throw new AssertionError("this test reads no run's output");
  }

  @Override
  public List<Rescue> ensureCommitted(
      String project, List<String> repos, String branch, String commitMessage) {
    throw new AssertionError("this test rescues no work");
  }
}
