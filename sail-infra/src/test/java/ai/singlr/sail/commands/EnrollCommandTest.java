/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ClientConfig;
import ai.singlr.sail.engine.ShellExec;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnrollCommandTest {

  @Test
  void mintCommandRidesTheGatewayLaneAndNeverPrompts() {
    var command = EnrollCommand.mintCommand(new ClientConfig("devbox"));
    assertEquals("ssh", command.getFirst());
    assertTrue(
        command.containsAll(
            List.of(
                "BatchMode=yes", "PasswordAuthentication=no", "KbdInteractiveAuthentication=no")));
    assertTrue(command.contains("sail@devbox"));
    assertEquals(
        List.of("sail", "fde", "enroll", "--json"),
        command.subList(command.size() - 4, command.size()));
    assertEquals("--", command.get(command.indexOf("sail@devbox") - 1));
  }

  @Test
  void parseTicketReadsTheGatewayJson() {
    var ticket =
        EnrollCommand.parseTicket(
            """
            {"ticket": "tkt_abc", "fde": "uday", "expires_at": "2026-07-07T10:15:30Z"}
            """);
    assertEquals("tkt_abc", ticket.ticket());
    assertEquals("uday", ticket.fde());
  }

  @Test
  void parseTicketFailsLoudOnUnexpectedOutput() {
    var error =
        assertThrows(
            IllegalStateException.class, () -> EnrollCommand.parseTicket("sail: unknown option"));
    assertTrue(error.getMessage().contains("sail: unknown option"));
    assertTrue(error.getMessage().contains("sail host update"));
  }

  @Test
  void enrollUrlTargetsTheCanonicalOriginAndEncodesEveryParameter() {
    var url = EnrollCommand.enrollUrl("tkt/+abc", "http://127.0.0.1:49152/callback", "state-nonce");
    assertTrue(url.startsWith(SshTunnel.ORIGIN + "/enroll?ticket=tkt%2F%2Babc"));
    assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A49152%2Fcallback"));
    assertTrue(url.endsWith("&state=state-nonce"));
  }

  @Test
  void mintFailureNamesTheGatewayAndSurfacesSshStderr() {
    var failure =
        EnrollCommand.mintFailure(
            "sail@devbox", new ShellExec.Result(255, "", "Permission denied (publickey).\n"));
    assertTrue(failure.contains("sail@devbox"));
    assertTrue(failure.contains("ssh exit 255"));
    assertTrue(failure.contains("Permission denied (publickey)."));
    assertTrue(failure.contains("sail fde add"));
  }

  @Test
  void mintFailureStaysCleanWhenSshWasSilent() {
    var failure = EnrollCommand.mintFailure("sail@devbox", new ShellExec.Result(1, "", ""));
    assertTrue(failure.contains("ssh exit 1"));
    assertTrue(failure.lines().noneMatch(line -> line.contains("ssh said")));
  }
}
