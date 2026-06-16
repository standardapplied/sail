/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SyncIdentity;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Sqlite;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
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
 *
 * <p>The handle is an FDE identity that lives on <em>main</em>, which this box cannot see, so it is
 * never guessed from the local OS user (which is {@code root} under sudo). It is prompted for, with
 * a sensible default drawn from this box's own FDE roster or {@code $SUDO_USER} — never {@code
 * root}. The {@code sail@} gateway user is implied, so a bare host like {@code maindevbox} works.
 */
@Command(
    name = "join",
    description = "Join this box to a main devbox as a sync node (prints the key to authorise).",
    mixinStandardHelpOptions = true)
public final class JoinCommand implements Runnable {

  @Parameters(
      index = "0",
      paramLabel = "TARGET",
      description = "Main devbox host (e.g. maindevbox or 10.0.0.1); the sail@ user is implied.")
  private String target;

  @Option(
      names = "--handle",
      description = "FDE handle main will authorise (skips the prompt; required with --json).")
  private String handle;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var normalized = normalizeTarget(target);
    nonSailUserWarning(target)
        .ifPresent(warning -> System.err.println(Banner.errorLine(warning, Ansi.AUTO)));
    var resolvedHandle = resolveHandle();
    var identity = new SyncIdentity(new ShellExecutor(false));
    var plan = plan(SailPaths.hostConfigPath(), identity, normalized, resolvedHandle);
    probeReachability(normalized);
    print(plan);
  }

  /**
   * Generates the sync key if needed and points the local host config at {@code target} as a node.
   * Pure of any prompting or stdout so it can be exercised directly in tests with a
   * {@code @TempDir} config and a stub identity.
   */
  static Plan plan(Path hostConfig, SyncIdentity identity, String target, String handle)
      throws Exception {
    if (!Files.exists(hostConfig)) {
      throw new IllegalStateException("Server not initialized. Run 'sail host init' first.");
    }
    HostConfigSetCommand.validate("sync-main", target);
    var publicKey = identity.ensure("sail-sync:" + handle);
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

  /** Prepends the implied {@code sail@} gateway user to a bare host; respects an explicit user. */
  static String normalizeTarget(String raw) {
    var trimmed = raw.strip();
    return trimmed.contains("@") ? trimmed : "sail@" + trimmed;
  }

  /**
   * Warns when the target names a user other than {@code sail}: the forced-command gateway only
   * authorises the {@code sail} account, so {@code someone@host} would be rejected.
   */
  static Optional<String> nonSailUserWarning(String raw) {
    var trimmed = raw.strip();
    var at = trimmed.indexOf('@');
    if (at <= 0) {
      return Optional.empty();
    }
    var user = trimmed.substring(0, at);
    if ("sail".equals(user)) {
      return Optional.empty();
    }
    return Optional.of(
        "The sync gateway only accepts the 'sail' user; '"
            + user
            + "@…' will be rejected. Use sail@"
            + trimmed.substring(at + 1)
            + " (or just the host).");
  }

  /**
   * Bare host for a reachability probe: strips any {@code user@} prefix and {@code :port} suffix.
   */
  static String hostForProbe(String target) {
    var trimmed = target.strip();
    var at = trimmed.indexOf('@');
    var host = at >= 0 ? trimmed.substring(at + 1) : trimmed;
    var colon = host.indexOf(':');
    return colon >= 0 ? host.substring(0, colon) : host;
  }

  /**
   * Default handle to offer, never {@code root}: this box's sole FDE if it has exactly one,
   * otherwise {@code $SUDO_USER}, otherwise the local part of {@code git config user.email}, else
   * {@code null} (caller must ask).
   */
  static String defaultHandle(List<String> nodeFdeHandles, String sudoUser, String gitLocalPart) {
    if (nodeFdeHandles.size() == 1) {
      return nodeFdeHandles.getFirst();
    }
    if (Strings.isNotBlank(sudoUser) && !"root".equals(sudoUser)) {
      return sudoUser;
    }
    if (Strings.isNotBlank(gitLocalPart) && !"root".equals(gitLocalPart)) {
      return gitLocalPart;
    }
    return null;
  }

  private String resolveHandle() {
    var fallback = defaultHandle(nodeFdeHandles(), System.getenv("SUDO_USER"), gitEmailLocalPart());
    return switch (handleChoice(handle, json, System.console() != null, fallback)) {
      case HandleChoice.Resolved resolved -> resolved.handle();
      case HandleChoice.NeedsPrompt prompt -> promptHandle(prompt.fallback());
    };
  }

  /**
   * Decides how to obtain the handle without doing any I/O: an explicit {@code --handle} wins; a
   * non-interactive run ({@code --json} or no console) resolves from the fallback or fails with an
   * actionable message; otherwise the caller must prompt. Pure so every branch is unit-tested.
   */
  static HandleChoice handleChoice(String flag, boolean json, boolean hasConsole, String fallback) {
    if (Strings.isNotBlank(flag)) {
      return new HandleChoice.Resolved(flag.strip());
    }
    if (json) {
      throw new IllegalArgumentException("--json requires --handle (the FDE main will authorise).");
    }
    if (!hasConsole) {
      if (fallback == null) {
        throw new IllegalArgumentException(
            "No terminal to prompt on. Pass --handle <the FDE main will authorise>.");
      }
      return new HandleChoice.Resolved(fallback);
    }
    return new HandleChoice.NeedsPrompt(fallback);
  }

  /** Reconciles a prompt's typed line with its default, rejecting an empty result. */
  static String chosenHandle(String typedLine, String fallback) {
    var chosen = typedLine == null || typedLine.isBlank() ? fallback : typedLine.strip();
    if (Strings.isBlank(chosen)) {
      throw new IllegalArgumentException(
          "A handle is required — it is the FDE main will authorise.");
    }
    return chosen;
  }

  private String promptHandle(String fallback) {
    var suffix = fallback != null ? " @|faint [" + fallback + "]|@" : "";
    System.out.print(
        Ansi.AUTO.string("  Authorise this node as which handle on main?" + suffix + " "));
    return chosenHandle(ConsoleHelper.readLine(), fallback);
  }

  /** How {@link #resolveHandle()} should obtain the handle, decided without I/O. */
  sealed interface HandleChoice {
    /** A handle is already determined (explicit flag, or a fallback in non-interactive mode). */
    record Resolved(String handle) implements HandleChoice {}

    /** The caller must prompt, offering {@code fallback} (may be {@code null}) as the default. */
    record NeedsPrompt(String fallback) implements HandleChoice {}
  }

  private static List<String> nodeFdeHandles() {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      return new FdeStore(db)
          .list().stream()
              .filter(fde -> "active".equals(fde.status()))
              .map(FdeStore.Fde::handle)
              .toList();
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static String gitEmailLocalPart() {
    try {
      var result = new ShellExecutor(false).exec(List.of("git", "config", "--get", "user.email"));
      if (result.ok()) {
        var email = result.stdout().strip();
        var at = email.indexOf('@');
        if (at > 0) {
          return email.substring(0, at);
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private static void probeReachability(String target) {
    var host = hostForProbe(target);
    try (var socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, 22), 5000);
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine(
              "Heads up: couldn't reach "
                  + host
                  + ":22 right now. Check the address and firewall — you can still hand the"
                  + " operator the line below and run 'sail sync' once it's reachable.",
              Ansi.AUTO));
    }
  }

  /** The {@code sail fde add} line main's operator runs to authorise this node. */
  static String authorizeLine(String handle, String publicKey) {
    return "sail fde add " + handle + " --role member --key \"" + publicKey + "\"";
  }

  private void print(Plan plan) {
    System.out.println(render(plan, json));
  }

  /** Renders the join result — machine JSON or the human next-steps block. Pure for testing. */
  static String render(Plan plan, boolean json) {
    var authorize = authorizeLine(plan.handle(), plan.publicKey());
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("target", plan.target());
      map.put("suggested_handle", plan.handle());
      map.put("public_key", plan.publicKey());
      map.put("authorize_command", authorize);
      return YamlUtil.dumpJson(map);
    }
    return Ansi.AUTO.string(
        """

            @|bold,green ✓|@ This box is now a @|bold node|@ syncing to @|bold %s|@.

            Send this to the operator of %s — they run it once:

              @|cyan %s|@

            Once they have, finish here with @|bold sail sync|@. Only a public key leaves \
        this box — never a secret."""
            .formatted(plan.target(), plan.target(), authorize));
  }

  /** Result of a join: the target, the chosen handle, and this node's public sync key. */
  record Plan(String target, String handle, String publicKey) {}
}
