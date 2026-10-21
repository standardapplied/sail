/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainHostKeyTest {

  private static final SshPublicKey MAIN =
      SshPublicKey.parse(TestSshKeys.ed25519("main", "root@main"));
  private static final SshPublicKey IMPOSTOR =
      SshPublicKey.parse(TestSshKeys.ed25519("impostor", "root@elsewhere"));

  @TempDir Path home;

  private Path knownHosts() {
    return home.resolve(".ssh").resolve("known_hosts");
  }

  private static String hashed(SshPublicKey key) {
    return "|1|c2FsdA==|aG1hYw== " + key.type() + " " + key.blob();
  }

  @Test
  void anUnpinnedMainIsAppendedHashedInAPrivateFile() throws Exception {
    var keyscan = new Keyscan(hashed(MAIN), "# maindevbox:22 SSH-2.0-OpenSSH_9.6\n", 0);

    var fingerprint =
        new MainHostKey(keyscan, knownHosts(), OperatorCommand.SUDO).pin("maindevbox");

    assertEquals(MAIN.fingerprint(), fingerprint);
    assertEquals(hashed(MAIN) + "\n", Files.readString(knownHosts()));
    assertEquals("rwx------", mode(knownHosts().getParent()));
    assertEquals("rw-------", mode(knownHosts()));
    assertEquals(
        List.of(List.of("ssh-keyscan", "-H", "-t", "ed25519", "-T", "5", "maindevbox")),
        keyscan.commands);
  }

  @Test
  void anotherHostsEntryIsKeptAndMainsIsAddedOnItsOwnLine() throws Exception {
    Files.createDirectories(knownHosts().getParent());
    Files.writeString(knownHosts(), "github.com ssh-rsa AAAA");
    var keyscan = new Keyscan(hashed(MAIN), "", 0);

    new MainHostKey(keyscan, knownHosts(), OperatorCommand.SUDO).pin("maindevbox");

    assertEquals("github.com ssh-rsa AAAA\n" + hashed(MAIN) + "\n", Files.readString(knownHosts()));
  }

  @Test
  void theSameKeyPinnedAgainLeavesTheFileByteIdentical() throws Exception {
    Files.createDirectories(knownHosts().getParent());
    var pinned = "maindevbox " + MAIN.type() + " " + MAIN.blob() + "\n";
    Files.writeString(knownHosts(), pinned);

    var fingerprint =
        new MainHostKey(new Keyscan(hashed(MAIN), "", 0), knownHosts(), OperatorCommand.SUDO)
            .pin("maindevbox");

    assertEquals(MAIN.fingerprint(), fingerprint);
    assertEquals(pinned, Files.readString(knownHosts()));
  }

  @Test
  void aDifferentKeyIsRefusedNamingBothAndThePinIsKept() throws Exception {
    Files.createDirectories(knownHosts().getParent());
    var pinned = hashed(MAIN) + "\n";
    Files.writeString(knownHosts(), pinned);
    var hostKey =
        new MainHostKey(new Keyscan(hashed(IMPOSTOR), "", 0), knownHosts(), OperatorCommand.SUDO);

    var refused = assertThrows(IllegalStateException.class, () -> hostKey.pin("sail@maindevbox"));

    assertEquals(
        "maindevbox's SSH host key is "
            + IMPOSTOR.fingerprint()
            + ", but this box pinned "
            + MAIN.fingerprint()
            + ". If main was rebuilt from a copy of its database, remove the old key with 'sudo"
            + " ssh-keygen -R maindevbox', re-run 'sudo sail init --main sail@maindevbox' and"
            + " compare the fingerprint it prints with main's ('sudo ssh-keygen -lf"
            + " /etc/ssh/ssh_host_ed25519_key.pub' on main); otherwise something between this box"
            + " and main is answering for it.",
        refused.getMessage());
    assertEquals(pinned, Files.readString(knownHosts()));
  }

  @Test
  void theRecoveryIsOneSentenceWhereverAChangedKeyIsMet() {
    assertEquals(
        "If main was rebuilt from a copy of its database, remove the old key with 'sudo ssh-keygen"
            + " -R 10.128.0.7', re-run 'sudo sail init --main sail@10.128.0.7:2222' and compare the"
            + " fingerprint it prints with main's ('sudo ssh-keygen -lf"
            + " /etc/ssh/ssh_host_ed25519_key.pub' on main); otherwise something between this box"
            + " and main is answering for it.",
        MainHostKey.rebuiltRecovery("sail@10.128.0.7:2222", OperatorCommand.SUDO));
  }

  @Test
  void aFreshBoxsSudoCallerRemovesRootsPinWithSudo() throws Exception {
    Files.createDirectories(knownHosts().getParent());
    Files.writeString(knownHosts(), hashed(MAIN) + "\n");
    var hostKey =
        new MainHostKey(new Keyscan(hashed(IMPOSTOR), "", 0), knownHosts(), OperatorCommand.SUDO);

    var refused = assertThrows(IllegalStateException.class, () -> hostKey.pin("sail@maindevbox"));

    assertEquals(
        "maindevbox's SSH host key is "
            + IMPOSTOR.fingerprint()
            + ", but this box pinned "
            + MAIN.fingerprint()
            + ". If main was rebuilt from a copy of its database, remove the old key with 'sudo"
            + " ssh-keygen -R maindevbox', re-run 'sudo sail init --main sail@maindevbox' and"
            + " compare the fingerprint it prints with main's ('sudo ssh-keygen -lf"
            + " /etc/ssh/ssh_host_ed25519_key.pub' on main); otherwise something between this box"
            + " and main is answering for it.",
        refused.getMessage());
  }

  @Test
  void anAccountsOwnPinIsRemovedWithoutSudo() {
    assertEquals(
        "If main was rebuilt from a copy of its database, remove the old key with 'ssh-keygen -R"
            + " main', re-run 'sudo sail init --main sail@main' and compare the fingerprint it"
            + " prints with main's ('sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub' on"
            + " main); otherwise something between this box and main is answering for it.",
        MainHostKey.rebuiltRecovery("sail@main", OperatorCommand.UNPRIVILEGED));
    assertEquals(
        "If main was rebuilt from a copy of its database, remove the old key with 'ssh-keygen -R"
            + " main', re-run 'sail init --main sail@main' and compare the fingerprint it prints"
            + " with main's ('sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub' on main);"
            + " otherwise something between this box and main is answering for it.",
        MainHostKey.rebuiltRecovery("sail@main", OperatorCommand.PLAIN));
  }

  @Test
  void aTargetsHostDropsItsUserAndPort() {
    assertEquals("host", MainHostKey.hostOf("sail@host"));
    assertEquals("10.0.0.1", MainHostKey.hostOf(" sail@10.0.0.1:2222 "));
    assertEquals("maindevbox", MainHostKey.hostOf("maindevbox"));
  }

  @Test
  void anUnreadableKeyIsRefusedWithSshKeyscansReasonAndNothingIsWritten() {
    var hostKey =
        new MainHostKey(
            new Keyscan("", "getaddrinfo maindevbox: Name or service not known\n", 1),
            knownHosts(),
            OperatorCommand.SUDO);

    var refused = assertThrows(IllegalStateException.class, () -> hostKey.pin("maindevbox"));

    assertEquals(
        "Couldn't read maindevbox's SSH host key on port 22: getaddrinfo maindevbox: Name or"
            + " service not known. Check the address and firewall, then re-run sudo sail init"
            + " --main maindevbox.",
        refused.getMessage());
    assertFalse(Files.exists(knownHosts().getParent()));
  }

  @Test
  void aSilentKeyscanIsRefusedSayingNoKeyCameBack() {
    var hostKey =
        new MainHostKey(
            new Keyscan("not a key line\n", "# maindevbox:22 banner\n", 1),
            knownHosts(),
            OperatorCommand.SUDO);

    var refused = assertThrows(IllegalStateException.class, () -> hostKey.pin("maindevbox"));

    assertEquals(
        "Couldn't read maindevbox's SSH host key on port 22: no ed25519 host key came back. Check"
            + " the address and firewall, then re-run sudo sail init --main maindevbox.",
        refused.getMessage());
  }

  @Test
  void anotherKeyTypePinnedForMainIsNoEd25519PinSoMainsIsAdded() throws Exception {
    Files.createDirectories(knownHosts().getParent());
    Files.writeString(knownHosts(), "maindevbox ssh-rsa AAAA\n");

    new MainHostKey(new Keyscan(hashed(MAIN), "", 0), knownHosts(), OperatorCommand.SUDO)
        .pin("maindevbox");

    assertEquals("maindevbox ssh-rsa AAAA\n" + hashed(MAIN) + "\n", Files.readString(knownHosts()));
  }

  @Test
  void aRevokedKeyIsRefusedAndNoPinIsWritten() throws Exception {
    Files.createDirectories(knownHosts().getParent());
    var revoked = "@revoked maindevbox " + MAIN.type() + " " + MAIN.blob() + "\n";
    Files.writeString(knownHosts(), revoked);
    var hostKey =
        new MainHostKey(new Keyscan(hashed(MAIN), "", 0), knownHosts(), OperatorCommand.SUDO);

    var refused = assertThrows(IllegalStateException.class, () -> hostKey.pin("maindevbox"));

    assertEquals(
        "maindevbox's SSH host key "
            + MAIN.fingerprint()
            + " is revoked in "
            + knownHosts()
            + ", so ssh would refuse it at every sync. Give main a new host key, or remove the"
            + " revocation if it was a mistake, then re-run sudo sail init --main maindevbox.",
        refused.getMessage());
    assertEquals(revoked, Files.readString(knownHosts()));
  }

  @Test
  void aRevocationOfAnotherKeyIsNoPinSoMainsIsAdded() throws Exception {
    Files.createDirectories(knownHosts().getParent());
    var revoked = "@revoked maindevbox " + IMPOSTOR.type() + " " + IMPOSTOR.blob() + "\n";
    Files.writeString(knownHosts(), revoked);

    new MainHostKey(new Keyscan(hashed(MAIN), "", 0), knownHosts(), OperatorCommand.SUDO)
        .pin("maindevbox");

    assertEquals(revoked + hashed(MAIN) + "\n", Files.readString(knownHosts()));
  }

  @Test
  void aCertificateAuthorityIsNoPinSoMainsIsAdded() throws Exception {
    Files.createDirectories(knownHosts().getParent());
    var authority = "@cert-authority maindevbox " + IMPOSTOR.type() + " " + IMPOSTOR.blob() + "\n";
    Files.writeString(knownHosts(), authority);

    new MainHostKey(new Keyscan(hashed(MAIN), "", 0), knownHosts(), OperatorCommand.SUDO)
        .pin("maindevbox");

    assertEquals(authority + hashed(MAIN) + "\n", Files.readString(knownHosts()));
  }

  private static String mode(Path path) throws IOException {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
  }

  /**
   * {@code ssh-keyscan} answering with {@code stdout}, and {@code ssh-keygen -F} finding every line
   * of the file it names: each test pins one host.
   */
  private static final class Keyscan implements ShellExec {
    private final String stdout;
    private final String stderr;
    private final int exit;
    private final List<List<String>> commands = new ArrayList<>();

    Keyscan(String stdout, String stderr, int exit) {
      this.stdout = stdout;
      this.stderr = stderr;
      this.exit = exit;
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      if (command.getFirst().equals("ssh-keygen")) {
        var file = Path.of(command.get(command.indexOf("-f") + 1));
        return new Result(0, "# Host found: line 1\n" + Files.readString(file), "");
      }
      commands.add(command);
      return new Result(exit, stdout, stderr);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
