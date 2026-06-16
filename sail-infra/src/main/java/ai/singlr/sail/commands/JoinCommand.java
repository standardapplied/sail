/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SyncIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Joins this box to a main devbox as a sync node in one step: generates the sail-managed sync key
 * if absent, points the box's config at main ({@code role=node}, {@code main=<target>}), and prints
 * the public key together with the exact {@code sail fde add} line for main's operator to run.
 *
 * <p>The handshake stays pure SSH keys end to end. The only thing that crosses out of band is a
 * public key — never a secret — so there is no network enrolment surface to secure. Once the
 * operator authorises the key, {@code sail sync} reconciles against main.
 */
@Command(
    name = "join",
    description = "Join this box to a main devbox as a sync node (prints the key to authorise).",
    mixinStandardHelpOptions = true)
public final class JoinCommand implements Runnable {

  @Parameters(
      index = "0",
      paramLabel = "TARGET",
      description = "SSH target of the main devbox, e.g. sail@maindevbox.")
  private String target;

  @Option(
      names = "--handle",
      description = "FDE handle to suggest in the authorise line (default: your local username).")
  private String handle;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var resolvedHandle = handle == null || handle.isBlank() ? defaultHandle() : handle;
    var identity = new SyncIdentity(new ShellExecutor(false));
    var plan = plan(SailPaths.hostConfigPath(), identity, target, resolvedHandle);
    print(plan);
  }

  /**
   * Generates the sync key if needed and points the local host config at {@code target} as a node.
   * Pure of any stdout so it can be exercised directly in tests with a {@code @TempDir} config and
   * a stub identity.
   */
  static Plan plan(Path hostConfig, SyncIdentity identity, String target, String handle)
      throws Exception {
    if (!Files.exists(hostConfig)) {
      throw new IllegalStateException("Server not initialized. Run 'sail host init' first.");
    }
    HostConfigSetCommand.validate("sync-main", target);
    var publicKey = identity.ensure("sail-sync@" + HostInfo.hostname());
    var host = HostYaml.fromMap(YamlUtil.parseFile(hostConfig));
    var updated = HostSyncCommand.configure(host, false, target);
    requireWritable(hostConfig, target);
    YamlUtil.dumpToFile(updated.toMap(), hostConfig);
    return new Plan(target, handle, publicKey);
  }

  private static void requireWritable(Path hostConfig, String target) {
    var probe = Files.exists(hostConfig) ? hostConfig : hostConfig.getParent();
    if (probe != null && !Files.isWritable(probe)) {
      throw new IllegalStateException(
          "Writing " + hostConfig + " needs root. Re-run: sudo sail join " + target);
    }
  }

  static String defaultHandle() {
    var user = System.getProperty("user.name");
    return user == null || user.isBlank() ? "your-handle" : user;
  }

  /** The {@code sail fde add} line main's operator runs to authorise this node. */
  static String authorizeLine(String handle, String publicKey) {
    return "sail fde add " + handle + " --role member --key \"" + publicKey + "\"";
  }

  private void print(Plan plan) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("target", plan.target());
      map.put("suggested_handle", plan.handle());
      map.put("public_key", plan.publicKey());
      map.put("authorize_command", authorizeLine(plan.handle(), plan.publicKey()));
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    var out = System.out;
    out.println(
        Ansi.AUTO.string(
            "  @|bold,green ✓|@ This box is now a @|bold node|@ syncing to @|bold "
                + plan.target()
                + "|@."));
    out.println();
    out.println("  Send this to the operator of " + plan.target() + " — they run it once:");
    out.println();
    out.println(
        Ansi.AUTO.string("    @|cyan " + authorizeLine(plan.handle(), plan.publicKey()) + "|@"));
    out.println();
    out.println(
        Ansi.AUTO.string(
            "  Once they have, finish here with @|bold sail sync|@. Only a public key leaves this"
                + " box — never a secret."));
  }

  /** Result of a join: the target, the suggested handle, and this node's public sync key. */
  record Plan(String target, String handle, String publicKey) {}
}
