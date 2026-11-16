/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.HandleChange;
import ai.singlr.sail.engine.MainHostKey;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SshSyncChannel;
import ai.singlr.sail.engine.SyncIdentity;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
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

  @Spec private CommandSpec spec;

  @Parameters(
      index = "0",
      paramLabel = "TARGET",
      description = "Main devbox host (e.g. maindevbox or 10.0.0.1); the sail@ user is implied.")
  private String target;

  @Option(
      names = "--handle",
      description =
          "FDE handle main will authorise (default: the one this box acts as; required with"
              + " --json on a first join).")
  private String handle;

  @Option(
      names = "--name",
      description = "Your full name for the team roster (default: git user.name).")
  private String fullName;

  @Option(
      names = "--email",
      description = "Your email for the team roster (default: git user.email).")
  private String email;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(
      names = "--another-main",
      description =
          "Follow a different main from now on, forgetting the one this box follows (without it,"
              + " TARGET is this box's main at a new address).")
  private boolean anotherMain;

  @Option(
      names = "--yes",
      description = "With --another-main, follow the different main without asking.")
  private boolean yes;

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    requireWritable(SailPaths.hostConfigPath());
    var normalized = normalizeTarget(target);
    consent(
        anotherMain,
        SailPaths.hostConfigPath(),
        SailPaths.controlPlaneDb(),
        normalized,
        yes,
        json,
        ConsoleHelper.hasConsole(),
        ConsoleHelper::confirmNo);
    nonSailUserWarning(target)
        .ifPresent(warning -> System.err.println(Banner.errorLine(warning, Ansi.AUTO)));
    var resolvedHandle = resolveHandle();
    var resolvedName = resolveOptional(fullName, gitConfig("user.name"), "Your full name");
    var resolvedEmail = resolveOptional(email, gitConfig("user.email"), "Your email");
    var identity = new SyncIdentity(new ShellExecutor(false));
    var plan =
        plan(
            SailPaths.hostConfigPath(),
            SailPaths.controlPlaneDb(),
            identity,
            normalized,
            resolvedHandle,
            resolvedName,
            resolvedEmail,
            SshSyncChannel::open,
            MainHostKey.here(),
            operator,
            anotherMain);
    System.out.println(render(plan, json, operator));
  }

  /**
   * What following a different main at {@code target} does to a box, configured at {@code
   * hostConfig} with its database at {@code db}, that follows a main now: said before anything
   * changes, and only for {@code --another-main}. Empty for a box that follows no main.
   */
  static Optional<String> repointing(Path hostConfig, Path db, String target) throws Exception {
    if (!Files.exists(hostConfig)) {
      return Optional.empty();
    }
    var sync = HostYaml.fromMap(YamlUtil.parseFile(hostConfig)).sync();
    if (!sync.isNode() || Strings.isBlank(sync.main())) {
      return Optional.empty();
    }
    var own = Objects.toString(sync.handle(), "");
    long described = 0;
    long unpushed = 0;
    if (Files.exists(db)) {
      try (var opened = Sqlite.open(db)) {
        described =
            opened
                .queryOne(
                    "SELECT COUNT(*) FROM fdes WHERE main_id IS NOT NULL AND handle != ?",
                    row -> row.integer(0),
                    own)
                .orElse(0L);
        unpushed =
            SyncedEntities.replicas(opened, sync.boxId(), own).values().stream()
                .mapToLong(replica -> replica.dirtyIds().size())
                .sum();
      }
    }
    return Optional.of(
        "This box will stop following the main it follows at "
            + sync.main()
            + " and follow a different main at "
            + target
            + ". At its first sync with it, everyone the old main described here ("
            + described
            + (described == 1 ? " person" : " people")
            + "), all but this box's own person ("
            + own
            + "), is removed here with the logins they stored; edits made here and not yet pushed ("
            + unpushed
            + ") are offered to the new main; the rows synced from the old main stay on this box.");
  }

  /**
   * Whether this join may go on: a box given a new address for the main it follows ({@code
   * anotherMain} false) is asked nothing; one told to follow another main says what that does and
   * goes on only on the operator's yes ({@link #confirmRepoint}).
   */
  static void consent(
      boolean anotherMain,
      Path hostConfig,
      Path db,
      String target,
      boolean yes,
      boolean json,
      boolean console,
      Predicate<String> ask)
      throws Exception {
    if (!anotherMain) {
      return;
    }
    var said = repointing(hostConfig, db, target);
    if (said.isPresent()) {
      confirmRepoint(said.get(), yes, json, console, ask);
    }
  }

  /**
   * Goes on with a re-point the operator said yes to, by {@code --yes} or when {@code ask}ed at the
   * terminal; refused with what it would do where nobody can answer.
   */
  static void confirmRepoint(
      String said, boolean yes, boolean json, boolean console, Predicate<String> ask) {
    if (yes) {
      System.err.println("  " + said);
      return;
    }
    if (json || !console) {
      throw new CliCommand.Refused(said + " Re-run with --yes to go on.");
    }
    if (!ask.test(said + " Go on?")) {
      throw new CliCommand.Refused("Aborted: this box still follows its main.");
    }
  }

  /**
   * Pins main's SSH host key, generates the sync key if needed and points the local host config at
   * {@code target} as a node. A box that already acts as an FDE re-joins as that one: another
   * handle is refused before anything is read from main ({@link HostSyncCommand#requireHandle}).
   * The pin comes next, so a main whose key cannot be read or differs from the one pinned leaves no
   * half-joined box. A first join's handle stamps every run main has not taken ({@link
   * HandleChange}), in the box's database {@code db}. Main already holds this box's key when it
   * welcomes a session opened with the key the box had before this join, as {@code handle} ({@link
   * SyncOperations#welcomedAs}); a key this join makes is on no main yet, so main is not asked. The
   * line that authorises the key is kept beside the config, for a sync main refuses to repeat
   * ({@link SshSyncChannel#keep}). Pure of any prompting or stdout so it can be exercised directly
   * in tests with a {@code @TempDir} config and a stub identity.
   */
  static Plan plan(
      Path hostConfig,
      Path db,
      SyncIdentity identity,
      String target,
      String handle,
      String name,
      String email,
      SyncOperations.Channels channels,
      MainHostKey mainHostKey,
      OperatorCommand operator)
      throws Exception {
    return plan(
        hostConfig,
        db,
        identity,
        target,
        handle,
        name,
        email,
        channels,
        mainHostKey,
        operator,
        false);
  }

  /**
   * As above; {@code anotherMain} makes {@code target} a different main this box follows from now
   * on, forgetting the one it followed ({@link HandleChange#write}). Without it a box that follows
   * a main takes {@code target} as that main at a new address.
   */
  static Plan plan(
      Path hostConfig,
      Path db,
      SyncIdentity identity,
      String target,
      String handle,
      String name,
      String email,
      SyncOperations.Channels channels,
      MainHostKey mainHostKey,
      OperatorCommand operator,
      boolean anotherMain)
      throws Exception {
    if (!Files.exists(hostConfig)) {
      throw HostInitCommand.notInitialized(operator);
    }
    HostConfigSetCommand.validate("sync-main", target);
    var host = HostYaml.fromMap(YamlUtil.parseFile(hostConfig));
    HostSyncCommand.requireHandle(host.sync().handle(), handle, operator);
    var pinned = mainHostKey.pin(target);
    var keyBefore = identity.exists();
    var updated =
        HostConfigSetCommand.applyChange(
            HostSyncCommand.configure(host, false, target), "sync-handle", handle);
    HandleChange.write(hostConfig, db, host, updated, channels, operator, anotherMain);
    var publicKey = identity.ensure("sail-sync:" + handle);
    var mainHasKey = keyBefore && SyncOperations.welcomedAs(updated.sync(), channels, handle);
    var authorize =
        SshSyncChannel.keep(
            SshSyncChannel.receipt(hostConfig), target, handle, name, email, publicKey, pinned);
    return new Plan(target, handle, name, email, publicKey, pinned, authorize, mainHasKey);
  }

  private void requireWritable(Path hostConfig) {
    if (Files.exists(hostConfig) && !Files.isWritable(hostConfig)) {
      throw RootRequired.refusal("Writing " + hostConfig + " needs root.", spec);
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

  private String resolveHandle() throws IOException {
    var fallback = defaultHandle(nodeFdeHandles(), System.getenv("SUDO_USER"), gitEmailLocalPart());
    var held = heldHandle(SailPaths.hostConfigPath());
    return switch (handleChoice(handle, held, json, System.console() != null, fallback)) {
      case HandleChoice.Resolved resolved -> resolved.handle();
      case HandleChoice.NeedsPrompt prompt -> promptHandle(prompt.fallback());
    };
  }

  /** The handle the box at {@code hostConfig} already acts as; null when it has none. */
  static String heldHandle(Path hostConfig) throws IOException {
    return Files.exists(hostConfig)
        ? HostYaml.fromMap(YamlUtil.parseFile(hostConfig)).sync().handle()
        : null;
  }

  /**
   * Decides how to obtain the handle without doing any I/O: an explicit {@code --handle} wins, then
   * the handle the box already acts as, {@code held}, so a re-join never renames the box; a
   * non-interactive run ({@code --json} or no console) resolves from the fallback or fails with an
   * actionable message; otherwise the caller must prompt. Pure so every branch is unit-tested.
   */
  static HandleChoice handleChoice(
      String flag, String held, boolean json, boolean hasConsole, String fallback) {
    if (Strings.isNotBlank(flag)) {
      return new HandleChoice.Resolved(flag.strip());
    }
    if (Strings.isNotBlank(held)) {
      return new HandleChoice.Resolved(held);
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
          .list().stream().filter(FdeStore.Fde::active).map(FdeStore.Fde::handle).toList();
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static String gitEmailLocalPart() {
    var email = gitConfig("user.email");
    if (email == null) {
      return null;
    }
    var at = email.indexOf('@');
    return at > 0 ? email.substring(0, at) : null;
  }

  private static String gitConfig(String key) {
    try {
      var result = new ShellExecutor(false).exec(List.of("git", "config", "--get", key));
      if (result.ok()) {
        var value = result.stdout().strip();
        return value.isBlank() ? null : value;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  /**
   * Resolves an optional roster field (name/email): an explicit flag wins, a non-interactive run
   * uses the git default, and an interactive run prompts with the git default. May be blank — the
   * FDE works without it, so the printed line just omits the flag.
   */
  private String resolveOptional(String flag, String gitDefault, String label) {
    if (Strings.isNotBlank(flag)) {
      return flag.strip();
    }
    if (json || System.console() == null) {
      return gitDefault;
    }
    var suffix = Strings.isNotBlank(gitDefault) ? " @|faint [" + gitDefault + "]|@" : "";
    System.out.print(Ansi.AUTO.string("  " + label + "?" + suffix + " "));
    var line = ConsoleHelper.readLine();
    return line == null || line.isBlank() ? gitDefault : line.strip();
  }

  /** Renders the join result — machine JSON or the human next-steps block. Pure for testing. */
  static String render(Plan plan, boolean json, OperatorCommand operator) {
    var authorize = plan.authorize();
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("target", plan.target());
      map.put("suggested_handle", plan.handle());
      map.put("suggested_name", plan.name());
      map.put("suggested_email", plan.email());
      map.put("public_key", plan.publicKey());
      map.put("main_host_key", plan.mainHostKey());
      map.put("authorize_command", authorize);
      return YamlUtil.dumpJson(map);
    }
    var sync = operator.sail("sync");
    if (plan.mainHasKey()) {
      return Ansi.AUTO.string(
          """

              @|bold,green ✓|@ This box is now a @|bold node|@ syncing to @|bold %s|@.

              %s

              Main already has this box's key. Finish with @|bold %s|@.\
          """
              .formatted(plan.target(), MainHostKey.pinnedLine(plan.mainHostKey()), sync));
    }
    return Ansi.AUTO.string(
        """

            @|bold,green ✓|@ This box is now a @|bold node|@ syncing to @|bold %s|@.

            %s

            Send this to the operator of %s — they run it once:

              @|cyan %s|@

            Once they have, finish here with @|bold %s|@. Only a public key leaves this box — \
        never a secret."""
            .formatted(
                plan.target(),
                MainHostKey.pinnedLine(plan.mainHostKey()),
                plan.target(),
                authorize,
                sync));
  }

  /**
   * Result of a join: the target, the chosen handle, this node's public sync key, the fingerprint
   * of main's host key it pinned, the line main's operator runs to authorise that key, and whether
   * main already holds it.
   */
  record Plan(
      String target,
      String handle,
      String name,
      String email,
      String publicKey,
      String mainHostKey,
      String authorize,
      boolean mainHasKey) {}
}
