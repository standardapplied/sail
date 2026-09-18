/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Designates this box's place in the db-sync star in one step. {@code --as-main} makes it the
 * authority every node reconciles against; {@code --main <target>} makes it a node pointed at main,
 * so {@code sail sync} reaches it without {@code --main}. With no flags it prints the current role.
 * A thin wrapper over the {@code host config set sync-*} keys, kept as one command so onboarding is
 * a single line rather than two.
 */
@Command(
    name = "sync",
    description =
        "Designate this box's sync role: --as-main, or --main <target> to make it a node.",
    mixinStandardHelpOptions = true)
public final class HostSyncCommand implements Runnable {

  @Option(names = "--as-main", description = "Make this box the main devbox (the sync authority).")
  private boolean asMain;

  @Option(
      names = "--main",
      paramLabel = "TARGET",
      description = "SSH target of the main devbox, e.g. sail@maindevbox — makes this box a node.")
  private String mainTarget;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) {
      throw new IllegalStateException("Server not initialized. Run 'sail host init' first.");
    }
    var host = HostYaml.fromMap(YamlUtil.parseFile(path));

    if (!asMain && mainTarget == null) {
      printRole(host.sync());
      return;
    }
    if (asMain && mainTarget != null) {
      throw new IllegalArgumentException("Choose either --as-main or --main <target>, not both.");
    }
    if (!ConsoleHelper.isRoot()) {
      throw new IllegalStateException(
          "Root privileges required. Run with: sudo sail host sync "
              + (asMain ? "--as-main" : "--main " + mainTarget));
    }

    var updated = configure(host, asMain, mainTarget);
    YamlUtil.dumpToFile(updated.toMap(), path);
    printRole(updated.sync());
  }

  /**
   * Applies the chosen role to {@code host}, reusing the validated {@code config set} primitives.
   */
  static HostYaml configure(HostYaml host, boolean asMain, String mainTarget) {
    var identified = withBoxId(host);
    if (asMain) {
      return HostConfigSetCommand.applyChange(identified, "sync-role", SyncConfig.ROLE_MAIN);
    }
    HostConfigSetCommand.validate("sync-main", mainTarget);
    var asNode = HostConfigSetCommand.applyChange(identified, "sync-role", SyncConfig.ROLE_NODE);
    return HostConfigSetCommand.applyChange(asNode, "sync-main", mainTarget);
  }

  /**
   * Mints this box's stable sync identity the first time it takes a role. A box that already has
   * one keeps it: every checkpoint a peer holds is keyed by it.
   */
  static HostYaml withBoxId(HostYaml host) {
    if (host.sync().boxId() != null) {
      return host;
    }
    return new HostYaml(
        host.storageBackend(),
        host.pool(),
        host.poolDisk(),
        host.bridge(),
        host.baseProfile(),
        host.image(),
        host.incusVersion(),
        host.serverIp(),
        host.initializedAt(),
        host.webauthn(),
        host.sync().withBoxId(Ids.newId().toString()));
  }

  private void printRole(SyncConfig sync) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("role", sync.role());
      map.put("main", sync.main());
      map.put("handle", sync.handle());
      map.put("box_id", sync.boxId());
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    if (sync.isMain()) {
      System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ This box is @|bold main|@."));
    } else if (sync.main() != null) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|bold,green ✓|@ This box is a @|bold node|@ syncing to @|bold "
                  + sync.main()
                  + "|@."));
    } else {
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint No sync role set.|@ Run @|bold sail host sync --as-main|@ or"
                  + " @|bold sail host sync --main <target>|@."));
    }
    System.out.println(Ansi.AUTO.string("  " + fdeLine(sync)));
  }

  private static String fdeLine(SyncConfig sync) {
    return sync.handle() != null
        ? "@|faint FDE:|@ @|bold " + sync.handle() + "|@"
        : "@|faint No FDE set — dispatch can't pick this box's specs. Set it:|@ @|bold sail host"
            + " config set sync-handle <handle>|@";
  }
}
