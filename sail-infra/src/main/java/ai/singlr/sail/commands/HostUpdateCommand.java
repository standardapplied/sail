/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "update",
    description = "Update system packages and Incus.",
    mixinStandardHelpOptions = true)
public final class HostUpdateCommand implements Runnable {

  private static final Duration APT_TIMEOUT = Duration.ofMinutes(5);

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var hostYamlPath = SailPaths.hostConfigPath();
    if (!Files.exists(hostYamlPath)) {
      throw new IllegalStateException("Server not initialized. Run 'sail host init' first.");
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(hostYamlPath));

    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw new IllegalStateException("Root privileges required. Run with: sudo sail host update");
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
    }

    var shell = new ShellExecutor(dryRun);

    if (!json) {
      System.out.println(Banner.stepLine(1, 2, "Updating system packages...", Ansi.AUTO));
    }
    shell.exec(List.of("apt-get", "update", "-qq"), null, APT_TIMEOUT);
    shell.exec(List.of("apt-get", "upgrade", "-y", "-qq"), null, APT_TIMEOUT);
    if (!json) {
      System.out.println(Banner.stepDoneLine(1, 2, "packages updated", Ansi.AUTO));
    }

    if (!json) {
      System.out.println(Banner.stepLine(2, 2, "Checking Incus version...", Ansi.AUTO));
    }
    var vResult = shell.exec(List.of("incus", "version"));
    var newVersion = hostYaml.incusVersion();
    if (vResult.ok()) {
      var firstLine = vResult.stdout().lines().findFirst().orElse("");
      var colonIdx = firstLine.indexOf(':');
      newVersion = colonIdx >= 0 ? firstLine.substring(colonIdx + 1).strip() : firstLine.strip();
    }
    if (!json) {
      System.out.println(Banner.stepDoneLine(2, 2, "Incus " + newVersion, Ansi.AUTO));
    }

    var previousVersion = hostYaml.incusVersion();
    if (!newVersion.equals(previousVersion) && !shell.isDryRun()) {
      var updated =
          new HostYaml(
              hostYaml.storageBackend(),
              hostYaml.pool(),
              hostYaml.poolDisk(),
              hostYaml.bridge(),
              hostYaml.baseProfile(),
              hostYaml.image(),
              newVersion,
              hostYaml.serverIp(),
              hostYaml.initializedAt(),
              hostYaml.webauthn());
      YamlUtil.dumpToFile(updated.toMap(), hostYamlPath);
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("status", "ok");
      map.put("incus_version", newVersion);
      map.put("previous_version", previousVersion);
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println();
    System.out.println(Ansi.AUTO.string("  @|bold,green \u2713 Host updated.|@"));
  }
}
