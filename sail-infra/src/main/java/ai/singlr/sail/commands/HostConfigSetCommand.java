/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NetworkDetector;
import ai.singlr.sail.engine.SailPaths;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Set;
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

  private static final Set<String> SETTABLE_KEYS = Set.of("server-ip");

  @Parameters(index = "0", description = "Configuration key (e.g. 'server-ip').")
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

    if ("server-ip".equals(key) && !NetworkDetector.isValidIpv4(value)) {
      throw new IllegalArgumentException(
          "Invalid IPv4 address: '" + value + "'. Expected format: 192.168.1.100");
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

    System.out.println(Ansi.AUTO.string("  @|bold,green \u2713|@ " + key + " = " + value));
  }

  private static HostYaml applyChange(HostYaml current, String key, String value) {
    return switch (key) {
      case "server-ip" ->
          new HostYaml(
              current.storageBackend(),
              current.pool(),
              current.poolDisk(),
              current.bridge(),
              current.baseProfile(),
              current.image(),
              current.incusVersion(),
              value,
              current.initializedAt(),
              current.webauthn());
      default -> throw new IllegalArgumentException("Unhandled key: " + key);
    };
  }
}
