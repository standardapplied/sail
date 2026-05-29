/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.ClientConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Initializes the client config on the engineer's Mac. Creates {@code ~/.sail/config.yaml} with the
 * host connection details. The host can be an IP, hostname, or SSH config alias.
 */
@Command(
    name = "init",
    description = "Initialize sail client — connect to a remote host.",
    mixinStandardHelpOptions = true)
public final class ClientInitCommand implements Runnable {

  @Parameters(index = "0", description = "Host server — IP address, hostname, or SSH config alias.")
  private String host;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var configPath = SailPaths.clientConfigPath();
    writeClientConfig(host, configPath, SailPaths.hostConfigPath());

    Banner.printBranding(System.out, Ansi.AUTO);
    System.out.println();
    System.out.println(
        Ansi.AUTO.string("  @|bold,green \u2713|@ Client configured: " + configPath));
    System.out.println(Ansi.AUTO.string("  @|faint Host:|@ " + host));
    System.out.println();
    System.out.println(Ansi.AUTO.string("  Test with: @|bold sail spec list <project>|@"));
  }

  /**
   * Writes the client config that points this machine at a remote host. Refuses to run on a host
   * server (where {@code host.yaml} exists), because {@code ~/.sail/config.yaml} there holds the
   * host's own server URL and API token — overwriting it with a client pointer would destroy those
   * credentials. Host and client are mutually exclusive roles for this file.
   */
  static void writeClientConfig(String host, Path configPath, Path hostConfigPath)
      throws IOException {
    if (Files.exists(hostConfigPath)) {
      throw new IllegalStateException(
          "This machine is a Sail host ("
              + hostConfigPath
              + " exists).\n"
              + "  'sail init' configures a client to reach a remote host; running it here would"
              + " overwrite\n"
              + "  the host's server credentials. Run it on your laptop instead.");
    }
    Files.createDirectories(configPath.getParent());
    YamlUtil.dumpToFile(new ClientConfig(host).toMap(), configPath);
  }
}
