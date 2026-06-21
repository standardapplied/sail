/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectMutationsTest {

  @TempDir Path dir;
  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  private PrintStream out;

  @BeforeEach
  void setUp() {
    out = new PrintStream(captured, true, StandardCharsets.UTF_8);
  }

  @Test
  void currentDefinitionReadsAnExplicitFile() throws Exception {
    var file = dir.resolve("custom.yaml");
    Files.writeString(file, "name: acme\n");
    assertEquals("name: acme\n", ProjectMutations.currentDefinition("acme", file));
  }

  @Test
  void currentDefinitionFailsWithGuidanceWhenAbsent() {
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> ProjectMutations.currentDefinition("ghost", dir.resolve("missing.yaml")));
    assertTrue(error.getMessage().contains("sail project create"));
    assertTrue(error.getMessage().contains("sail sync"));
  }

  @Test
  void persistWritesTheExplicitFileWhenOneIsGiven() throws Exception {
    var file = dir.resolve("custom.yaml");
    Files.writeString(file, "name: acme\n");

    ProjectMutations.persist("acme", file, "name: acme\nimage: ubuntu/24.04\n", false, out, "x");

    assertEquals("name: acme\nimage: ubuntu/24.04\n", Files.readString(file));
  }

  @Test
  void persistUnderDryRunPrintsTheIntentAndWritesNothing() throws Exception {
    var file = dir.resolve("custom.yaml");
    Files.writeString(file, "original\n");

    ProjectMutations.persist("acme", file, "changed\n", true, out, "record repo 'x'");

    assertEquals("original\n", Files.readString(file), "dry-run must not write");
    assertTrue(captured.toString(StandardCharsets.UTF_8).contains("[dry-run] record repo 'x'"));
  }

  @Test
  void notFoundNamesBothRemedies() {
    var message = ProjectMutations.notFound("acme").getMessage();
    assertTrue(message.contains("sail project create"));
    assertTrue(message.contains("sail sync"));
    assertFalse(message.contains("null"));
  }
}
