/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.WebauthnConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NetworkDetector;
import ai.singlr.sail.engine.SailPaths;
import java.nio.file.Files;
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
      Set.of("server-ip", "webauthn-rp-id", "webauthn-rp-name", "webauthn-origin");
  private static final Set<String> WEBAUTHN_KEYS =
      Set.of("webauthn-rp-id", "webauthn-rp-name", "webauthn-origin");
  private static final Pattern RP_ID = Pattern.compile("[a-z0-9]([a-z0-9.-]*[a-z0-9])?");
  private static final Pattern ORIGIN = Pattern.compile("https?://\\S+[^/]");

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

  static void validate(String key, String value) {
    switch (key) {
      case "server-ip" -> {
        if (!NetworkDetector.isValidIpv4(value)) {
          throw new IllegalArgumentException(
              "Invalid IPv4 address: '" + value + "'. Expected format: 192.168.1.100");
        }
      }
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
      default -> {}
    }
  }

  static HostYaml applyChange(HostYaml current, String key, String value) {
    var webauthn = current.webauthn();
    return switch (key) {
      case "server-ip" -> withServerIp(current, value);
      case "webauthn-rp-id" ->
          withWebauthn(current, new WebauthnConfig(value, webauthn.rpName(), webauthn.origins()));
      case "webauthn-rp-name" ->
          withWebauthn(current, new WebauthnConfig(webauthn.rpId(), value, webauthn.origins()));
      case "webauthn-origin" ->
          withWebauthn(
              current, new WebauthnConfig(webauthn.rpId(), webauthn.rpName(), List.of(value)));
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
        current.webauthn());
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
        webauthn);
  }
}
