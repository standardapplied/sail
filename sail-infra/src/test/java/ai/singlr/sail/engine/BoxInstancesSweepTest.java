/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * W10: every command that walks the box's instances asks {@link BoxInstances} what each is, so a
 * listing of every instance is built there and nowhere else. A class that lists the instances
 * itself would take a workspace or a template for a project. The forms looked for are the argv
 * {@code "incus", "list"} with anything but the one-name {@code "^name$"} after it (so {@code
 * --format}, {@code -f} and {@code -c} columns alike), {@code list} after a constant naming the
 * binary, the API's {@code /1.0/instances}, and {@code incus list} in a shell script, quoted after
 * {@code -c} or as a line of a text block. A listing assembled any other way (an argv built in a
 * loop, a binary named by a variable) is beyond what this sweep can see.
 */
class BoxInstancesSweepTest {

  private static final Map<Pattern, String> FORMS =
      Map.of(
          Pattern.compile("\"incus\",\\s*\"list\"(?!,\\s*\"\\^\")"),
          "List.of(\"incus\", \"list\", \"-c\", \"n\", \"-f\", \"csv\")",
          Pattern.compile("\\b[A-Z][A-Z0-9_]*,\\s*\"list\""),
          "List.of(INCUS, \"list\", \"--format\", \"json\")",
          Pattern.compile("\"/1\\.0/instances"),
          "List.of(\"incus\", \"query\", \"/1.0/instances?recursion=1\")",
          Pattern.compile("\"-c\",\\s*\"[^\"]*\\bincus\\s+list\\b"),
          "List.of(\"bash\", \"-c\", \"incus list -f json | jq .\")",
          Pattern.compile("(?m)^\\s*incus\\s+list\\b"),
          "\"\"\"\n    incus list --format json\n    \"\"\"");

  @Test
  void everyFormTheSweepLooksForIsSeenAndTheOneNameLookupIsNot() {
    var lookup = "List.of(\"incus\", \"list\", \"^\" + name + \"$\", \"--format\", \"json\")";
    for (var form : FORMS.entrySet()) {
      assertTrue(form.getKey().matcher(form.getValue()).find(), form.getValue());
      assertFalse(form.getKey().matcher(lookup).find(), form.getKey().pattern());
    }
  }

  @Test
  void noClassButTheSeamListsEveryInstance() throws IOException {
    var listing =
        List.of(
                Path.of("..", "sail-core", "src", "main", "java"),
                Path.of("..", "sail-harness", "src", "main", "java"),
                Path.of("src", "main", "java"))
            .stream()
            .flatMap(BoxInstancesSweepTest::sources)
            .filter(source -> listsEveryInstance(read(source)))
            .map(source -> source.getFileName().toString())
            .toList();

    assertEquals(List.of("BoxInstances.java"), listing);
  }

  private static boolean listsEveryInstance(String source) {
    return FORMS.keySet().stream().anyMatch(form -> form.matcher(source).find());
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
