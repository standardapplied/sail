/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * W10: every command that walks the box's instances asks {@link BoxInstances} what each is, so the
 * one listing of every instance, {@code incus list --format json}, is built there and nowhere else.
 * A class that lists the instances itself would take a workspace or a template for a project.
 */
class BoxInstancesSweepTest {

  private static final Pattern EVERY_INSTANCE =
      Pattern.compile("\"incus\",\\s*\"list\",\\s*\"--format\"");

  @Test
  void noClassButTheSeamListsEveryInstance() throws IOException {
    var listing =
        List.of(
                Path.of("..", "sail-core", "src", "main", "java"),
                Path.of("..", "sail-harness", "src", "main", "java"),
                Path.of("src", "main", "java"))
            .stream()
            .flatMap(BoxInstancesSweepTest::sources)
            .filter(source -> EVERY_INSTANCE.matcher(read(source)).find())
            .map(source -> source.getFileName().toString())
            .toList();

    assertEquals(List.of("BoxInstances.java"), listing);
  }

  private static Stream<Path> sources(Path root) {
    try (var walk = Files.walk(root)) {
      return walk.filter(path -> path.toString().endsWith(".java")).toList().stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path source) {
    try {
      return Files.readString(source);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
