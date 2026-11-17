/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Refuses every test class of a JVM that would reach a real Sail: the person's own {@code ~/.sail}
 * or the box's provisioned {@code /var/lib/sail}, whose lock files, database and configuration a
 * test would then share with that Sail and with every other test JVM. Registered for every test run
 * of every module, however it is started (Maven's surefire and failsafe, an IDE), through JUnit's
 * extension auto-detection; Maven's runs give each JVM a Sail of its own.
 */
public final class OwnSailGuard implements BeforeAllCallback {

  private static final List<String> REAL =
      reached(
          SailPaths.sailDir(),
          SailPaths.dataDir(),
          SailPaths.locksDir(),
          Optional.ofNullable(System.getenv("HOME")).map(home -> Path.of(home, ".sail")),
          SailPaths.systemDataDir());

  @Override
  public void beforeAll(ExtensionContext context) {
    if (!REAL.isEmpty()) {
      throw new IllegalStateException(
          "This test JVM would use a real Sail: "
              + String.join("; ", REAL)
              + ". Run tests through Maven, which gives each JVM a Sail of its own, or start the"
              + " JVM with -Duser.home=<a directory of the run's own> and -D"
              + SailPaths.NEVER_THE_SYSTEM_SAIL
              + "=true.");
    }
  }

  /**
   * What of {@code sailDir}, {@code dataDir} and {@code locks} lies in the person's own Sail
   * ({@code persons}, when the person's home is known) or in the box's provisioned {@code system}
   * one, each said once.
   */
  static List<String> reached(
      Path sailDir, Path dataDir, Path locks, Optional<Path> persons, Path system) {
    var reached = new ArrayList<String>();
    for (var named :
        List.of(
            Map.entry("its Sail home", sailDir),
            Map.entry("its data directory", dataDir),
            Map.entry("its locks", locks))) {
      var path = named.getValue();
      if (persons.filter(path::startsWith).isPresent()) {
        reached.add(named.getKey() + " " + path + " is the person's own");
      } else if (path.startsWith(system)) {
        reached.add(named.getKey() + " " + path + " is the box's provisioned Sail");
      }
    }
    return reached;
  }
}
