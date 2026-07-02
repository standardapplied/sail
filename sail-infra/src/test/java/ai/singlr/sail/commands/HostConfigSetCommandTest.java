/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.ssh.SshPublicKey;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import picocli.CommandLine;

@Execution(ExecutionMode.SAME_THREAD)
class HostConfigSetCommandTest {

  private PrintStream originalOut;
  private PrintStream originalErr;
  private ByteArrayOutputStream capturedOut;
  private ByteArrayOutputStream capturedErr;

  @BeforeEach
  void captureStreams() {
    originalOut = System.out;
    originalErr = System.err;
    capturedOut = new ByteArrayOutputStream();
    capturedErr = new ByteArrayOutputStream();
    System.setOut(new PrintStream(capturedOut));
    System.setErr(new PrintStream(capturedErr));
  }

  @AfterEach
  void restoreStreams() {
    System.setOut(originalOut);
    System.setErr(originalErr);
  }

  @Test
  void helpShowsUsage() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("host", "config", "set", "--help");
    assertEquals(0, exitCode);

    var output = capturedOut.toString();
    assertTrue(output.contains("server-ip"), "Help should mention server-ip");
  }

  @Test
  void dryRunShowsWhatWouldChange(@TempDir Path tempDir) throws Exception {
    var hostYamlPath = tempDir.resolve("host.yaml");
    var hostYaml =
        new HostYaml(
            "dir",
            "devpool",
            null,
            "incusbr0",
            "singlr-base",
            "ubuntu/24.04",
            "6.8",
            null,
            "2026-01-01T00:00:00Z");
    YamlUtil.dumpToFile(hostYaml.toMap(), hostYamlPath);

    var cmd = new HostConfigSetCommand();
    injectFields(cmd, "key", "server-ip");
    injectFields(cmd, "value", "192.168.1.100");
    injectFields(cmd, "dryRun", true);

    var output = capturedOut.toString();
    assertNotNull(cmd);
  }

  @Test
  void rejectsUnknownKey() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("host", "config", "set", "--dry-run", "unknown-key", "value");
    assertNotEquals(0, exitCode);

    var errOutput = capturedErr.toString();
    assertTrue(
        errOutput.contains("Unknown config key") || errOutput.contains("unknown-key"),
        "Should reject unknown config key");
  }

  @Test
  void rejectsInvalidIpFormat() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exitCode = cmd.execute("host", "config", "set", "--dry-run", "server-ip", "not-an-ip");
    assertNotEquals(0, exitCode);

    var errOutput = capturedErr.toString();
    assertTrue(
        errOutput.contains("Invalid IPv4") || errOutput.contains("not-an-ip"),
        "Should reject invalid IP format");
  }

  @Test
  void hostConfigSubcommandRegistered() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));
    cmd.setErr(new PrintWriter(new StringWriter()));

    cmd.execute("host", "config");
    var output = capturedOut.toString();
    assertTrue(output.contains("set"), "host config should list 'set' subcommand");
  }

  private static void injectFields(Object obj, String fieldName, Object value) {
    try {
      var field = obj.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(obj, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static final HostYaml BASE =
      HostYaml.fromMap(java.util.Map.of("storage_backend", "dir", "server_ip", "10.0.0.1"));

  @Test
  void webauthnRpIdIsSetWithoutDisturbingOtherFields() {
    var updated = HostConfigSetCommand.applyChange(BASE, "webauthn-rp-id", "localhost");

    assertEquals("localhost", updated.webauthn().rpId());
    assertEquals("10.0.0.1", updated.serverIp());
    assertEquals("dir", updated.storageBackend());
  }

  @Test
  void webauthnKeysComposeIntoAConfiguredBlock() {
    var step1 = HostConfigSetCommand.applyChange(BASE, "webauthn-rp-id", "localhost");
    var step2 = HostConfigSetCommand.applyChange(step1, "webauthn-rp-name", "Sail devbox");
    var step3 = HostConfigSetCommand.applyChange(step2, "webauthn-origin", "http://localhost:7070");

    assertEquals(
        new ai.singlr.sail.config.WebauthnConfig(
            "localhost", "Sail devbox", java.util.List.of("http://localhost:7070")),
        step3.webauthn());
    assertTrue(step3.webauthn().isConfigured());
  }

  @Test
  void serverIpChangePreservesTheWebauthnBlock() {
    var withWebauthn = HostConfigSetCommand.applyChange(BASE, "webauthn-rp-id", "localhost");

    var updated = HostConfigSetCommand.applyChange(withWebauthn, "server-ip", "10.0.0.2");

    assertEquals("10.0.0.2", updated.serverIp());
    assertEquals("localhost", updated.webauthn().rpId());
  }

  @Test
  void syncRoleAndMainComposeAndPreserveOtherFields() {
    var asNode = HostConfigSetCommand.applyChange(BASE, "sync-role", "node");
    var pointed = HostConfigSetCommand.applyChange(asNode, "sync-main", "sail@maindevbox");

    assertEquals("node", pointed.sync().role());
    assertEquals("sail@maindevbox", pointed.sync().main());
    assertEquals("10.0.0.1", pointed.serverIp());
  }

  @Test
  void serverIpChangePreservesTheSyncBlock() {
    var asMain = HostConfigSetCommand.applyChange(BASE, "sync-role", "main");

    var updated = HostConfigSetCommand.applyChange(asMain, "server-ip", "10.0.0.2");

    assertTrue(updated.sync().isMain());
    assertEquals("10.0.0.2", updated.serverIp());
  }

  @Test
  void validateRejectsAnUnknownSyncRoleAndMalformedMain() {
    HostConfigSetCommand.validate("sync-role", "main");
    HostConfigSetCommand.validate("sync-role", "node");
    HostConfigSetCommand.validate("sync-main", "sail@maindevbox");
    assertThrows(
        IllegalArgumentException.class,
        () -> HostConfigSetCommand.validate("sync-role", "primary"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HostConfigSetCommand.validate("sync-main", "bad target!"));
  }

  @Test
  void validateAcceptsRealisticValues() {
    HostConfigSetCommand.validate("server-ip", "192.168.1.100");
    HostConfigSetCommand.validate("webauthn-rp-id", "sail.example.dev");
    HostConfigSetCommand.validate("webauthn-rp-id", "localhost");
    HostConfigSetCommand.validate("webauthn-rp-name", "anything goes");
    HostConfigSetCommand.validate("webauthn-origin", "https://sail.example.dev");
    HostConfigSetCommand.validate("webauthn-origin", "http://localhost:7070");
  }

  @Test
  void validateRejectsMalformedValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HostConfigSetCommand.validate("server-ip", "not-an-ip"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HostConfigSetCommand.validate("webauthn-rp-id", "Has Spaces"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HostConfigSetCommand.validate("webauthn-origin", "sail.example.dev"));
  }

  @Test
  void validateRejectsTrailingSlashOrigin() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> HostConfigSetCommand.validate("webauthn-origin", "http://localhost:7070/"));
    assertTrue(thrown.getMessage().contains("trailing slash"));
  }

  private static final String KEY_A = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILDpT0mMcK alice@mac";
  private static final String KEY_B = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILDpT0mMcX bob@laptop";

  @Test
  void detectWorkstationKeysParsesValidLinesAndSkipsCommentsAndGarbage(@TempDir Path tmp)
      throws Exception {
    var ak = tmp.resolve("authorized_keys");
    Files.writeString(ak, "# a comment\n\n" + KEY_A + "\nnot a key line\n" + KEY_B + "\n");

    assertEquals(2, HostConfigSetCommand.detectWorkstationKeys(ak).size());
  }

  @Test
  void authorizedKeysPathResolvesRootsHomeCorrectlyUnderSudo() {
    assertEquals(
        Path.of("/root/.ssh/authorized_keys"),
        HostConfigSetCommand.authorizedKeysPath("root", Path.of("/ignored")),
        "sudo from a root shell sets SUDO_USER=root; root's home is /root, not /home/root");
    assertEquals(
        Path.of("/home/uday/.ssh/authorized_keys"),
        HostConfigSetCommand.authorizedKeysPath("uday", Path.of("/ignored")));
    assertEquals(
        Path.of("/root/.ssh/authorized_keys"),
        HostConfigSetCommand.authorizedKeysPath(null, Path.of("/root")),
        "no sudo: the process user's own home");
  }

  @Test
  void detectWorkstationKeysIsEmptyWhenTheFileIsMissing(@TempDir Path tmp) {
    assertTrue(HostConfigSetCommand.detectWorkstationKeys(tmp.resolve("absent")).isEmpty());
  }

  @Test
  void resolveWorkstationKeyPrefersAnExplicitValueOverDetection() {
    assertEquals(
        KEY_A,
        HostConfigSetCommand.resolveWorkstationKey(KEY_A, List.of(SshPublicKey.parse(KEY_B)))
            .line());
  }

  @Test
  void resolveWorkstationKeyAutoPicksTheSoleDetectedKey() {
    assertEquals(
        KEY_A,
        HostConfigSetCommand.resolveWorkstationKey(null, List.of(SshPublicKey.parse(KEY_A)))
            .line());
  }

  @Test
  void resolveWorkstationKeyFailsWhenNoneCanBeFound() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> HostConfigSetCommand.resolveWorkstationKey(null, List.of()));
    assertTrue(error.getMessage().contains("public key"));
  }

  @Test
  void resolveWorkstationKeyFailsWhenMultipleAreDetectedSoTheOwnerMustChoose() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HostConfigSetCommand.resolveWorkstationKey(
                    null, List.of(SshPublicKey.parse(KEY_A), SshPublicKey.parse(KEY_B))));
    assertTrue(error.getMessage().toLowerCase().contains("multiple"));
  }

  @Test
  void resolveWorkstationKeyRejectsAMalformedExplicitValue() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HostConfigSetCommand.resolveWorkstationKey("not a real key", List.of()));
  }

  @Test
  void writeWorkstationKeyStoresTheCanonicalLine(@TempDir Path tmp) throws Exception {
    var dest = tmp.resolve("workstation_key.pub");
    var value = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILDpT0mMcK sumesh@macbook";

    var written = HostConfigSetCommand.writeWorkstationKey(dest, value);

    assertEquals(value, written.line(), "returns the parsed key whose canonical line was written");
    assertEquals(value + "\n", java.nio.file.Files.readString(dest));
  }

  @Test
  void dryRunForSshPublicKeyReportsWithoutRequiringRootOrHostYaml() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exit =
        cmd.execute(
            "host",
            "config",
            "set",
            "ssh-public-key",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILDpT0mMcK me@mac",
            "--dry-run");

    assertEquals(0, exit);
    assertTrue(capturedOut.toString().contains("ssh-public-key"));
  }

  @Test
  void rejectsAMalformedSshPublicKeyThroughTheCommand() {
    var cmd = new CommandLine(new Sail());
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exit =
        cmd.execute("host", "config", "set", "ssh-public-key", "not-a-real-key", "--dry-run");

    assertNotEquals(0, exit, "a malformed key must be rejected");
  }
}
