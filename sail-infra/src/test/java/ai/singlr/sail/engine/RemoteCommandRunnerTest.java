/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ClientConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteCommandRunnerTest {

  private static final ClientConfig CONFIG = new ClientConfig("kubera-server");

  @Test
  void buildSshCommandForNonInteractive() {
    var runner = new RemoteCommandRunner(CONFIG);

    var cmd = runner.buildSshCommand(new String[] {"spec", "list", "kubera"}, false);

    assertEquals("ssh", cmd.getFirst());
    assertFalse(cmd.contains("-t"));
    assertTrue(cmd.contains("sail@kubera-server"));
    assertTrue(cmd.contains("sail"));
    assertTrue(cmd.contains("spec"));
    assertTrue(cmd.contains("list"));
    assertTrue(cmd.contains("kubera"));
  }

  @Test
  void gatewayCommandsTargetTheSailUser() {
    var runner = new RemoteCommandRunner(CONFIG);

    assertEquals("sail@kubera-server", runner.sshTarget(new String[] {"spec", "list"}));
    assertEquals("sail@kubera-server", runner.sshTarget(new String[] {"agent", "status"}));
    assertEquals("sail@kubera-server", runner.sshTarget(new String[] {"events", "tail"}));
    assertEquals("sail@kubera-server", runner.sshTarget(new String[] {"fde", "list"}));
  }

  @Test
  void hostPrivilegedCommandsTargetThePlainHost() {
    var runner = new RemoteCommandRunner(CONFIG);

    assertEquals("kubera-server", runner.sshTarget(new String[] {"project", "up", "demo"}));
    assertEquals("kubera-server", runner.sshTarget(new String[] {"shell", "demo"}));
    assertEquals("kubera-server", runner.sshTarget(new String[] {}));
  }

  @Test
  void blankGatewayUserDisablesTheGatewayLane() {
    var runner = new RemoteCommandRunner(new ClientConfig("kubera-server", ""));

    assertEquals("kubera-server", runner.sshTarget(new String[] {"spec", "list"}));
  }

  @Test
  void gatewayFailureMessageExplainsKeyRegistration() {
    var runner = new RemoteCommandRunner(CONFIG);

    var message = runner.connectionFailureMessage("sail@kubera-server");

    assertTrue(message.contains("Cannot connect to sail@kubera-server"));
    assertTrue(message.contains("sail fde add"));
    assertTrue(message.contains("sail host keys sync"));
  }

  @Test
  void plainLaneFailureMessageOmitsGatewayGuidance() {
    var runner = new RemoteCommandRunner(CONFIG);

    var message = runner.connectionFailureMessage("kubera-server");

    assertTrue(message.contains("Cannot connect to kubera-server"));
    assertFalse(message.contains("sail fde add"));
  }

  @Test
  void gatewayLaneForbidsPasswordFallback() {
    var runner = new RemoteCommandRunner(CONFIG);

    var cmd = runner.buildSshCommand(new String[] {"spec", "list"}, false);

    assertEquals(
        List.of(
            "ssh",
            "-o",
            "PasswordAuthentication=no",
            "-o",
            "KbdInteractiveAuthentication=no",
            "sail@kubera-server",
            "sail",
            "spec",
            "list"),
        cmd);
  }

  @Test
  void plainLaneLeavesSshAuthenticationAlone() {
    var runner = new RemoteCommandRunner(CONFIG);

    var cmd = runner.buildSshCommand(new String[] {"project", "up", "demo"}, false);

    assertEquals(List.of("ssh", "kubera-server", "sail", "project", "up", "demo"), cmd);
  }

  @Test
  void buildSshCommandForInteractive() {
    var runner = new RemoteCommandRunner(CONFIG);

    var cmd = runner.buildSshCommand(new String[] {"shell", "kubera"}, true);

    assertTrue(cmd.contains("-t"));
    assertTrue(cmd.contains("shell"));
  }

  @Test
  void buildSshCommandUsesHostDirectly() {
    var config = new ClientConfig("10.0.0.1");
    var runner = new RemoteCommandRunner(config);

    var cmd = runner.buildSshCommand(new String[] {"up", "demo"}, false);

    assertTrue(cmd.contains("10.0.0.1"));
  }

  @Test
  void buildSshCommandPreservesAllArgs() {
    var runner = new RemoteCommandRunner(CONFIG);
    var args = new String[] {"dispatch", "kubera", "--spec", "auth", "--json"};

    var cmd = runner.buildSshCommand(args, false);

    var sailIdx = cmd.indexOf("sail");
    var tail = cmd.subList(sailIdx + 1, cmd.size());
    assertEquals(List.of("dispatch", "kubera", "--spec", "auth", "--json"), tail);
  }

  @Test
  void buildSshCommandForEmptyArgs() {
    var runner = new RemoteCommandRunner(CONFIG);

    var cmd = runner.buildSshCommand(new String[] {}, false);

    assertTrue(cmd.contains("sail"));
    assertEquals(cmd.indexOf("sail"), cmd.size() - 1);
  }

  @Test
  void isLocalCommandRecognizesVersionUpgradeInitAndLogin() {
    assertTrue(RemoteCommandRunner.isLocalCommand("--version"));
    assertTrue(RemoteCommandRunner.isLocalCommand("-V"));
    assertTrue(RemoteCommandRunner.isLocalCommand("upgrade"));
    assertTrue(RemoteCommandRunner.isLocalCommand("init"));
    assertTrue(RemoteCommandRunner.isLocalCommand("login"));
    assertFalse(RemoteCommandRunner.isLocalCommand("spec"));
    assertFalse(RemoteCommandRunner.isLocalCommand("dispatch"));
  }

  @Test
  void localVersionCommandExecutesWithoutForking() {
    var runner = new RemoteCommandRunner(CONFIG);

    var exitCode = runner.execute(new String[] {"-V"});

    assertEquals(0, exitCode);
  }

  @Test
  void isHostOnlyCommandRecognizesHostSubcommands() {
    assertTrue(RemoteCommandRunner.isHostOnlyCommand("host"));
    assertFalse(RemoteCommandRunner.isHostOnlyCommand("project"));
  }

  @Test
  void isInteractiveCommandRecognizesShellAndExec() {
    assertTrue(RemoteCommandRunner.isInteractiveCommand("shell"));
    assertTrue(RemoteCommandRunner.isInteractiveCommand("exec"));
    assertFalse(RemoteCommandRunner.isInteractiveCommand("dispatch"));
  }

  @Test
  void sshCommandIsImmutable() {
    var runner = new RemoteCommandRunner(CONFIG);

    var cmd = runner.buildSshCommand(new String[] {"up", "demo"}, false);

    assertThrows(UnsupportedOperationException.class, () -> cmd.add("extra"));
  }

  @Test
  void sshCommandIsSimpleWithAlias() {
    var runner = new RemoteCommandRunner(CONFIG);

    var cmd = runner.buildSshCommand(new String[] {"ps", "kubera"}, false);

    assertEquals(List.of("ssh", "kubera-server", "sail", "ps", "kubera"), cmd);
  }
}
