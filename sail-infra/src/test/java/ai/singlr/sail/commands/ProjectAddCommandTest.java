/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ProjectAddCommandTest {

  @Test
  void deprecatedShimPointsToTheServiceAndRepoGroups() {
    var captured = new ByteArrayOutputStream();
    var original = System.err;
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    int exit;
    try {
      exit = new CommandLine(new ProjectAddCommand()).execute("service", "redpanda");
    } finally {
      System.setErr(original);
    }

    var err = captured.toString(StandardCharsets.UTF_8);
    assertEquals(2, exit, "deprecated path signals it did not run");
    assertTrue(err.contains("project service add"), err);
    assertTrue(err.contains("project repo add"), err);
  }
}
