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
import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.authority.WriteRefused;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
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
  void reportFollowsAWriteTheJournalRefusedWithItsFix() {
    var err = new StringWriter();
    var code =
        Main.report(
            new WriteRefused(
                new Refusal(Refusal.Kind.ADMIN_ONLY, "Renaming is an admin's.", "Ask an admin.")),
            withCapturedErr(err));
    assertEquals(1, code);
    assertTrue(err.toString().contains("Renaming is an admin's."), err.toString());
    assertTrue(err.toString().contains("Ask an admin."), err.toString());
  }

  @Test
  void reportFallsBackToTheTypeWhenTheMessageIsBlank() {
    var err = new StringWriter();
    var code = Main.report(new IllegalStateException(), withCapturedErr(err));
    assertEquals(1, code);
    assertFalse(err.toString().isBlank());
    assertTrue(err.toString().contains("IllegalStateException"), err.toString());
  }

  @Test
  void anAccountThatDoesNotOwnTheBoxIsToldItsCommandWithSudo() {
    assertEquals(
        "sail-api on this box runs as root, so dev's sail would see none of its specs, projects or"
            + " FDEs. Run it with sudo: sudo sail spec create --id a --title 'b c'",
        Main.refusal("dev", new String[] {"spec", "create", "--id", "a", "--title", "b c"}));
    assertEquals(
        "sail-api on this box runs as root, so dev's sail would see none of its specs, projects or"
            + " FDEs. Run it with sudo: sudo sail",
        Main.refusal("dev", new String[0]));
  }

  @Test
  void onlyHelpAndTheVersionAreExemptFromTheRefusal() {
    for (var flag : List.of("--version", "-V", "--help", "-h")) {
      assertTrue(Main.exempt(new String[] {flag}), flag);
    }
    assertTrue(Main.exempt(new String[] {"spec", "list", "--help"}));
    assertFalse(Main.exempt(new String[] {"spec", "list"}));
    assertFalse(Main.exempt(new String[] {"sync"}));
    assertFalse(Main.exempt(new String[0]));
  }

  @Test
  void aHelpFlagPicocliPassesOnAsAnArgumentIsNoExemption() {
    assertFalse(
        Main.exempt(
            new String[] {"project", "exec", "acme", "--", "sh", "-c", "touch /tmp/x", "--help"}));
    assertFalse(Main.exempt(new String[] {"spec", "list", "--no-such-flag"}));
  }
}
