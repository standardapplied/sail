/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.WebauthnConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NetworkDetector;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.ssh.SshPublicKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "set",
    description = "Update a host configuration value.",
    mixinStandardHelpOptions = true)
public final class HostConfigSetCommand implements Runnable {

  private static final Set<String> SETTABLE_KEYS =
      Set.of(
          "server-ip",
          "ssh-public-key",
          "webauthn-rp-id",
          "webauthn-rp-name",
          "webauthn-origin",
          "sync-role",
          "sync-main",
          "sync-handle");
  private static final Set<String> WEBAUTHN_KEYS =
      Set.of("webauthn-rp-id", "webauthn-rp-name", "webauthn-origin");
  private static final Pattern RP_ID = Pattern.compile("[a-z0-9]([a-z0-9.-]*[a-z0-9])?");
  private static final Pattern ORIGIN = Pattern.compile("https?://\\S+[^/]");
  private static final Pattern SSH_TARGET = Pattern.compile("([a-z_][a-z0-9_-]*@)?[a-zA-Z0-9.-]+");

  @Parameters(index = "0", description = "Configuration key (e.g. 'server-ip', 'webauthn-rp-id').")
  private String key;

  @Parameters(index = "1", description = "Value to set.")
  private String value;

  @Option(names = "--dry-run", description = "Print what would change without writing.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw new IllegalStateException(
          "Root privileges required. Run with: sudo sail host config set " + key + " " + value);
    }

    if (!SETTABLE_KEYS.contains(key)) {
      throw new IllegalArgumentException(
          "Unknown config key: '" + key + "'. Settable keys: " + String.join(", ", SETTABLE_KEYS));
    }

    validate(key, value);

    if ("ssh-public-key".equals(key)) {
      applyWorkstationKey();
      return;
    }

    var hostYamlPath = SailPaths.hostConfigPath();
    if (!Files.exists(hostYamlPath)) {
      throw new IllegalStateException("Server not initialized. Run 'sail host init' first.");
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(hostYamlPath));

    var updated = applyChange(hostYaml, key, value);

    if (dryRun) {
      System.out.println("[dry-run] Would update " + key + " = " + value + " in " + hostYamlPath);
      return;
    }

    YamlUtil.dumpToFile(updated.toMap(), hostYamlPath);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("key", key);
      map.put("value", value);
      map.put("status", "updated");
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ " + key + " = " + value));
    if (WEBAUTHN_KEYS.contains(key)) {
      System.out.println(
          Ansi.AUTO.string("  @|faint Restart to apply: sudo systemctl restart sail-api|@"));
    }
  }

  private void applyWorkstationKey() throws IOException {
    var dest = SailPaths.workstationPublicKeyPath();
    if (dryRun) {
      System.out.println("[dry-run] Would set ssh-public-key in " + dest);
      return;
    }
    var written = writeWorkstationKey(dest, value);
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("key", key);
      map.put("value", written.line());
      map.put("status", "updated");
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    System.out.println(
        Ansi.AUTO.string("  @|bold,green ✓|@ ssh-public-key set (" + written.fingerprint() + ")"));
  }

  /**
   * Validates and writes the box owner's workstation public key, returning the parsed key.
   * Package-private so the parse/write behaviour is unit-tested without root or a real host.
   */
  static SshPublicKey writeWorkstationKey(Path dest, String value) throws IOException {
    var key = SshPublicKey.parse(value);
    SailPaths.ensureDataDir(dest.getParent());
    Files.writeString(dest, key.line() + "\n");
    Files.setPosixFilePermissions(
        dest,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    return key;
  }

  static void validate(String key, String value) {
    switch (key) {
      case "server-ip" -> {
        if (!NetworkDetector.isValidIpv4(value)) {
          throw new IllegalArgumentException(
              "Invalid IPv4 address: '" + value + "'. Expected format: 192.168.1.100");
        }
      }
      case "ssh-public-key" -> SshPublicKey.parse(value);
      case "webauthn-rp-id" -> {
        if (!RP_ID.matcher(value).matches()) {
          throw new IllegalArgumentException(
              "Invalid RP id: '"
                  + value
                  + "'. Expected a lowercase hostname, e.g. sail.example.dev or localhost.");
        }
      }
      case "webauthn-origin" -> {
        if (!ORIGIN.matcher(value).matches()) {
          throw new IllegalArgumentException(
              "Invalid origin: '"
                  + value
                  + "'. Expected e.g. https://sail.example.dev (no trailing slash — browsers"
                  + " send the origin without one, and the match is exact).");
        }
      }
      case "sync-role" -> {
        if (!SyncConfig.ROLE_MAIN.equals(value) && !SyncConfig.ROLE_NODE.equals(value)) {
          throw new IllegalArgumentException(
              "Invalid sync role: '" + value + "'. Expected 'main' or 'node'.");
        }
      }
      case "sync-main" -> {
        if (!SSH_TARGET.matcher(value).matches()) {
          throw new IllegalArgumentException(
              "Invalid main target: '"
                  + value
                  + "'. Expected an SSH target, e.g. sail@maindevbox.");
        }
      }
      default -> {}
    }
  }

  static HostYaml applyChange(HostYaml current, String key, String value) {
    var webauthn = current.webauthn();
    var sync = current.sync();
    return switch (key) {
      case "server-ip" -> withServerIp(current, value);
      case "webauthn-rp-id" ->
          withWebauthn(current, new WebauthnConfig(value, webauthn.rpName(), webauthn.origins()));
      case "webauthn-rp-name" ->
          withWebauthn(current, new WebauthnConfig(webauthn.rpId(), value, webauthn.origins()));
      case "webauthn-origin" ->
          withWebauthn(
              current, new WebauthnConfig(webauthn.rpId(), webauthn.rpName(), List.of(value)));
      case "sync-role" -> withSync(current, new SyncConfig(value, sync.main(), sync.handle()));
      case "sync-main" -> withSync(current, new SyncConfig(sync.role(), value, sync.handle()));
      case "sync-handle" -> withSync(current, new SyncConfig(sync.role(), sync.main(), value));
      default -> throw new IllegalArgumentException("Unhandled key: " + key);
    };
  }

  private static HostYaml withServerIp(HostYaml current, String serverIp) {
    return new HostYaml(
        current.storageBackend(),
        current.pool(),
        current.poolDisk(),
        current.bridge(),
        current.baseProfile(),
        current.image(),
        current.incusVersion(),
        serverIp,
        current.initializedAt(),
        current.webauthn(),
        current.sync());
  }

  private static HostYaml withWebauthn(HostYaml current, WebauthnConfig webauthn) {
    return new HostYaml(
        current.storageBackend(),
        current.pool(),
        current.poolDisk(),
        current.bridge(),
        current.baseProfile(),
        current.image(),
        current.incusVersion(),
        current.serverIp(),
        current.initializedAt(),
        webauthn,
        current.sync());
  }

  private static HostYaml withSync(HostYaml current, SyncConfig sync) {
    return new HostYaml(
        current.storageBackend(),
        current.pool(),
        current.poolDisk(),
        current.bridge(),
        current.baseProfile(),
        current.image(),
        current.incusVersion(),
        current.serverIp(),
        current.initializedAt(),
        current.webauthn(),
        sync);
  }
}
