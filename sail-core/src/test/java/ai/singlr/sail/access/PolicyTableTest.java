/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A1: ARCHITECTURE.md's permission table is the one {@link Policy} holds, so the doc cannot drift.
 */
class PolicyTableTest {

  private static final String SECTION = "## Access";

  @Test
  void architectureCarriesThePolicysTable() throws IOException {
    assertEquals(Policy.table(), tableOf(Files.readString(Path.of("..", "ARCHITECTURE.md"))));
  }

  /** The first table of the Access section: its consecutive lines that start with a bar. */
  private static String tableOf(String document) {
    var start = document.indexOf("\n" + SECTION + "\n");
    assertNotEquals(-1, start, "ARCHITECTURE.md has no '" + SECTION + "' section");
    return document
        .substring(start + 1)
        .lines()
        .dropWhile(line -> !line.startsWith("|"))
        .takeWhile(line -> line.startsWith("|"))
        .collect(Collectors.joining("\n"));
  }
}
