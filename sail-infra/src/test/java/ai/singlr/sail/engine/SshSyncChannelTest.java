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
import java.nio.charset.StandardCharsets;
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

  private static final String TARGET = "sail@10.0.0.1";

  private Path receipt() {
    return SshSyncChannel.receipt(dir.resolve("host.yaml"));
  }

  private String kept(String target, String handle, String key, String fingerprint)
      throws IOException {
    return SshSyncChannel.keep(
        receipt(), target, handle, "Mady M", "mady@example.com", key, fingerprint);
  }

  private Optional<String> authorisation(String handle, ScriptedShellExecutor shell)
      throws Exception {
    var publicKey = Files.writeString(dir.resolve("sync_ed25519.pub"), PUB + "\n");
    var knownHosts = Files.writeString(dir.resolve("known_hosts"), "");
    return SshSyncChannel.authorisation(
        handle, publicKey, TARGET, new MainHostKey(shell, knownHosts), receipt());
  }

  private static ScriptedShellExecutor pinned() {
    return new ScriptedShellExecutor()
        .onOk(
            "ssh-keygen -F 10.0.0.1",
            "# Host 10.0.0.1 found: line 1\n|1|c2FsdA==|aG1hYw== ssh-ed25519 "
                + MAIN.blob()
                + "\n");
  }

  @Test
  void aRefusedKeyIsAuthorisedByTheLineTheJoinPrinted() throws Exception {
    var printed = kept(TARGET, "mady", PUB, MAIN.fingerprint());

    assertEquals(
        "sudo sail fde add mady --role member --name 'Mady M' --email mady@example.com --key '"
            + PUB
            + "' --main-host-key "
            + MAIN.fingerprint(),
        printed);
    assertEquals(Optional.of(printed), authorisation("mady", pinned()));
  }

  @Test
  void aLineThatNoLongerNamesThisBoxIsNotRepeated() throws Exception {
    kept(TARGET, "ada", PUB, MAIN.fingerprint());
    assertEquals(Optional.empty(), authorisation("mady", pinned()), "another handle");
    kept("sail@10.0.0.2", "mady", PUB, MAIN.fingerprint());
    assertEquals(Optional.empty(), authorisation("mady", pinned()), "another main");
    kept(TARGET, "mady", PUB.replace("BLOB", "OTHER"), MAIN.fingerprint());
    assertEquals(Optional.empty(), authorisation("mady", pinned()), "another key");
    kept(TARGET, "mady", PUB, "SHA256:another");
    assertEquals(Optional.empty(), authorisation("mady", pinned()), "another pin");
  }

  @Test
  void aBoxNoJoinKeptALineForNamesNone() throws Exception {
    assertEquals(Optional.empty(), authorisation("mady", pinned()));
    Files.writeString(receipt(), "target: \"sail@10.0.0.1\nhandle: [mady");
    assertEquals(Optional.empty(), authorisation("mady", pinned()), "a receipt cut short");
  }

  @Test
  void noHandleNoPinOrAnUnreadablePinNamesNoLine() throws Exception {
    kept(TARGET, "mady", PUB, MAIN.fingerprint());
    assertEquals(Optional.empty(), authorisation(null, pinned()));
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
    kept(TARGET, "mady", PUB, MAIN.fingerprint());
    var knownHosts = Files.writeString(dir.resolve("known_hosts"), "");

    assertEquals(
        Optional.empty(),
        SshSyncChannel.authorisation(
            "mady",
            dir.resolve("absent.pub"),
            TARGET,
            new MainHostKey(pinned(), knownHosts),
            receipt()));
  }

  @Test
  void authorizeLineOmitsBlankNameAndEmail() {
    assertEquals(
        "sudo sail fde add mady --role member --key '"
            + PUB
            + "' --main-host-key "
            + MAIN.fingerprint(),
        SshSyncChannel.authorizeLine("mady", null, "  ", PUB, MAIN.fingerprint()));
  }

  @Test
  void aPastedLineGivesSailEveryValueAsTypedAndRunsNothingElse() throws Exception {
    var name = "Ada $(touch pwned) `touch pwned` \"quoted\" O'Brien $HOME";
    var email = "a'b\"$(id)@example.com";
    var line = SshSyncChannel.authorizeLine("mady", name, email, PUB, MAIN.fingerprint());

    var shell =
        new ProcessBuilder("bash", "-c", "sudo() { printf '%s\\n' \"$@\"; }; " + line)
            .directory(dir.toFile())
            .start();
    var argv = new String(shell.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertEquals(0, shell.waitFor());
    assertEquals(
        List.of(
            "sail",
            "fde",
            "add",
            "mady",
            "--role",
            "member",
            "--name",
            name,
            "--email",
            email,
            "--key",
            PUB,
            "--main-host-key",
            MAIN.fingerprint()),
        argv.lines().toList());
    assertFalse(Files.exists(dir.resolve("pwned")));
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

  @Test
  void theReceiptNamesTheLineFirstSoOneCutShortNamesNoBox() throws Exception {
    kept(TARGET, "mady", PUB, MAIN.fingerprint());
    var written = Files.readString(receipt());

    assertEquals(
        List.of("authorize_command", "target", "handle", "public_key", "main_host_key"),
        written.lines().filter(l -> !l.startsWith(" ")).map(l -> l.split(":")[0]).toList(),
        written);

    Files.writeString(receipt(), written.substring(0, written.length() / 2));
    assertEquals(Optional.empty(), authorisation("mady", pinned()));
  }
}
