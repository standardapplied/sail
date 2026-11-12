/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The compiled classes of a module, for the sweeps that read what a module's code names. */
public final class ModuleClasses {

  private ModuleClasses() {}

  /**
   * The bytes of every class in the module {@code anchor} was loaded from: a directory or a jar.
   */
  public static List<byte[]> of(Class<?> anchor) throws IOException, URISyntaxException {
    var location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (Files.isDirectory(location)) {
      return classesUnder(location);
    }
    try (var jar = FileSystems.newFileSystem(location)) {
      return classesUnder(jar.getPath("/"));
    }
  }

  private static List<byte[]> classesUnder(Path root) throws IOException {
    var classes = new ArrayList<byte[]>();
    try (var files = Files.walk(root)) {
      files
          .filter(file -> file.toString().endsWith(".class"))
          .forEach(
              file -> {
                try {
                  classes.add(Files.readAllBytes(file));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }
    return classes;
  }
}
