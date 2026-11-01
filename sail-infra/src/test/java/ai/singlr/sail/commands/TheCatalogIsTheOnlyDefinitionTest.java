/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.Sail;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * A project's definition lives in the catalog alone: no command takes a definition file, and no
 * source or resource sail ships names the file that once held one.
 */
class TheCatalogIsTheOnlyDefinitionTest {

  @Test
  void noCommandTakesADefinitionFile() {
    var taking = new ArrayList<String>();
    walk(new CommandLine(new Sail()), taking);

    assertEquals(List.of(), taking, "a command reads a definition from a file");
  }

  @Test
  void nothingSailShipsNamesTheDescriptorFile() throws IOException {
    var naming = new ArrayList<String>();
    for (var root :
        List.of(
            Path.of("..", "sail-core", "src", "main"),
            Path.of("..", "sail-harness", "src", "main"),
            Path.of("src", "main"))) {
      try (var files = Files.walk(root)) {
        for (var file : files.filter(Files::isRegularFile).toList()) {
          if (read(file).contains("sail.yaml")) {
            naming.add(file.toString());
          }
        }
      }
    }

    assertEquals(List.of(), naming, "a shipped file names sail.yaml");
  }

  private static void walk(CommandLine command, List<String> taking) {
    for (var option : command.getCommandSpec().options()) {
      if (List.of(option.names()).contains("--file")) {
        taking.add(command.getCommandSpec().qualifiedName());
      }
    }
    command.getSubcommands().values().forEach(sub -> walk(sub, taking));
  }

  private static String read(Path file) {
    try {
      return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
