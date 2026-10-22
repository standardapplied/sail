/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshSyncChannelTest {

  private static final String PUB =
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITESTKEYBLOB sail-sync:mady";
  private static final SshPublicKey MAIN =
      SshPublicKey.parse(TestSshKeys.ed25519("main", "root@main"));

  @TempDir Path dir;

  private Optional<String> authorisation(String handle, ScriptedShellExecutor shell)
      throws Exception {
    var publicKey = Files.writeString(dir.resolve("sync_ed25519.pub"), PUB + "\n");
    var knownHosts = Files.writeString(dir.resolve("known_hosts"), "");
    return SshSyncChannel.authorisation(
        handle,
        publicKey,
        "sail@10.0.0.1",
        new MainHostKey(shell, knownHosts),
        new LocalIdentity(shell, dir.resolve("workstation_key.pub"), OperatorCommand.PLAIN));
  }

  private static ScriptedShellExecutor pinnedAndNamed() {
    return new ScriptedShellExecutor()
        .onOk(
            "ssh-keygen -F 10.0.0.1",
            "# Host 10.0.0.1 found: line 1\n|1|c2FsdA==|aG1hYw== ssh-ed25519 " + MAIN.blob() + "\n")
        .onOk("user.name", "Mady M\n")
        .onOk("user.email", "mady@example.com\n");
  }

  @Test
  void aRefusedKeyIsAuthorisedByTheLineTheJoinPrinted() throws Exception {
    assertEquals(
        Optional.of(
            "sudo sail fde add mady --role member --name \"Mady M\" --email \"mady@example.com\""
                + " --key \""
                + PUB
                + "\" --main-host-key "
                + MAIN.fingerprint()),
        authorisation("mady", pinnedAndNamed()));
  }

  @Test
  void noHandleNoPinOrAnUnreadablePinNamesNoLine() throws Exception {
    assertEquals(Optional.empty(), authorisation(null, pinnedAndNamed()));
    assertEquals(
        Optional.empty(),
        authorisation("mady", new ScriptedShellExecutor().onOk("ssh-keygen -F 10.0.0.1", "")));
    assertEquals(
        Optional.empty(),
        authorisation(
            "mady",
            new ScriptedShellExecutor()
                .onThrow("ssh-keygen -F", new IOException("ssh-keygen: not found"))));
  }

  @Test
  void aBoxWithNoPublicKeyNamesNoLine() throws Exception {
    var knownHosts = Files.writeString(dir.resolve("known_hosts"), "");
    var shell = pinnedAndNamed();

    assertEquals(
        Optional.empty(),
        SshSyncChannel.authorisation(
            "mady",
            dir.resolve("absent.pub"),
            "sail@10.0.0.1",
            new MainHostKey(shell, knownHosts),
            new LocalIdentity(shell, dir.resolve("workstation_key.pub"), OperatorCommand.PLAIN)));
  }

  @Test
  void authorizeLineCarriesNameAndEmailWhenPresent() {
    assertEquals(
        "sudo sail fde add mady --role member --name \"Mady M\" --email \"mady@example.com\""
            + " --key \""
            + PUB
            + "\" --main-host-key "
            + MAIN.fingerprint(),
        SshSyncChannel.authorizeLine(
            "mady", "Mady M", "mady@example.com", PUB, MAIN.fingerprint()));
  }

  @Test
  void authorizeLineOmitsBlankNameAndEmail() {
    assertEquals(
        "sudo sail fde add mady --role member --key \""
            + PUB
            + "\" --main-host-key "
            + MAIN.fingerprint(),
        SshSyncChannel.authorizeLine("mady", null, "  ", PUB, MAIN.fingerprint()));
  }

  @Test
  void anUnreachableMainAndABrokenConnectionFailWithinTheHealthWindow() {
    var command = SshSyncChannel.sshCommand("sail@main", null);
    assertTrue(command.contains("BatchMode=yes"));
    assertTrue(command.contains("ConnectTimeout=10"));
    assertTrue(command.contains("ConnectionAttempts=1"));
    assertTrue(command.contains("ServerAliveInterval=10"));
    assertTrue(command.contains("ServerAliveCountMax=2"));
  }

  @Test
  void sshCommandTargetsTheGatewayAndRunsTheSyncServer() {
    var command = SshSyncChannel.sshCommand("sail@maindevbox", null);

    assertEquals("ssh", command.getFirst());
    assertEquals(List.of("sail", "_sync"), command.subList(command.size() - 2, command.size()));
    assertTrue(command.contains("sail@maindevbox"));
  }

  @Test
  void sshCommandForbidsPasswordAndKeyboardInteractiveAuth() {
    var command = SshSyncChannel.sshCommand("sail@host", null);

    assertTrue(command.contains("PasswordAuthentication=no"));
    assertTrue(command.contains("KbdInteractiveAuthentication=no"));
  }

  @Test
  void sshCommandWithoutAnIdentityOmitsTheKeyFlags() {
    var command = SshSyncChannel.sshCommand("sail@host", null);

    assertFalse(command.contains("-i"));
    assertFalse(command.contains("IdentitiesOnly=yes"));
  }

  @Test
  void sshCommandPinsTheManagedKeyWhenAnIdentityIsGiven() {
    var command = SshSyncChannel.sshCommand("sail@host", Path.of("/home/dev/.sail/sync_ed25519"));

    assertTrue(command.contains("IdentitiesOnly=yes"));
    var i = command.indexOf("-i");
    assertTrue(i > 0);
    assertEquals("/home/dev/.sail/sync_ed25519", command.get(i + 1));
    assertEquals(List.of("sail", "_sync"), command.subList(command.size() - 2, command.size()));
    assertTrue(command.indexOf("sail@host") < command.indexOf("sail"));
  }
}
