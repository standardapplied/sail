/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.StubHarness;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ConnectCommandTest {

  @Test
  void snippetUsesTheProjectsContainerUserNotAHardcodedDev() {
    var snippet =
        ConnectCommand.connectSnippet(
            "mainbox", "10.0.0.1", "uday", "acme", "10.1.1.5", "engineer", "~/.ssh/id_ed25519");

    assertTrue(snippet.contains("User engineer"), "container ssh user from the definition");
    assertTrue(snippet.contains("zed ssh://engineer@acme/home/engineer/workspace"));
    assertFalse(snippet.contains("dev@"), "no hardcoded dev user");
  }

  @Test
  void snippetPointsIdentityFileAtTheRegisteredKey() {
    var snippet =
        ConnectCommand.connectSnippet(
            "mainbox", "10.0.0.1", "uday", "acme", "10.1.1.5", "engineer", "~/.ssh/id_rsa");

    assertTrue(snippet.contains("IdentityFile ~/.ssh/id_rsa"), "uses the derived identity file");
    assertFalse(snippet.contains("id_ed25519"), "no hardcoded ed25519 when another key is set");
  }

  @Test
  void jsonReportsTheResolvedContainerUserAndKeyStatus() {
    var map =
        ConnectCommand.connectJson(
            "acme", "10.0.0.1", "uday", "10.1.1.5", "engineer", "~/.ssh/id_ed25519", false);
    assertEquals("engineer", map.get("container_user"));
    assertEquals("acme", map.get("project"));
    assertEquals("~/.ssh/id_ed25519", map.get("identity_file"));
    assertEquals(false, map.get("workstation_key_set"));
  }

  @Test
  void theSnippetIsTheOneAProjectHasAlwaysBeenGivenWithEachHarnesssLoginPort() {
    assertEquals(
        """
        # Add to ~/.ssh/config on your Mac:

        Host mainbox
            HostName 10.0.0.1
            User uday
            IdentityFile ~/.ssh/id_ed25519

        Host acme
            HostName 10.1.1.5
            User dev
            ProxyJump mainbox
            IdentityFile ~/.ssh/id_ed25519

        # Then connect:
        #   ssh acme
        #   zed ssh://dev@acme/home/dev/workspace
        #
        # Agent auth (port forwarding for subscription login):
        #   ssh -N -L 3000:localhost:3000 acme""",
        ConnectCommand.connectSnippet(
            "mainbox", "10.0.0.1", "uday", "acme", "10.1.1.5", "dev", "~/.ssh/id_ed25519"));
  }

  @Test
  void noHintNamesTheOldProductsJumpHost() throws Exception {
    try (var sources = Files.walk(Path.of("src", "main"))) {
      var naming =
          sources
              .filter(Files::isRegularFile)
              .filter(file -> read(file).contains("singular-server"))
              .toList();

      assertEquals(List.of(), naming);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void aBoxWithNoServerIpIsToldBothFixesAsItsOperatorTypesThem() {
    assertEquals(
        "Server IP not configured. Fix with one of:\n"
            + "  - sudo sail host config set server-ip <your-server-ip>\n"
            + "  - Or pass: sudo sail project connect acme --server-ip <your-server-ip>",
        ConnectCommand.serverIpUnset("acme", new OperatorCommand(true)));
    assertEquals(
        "Server IP not configured. Fix with one of:\n"
            + "  - sudo sail host config set server-ip <your-server-ip>\n"
            + "  - Or pass: sail project connect acme --server-ip <your-server-ip>",
        ConnectCommand.serverIpUnset("acme", OperatorCommand.PLAIN));
  }

  private static Harness loggingInThrough(String name, int port) {
    return StubHarness.loggingInThrough(name, OptionalInt.of(port));
  }

  @Test
  void theTunnelIsForThePortTheHarnessNamesAtBothEnds() {
    assertEquals(
        "\n#\n# Agent auth (port forwarding for subscription login):\n"
            + "#   ssh -N -L 4100:localhost:4100 acme",
        ConnectCommand.agentAuth(List.of(loggingInThrough("one", 4100)), "acme"));
  }

  @Test
  void harnessesThatShareALoginPortAreToldOfItOnceAndOnesThatDifferEachOfTheirs() {
    var told =
        ConnectCommand.agentAuth(
            List.of(
                loggingInThrough("one", 4100),
                StubHarness.loggingInThrough("none", OptionalInt.empty()),
                loggingInThrough("two", 4100),
                loggingInThrough("three", 5200)),
            "acme");

    assertEquals(
        "\n#\n# Agent auth (port forwarding for subscription login):\n"
            + "#   ssh -N -L 4100:localhost:4100 acme"
            + "\n#\n# Agent auth (port forwarding for subscription login):\n"
            + "#   ssh -N -L 5200:localhost:5200 acme",
        told);
  }

  @Test
  void harnessesThatLogInWithoutATunnelAddNothingToTheSnippet() {
    assertEquals(
        "",
        ConnectCommand.agentAuth(
            List.of(StubHarness.loggingInThrough("none", OptionalInt.empty())), "acme"));
    assertEquals("", ConnectCommand.agentAuth(List.of(), "acme"));
  }
}
