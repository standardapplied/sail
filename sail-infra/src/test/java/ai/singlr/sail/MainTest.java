/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class MainTest {

  private static CommandLine withCapturedErr(StringWriter err) {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(err));
    cmd.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
    return cmd;
  }

  @Test
  void reportPrintsTheMessageAndReturnsNonZero() {
    var err = new StringWriter();
    var code =
        Main.report(new IllegalStateException("boom: run sail host init"), withCapturedErr(err));
    assertEquals(1, code);
    assertTrue(err.toString().contains("boom: run sail host init"), err.toString());
  }

  @Test
  void reportFollowsARefusalWithItsFix() {
    var err = new StringWriter();
    var code =
        Main.report(
            new ApiException(ErrorCode.CONFLICT, "The roster is unsynced.", "Run 'sail sync'."),
            withCapturedErr(err));
    assertEquals(1, code);
    assertTrue(err.toString().contains("The roster is unsynced."), err.toString());
    assertTrue(err.toString().contains("Run 'sail sync'."), err.toString());
  }

  @Test
  void reportFallsBackToTheTypeWhenTheMessageIsBlank() {
    var err = new StringWriter();
    var code = Main.report(new IllegalStateException(), withCapturedErr(err));
    assertEquals(1, code);
    assertFalse(err.toString().isBlank());
    assertTrue(err.toString().contains("IllegalStateException"), err.toString());
  }
}
