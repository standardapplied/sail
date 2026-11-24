/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;

/** The reactor's paths, as this module's surefire configuration passes them to its tests. */
final class Reactor {

  private Reactor() {}

  static Path path(String property) {
    var value = System.getProperty(property);
    if (value == null) {
      fail(
          "system property "
              + property
              + " is unset, since only this module's Maven build sets it: run these tests from the"
              + " repository's root with mvn verify -pl architecture -am");
    }
    return Path.of(value);
  }
}
