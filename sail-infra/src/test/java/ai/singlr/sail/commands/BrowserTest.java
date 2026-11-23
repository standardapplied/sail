/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ClosesWhatItOpens;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class BrowserTest {

  /**
   * The opener may outlive this process with the browser it starts, so it is given no pipe of ours:
   * its input is empty, so one that reads it ends at once.
   */
  @Test
  void theOpenerIsGivenNoPipeOfOurs() throws Exception {
    var before = ClosesWhatItOpens.baseline();

    var opener = Browser.start(List.of("cat"));

    assertTrue(opener.waitFor(10, TimeUnit.SECONDS), "it read an empty input");
    ClosesWhatItOpens.assertClosed("an opener", before);
  }
}
