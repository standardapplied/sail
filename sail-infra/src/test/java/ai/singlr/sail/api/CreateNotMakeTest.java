/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A workspace or a project is created, never made, in every sentence a person reads: no string
 * literal in any module's main source says make, makes, making or made within two lines of a
 * workspace or a project, save the few listed in {@link #NOT_ABOUT_CREATING}, where "made" means
 * something else.
 */
class CreateNotMakeTest {

  private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  private static final Pattern MAKE = Pattern.compile("\\b(?:[Mm]akes?|[Mm]aking|[Mm]ade)\\b");

  private static final Pattern ABOUT = Pattern.compile("(?i)workspace|project");

  /** Literals where "make" is not about creating a workspace or a project, each saying why. */
  private static final List<String> NOT_ABOUT_CREATING =
      List.of(
          " and nothing its runs made is lost",
          ": a login made by hand in the project's container is not used by runs",
          "'s definition or a change made by hand in ",
          "Make a project's container match its definition: create it if absent, start it if",
          "skills made by hand",
          "make the skills directory for",
          "Make it ready with ",
          ". Once incus answers, make them: '",
          "Switch to a project (start it, make it current), warning if resources overcommit.",
          "'s chat takes your stored API key here, not the sign-in made in the workspace.");

  @Test
  void aWorkspaceOrAProjectIsCreatedNeverMade() throws IOException {
    var found = new ArrayList<String>();
    try (var modules = Files.list(Path.of(".."))) {
      for (var module : modules.sorted().toList()) {
        var main = module.resolve("src/main/java");
        if (!Files.isDirectory(main)) {
          continue;
        }
        try (Stream<Path> files = Files.walk(main)) {
          for (var file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
            found.addAll(made(file));
          }
        }
      }
    }
    assertEquals(List.of(), found);
  }

  private static List<String> made(Path file) throws IOException {
    var lines = Files.readAllLines(file);
    var found = new ArrayList<String>();
    for (var i = 0; i < lines.size(); i++) {
      var line = lines.get(i).strip();
      if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
        continue;
      }
      var literals = LITERAL.matcher(lines.get(i));
      while (literals.find()) {
        var literal = literals.group(1);
        if (!MAKE.matcher(literal).find() || NOT_ABOUT_CREATING.contains(literal)) {
          continue;
        }
        var near =
            String.join(" ", lines.subList(Math.max(0, i - 2), Math.min(lines.size(), i + 3)));
        if (ABOUT.matcher(near.toLowerCase(Locale.ROOT)).find()) {
          found.add(file.getFileName() + ":" + (i + 1) + ": " + literal);
        }
      }
    }
    return found;
  }
}
