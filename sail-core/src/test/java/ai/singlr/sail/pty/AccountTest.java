/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AccountTest {

  /**
   * The account is read where a login reads it, so it is checked against another reader of the same
   * database: perl's {@code getpwuid}, on Linux and macOS alike.
   */
  @Test
  void thisProcessesAccountIsItsEntryInTheAccountDatabase() throws Exception {
    var perl =
        new ProcessBuilder(
                "perl", "-e", "my @p = getpwuid($<); print join(\"\\n\", $p[0], $p[7], $p[8])")
            .redirectErrorStream(true)
            .start();
    assertTrue(perl.waitFor(30, TimeUnit.SECONDS), "perl answered");
    var entry =
        new String(perl.getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("\n");

    assertEquals(new Account(entry[0], Path.of(entry[1]), Path.of(entry[2])), Account.current());
  }
}
