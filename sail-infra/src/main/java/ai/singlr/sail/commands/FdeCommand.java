/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.auth.EnrollmentService;
import ai.singlr.sail.auth.EnrollmentTickets;
import ai.singlr.sail.auth.Passkeys;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AuthorizedKeysSync;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerAuthorizedKeys;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorAuthorizedKeys;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import ai.singlr.sail.engine.SyncIdentity;
import ai.singlr.sail.engine.WorkstationIdentity;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.EnrollmentTicketStore;
import ai.singlr.sail.store.FdeBoxes;
import ai.singlr.sail.store.FdePairingStore;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SqliteException;
import ai.singlr.sail.store.SyncScope;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Manages Forward Deployed Engineers — the human principals that own API tokens and are attributed
 * on the specs they act on. Runs against the control-plane database on the host.
 */
@Command(
    name = "fde",
    description = "Manage Forward Deployed Engineers (FDEs).",
    mixinStandardHelpOptions = true,
    subcommands = {
      FdeCommand.Add.class,
      FdeCommand.ListFdes.class,
      FdeCommand.Update.class,
      FdeCommand.Grant.class,
      FdeCommand.Revoke.class,
      FdeCommand.Remove.class,
      FdeCommand.Pair.class,
      FdeCommand.Unpair.class,
      FdeCommand.ReleaseBox.class,
      FdeCommand.Enroll.class,
      FdeCommand.Key.class,
      FdeCommand.Passkey.class
    })
public final class FdeCommand implements Runnable {

  /** The format a connect code names, so Mast can refuse anything else in one sentence. */
  static final String CODE_PREFIX = "sail1.";

  private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");

  /** How long a connect code, and so its token, lives unless the operator says otherwise. */
  private static final Duration PAIRING_TTL = Duration.ofDays(365);

  /**
   * What a pairing touches on this box: the control plane, the operator (the account {@code
   * sail-api} runs as, whose home holds the pty socket Mast forwards to), the SSH host key a code
   * pins, the workstation key containers trust and the main a node syncs with ({@code null} on main
   * or a lone box). A test puts all of it under a temporary directory.
   */
  record Box(
      Path database,
      String operator,
      String handle,
      Path home,
      Path hostKey,
      Path workstationKey,
      ShellExec shell,
      String main) {

    static Box here() {
      var sync = HostSync.config();
      return new Box(
          SailPaths.controlPlaneDb(),
          System.getProperty("user.name"),
          sync.handle(),
          Path.of(System.getProperty("user.home")),
          Path.of("/etc/ssh/ssh_host_ed25519_key.pub"),
          SailPaths.workstationPublicKeyPath(),
          new ShellExecutor(false),
          HostSync.isNode(sync) ? sync.main() : null);
    }

    /** Whether this box is a node, whose roster is a copy of main's. */
    boolean node() {
      return main != null;
    }

    /**
     * Whether this is the SSH gateway's account, which runs an admin's {@code fde} commands. Its
     * {@code authorized_keys} is the forced-command file and must never take a login, and it cannot
     * reach the operator's.
     */
    boolean gateway() {
      return SshIdentityProvisioner.SAIL_USER.equals(operator);
    }
  }

  private final Supplier<Box> box;

  public FdeCommand() {
    this(Box::here);
  }

  FdeCommand(Supplier<Box> box) {
    this.box = box;
  }

  private Path database() {
    return box.get().database();
  }

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  private static OperatorCommand operator() {
    return OperatorCommand.here();
  }

  /**
   * Refuses to touch a pairing from the SSH gateway: its key line is in the operator's login, which
   * only the operator's own account writes.
   */
  private static void requireOperator(Box box, String rendered) {
    if (box.gateway()) {
      throw new IllegalStateException(
          "A pairing is a login of this box's operator, and an SSH-key session runs as the"
              + " gateway's account. Run '"
              + rendered
              + "' on the host, as the account sail-api runs as.");
    }
  }

  /** The comment on every key line a pairing writes, and the only lines revoking it removes. */
  static String pairingComment(String handle) {
    return "sail-mast:" + handle;
  }

  static String alreadyExists(String handle, OperatorCommand operator) {
    return "FDE '"
        + handle
        + "' already exists. Change it with '"
        + operator.sail("fde update " + handle)
        + "'.";
  }

  static String added(FdeStore.Fde fde, boolean scoped, OperatorCommand operator) {
    return "  @|green ✓|@ FDE added: "
        + fde.handle()
        + " ("
        + fde.role()
        + (scoped ? ", scoped: grant projects with '" + operator.sail("fde grant") + "'" : "")
        + ")";
  }

  /**
   * Puts main's own FDE {@code handle} on the roster in {@code db}: added as admin when absent, as
   * the operator who set up the box administers it, and an FDE already there is left as it is, told
   * how to make it admin when it is not. Returns the line that says which.
   */
  static String enrol(Path db, String handle, String name, String email, OperatorCommand operator) {
    try (var sqlite = Sqlite.open(db)) {
      new SchemaManager(sqlite).migrate();
      var fdes = new FdeStore(sqlite);
      return sqlite.transaction(
          () ->
              fdes.byHandle(handle)
                  .map(fde -> onRoster(fde, operator))
                  .orElseGet(() -> added(fdes.add(handle, name, email, "admin"), false, operator)));
    }
  }

  private static String onRoster(FdeStore.Fde fde, OperatorCommand operator) {
    var standing = fde.active() ? fde.role() : fde.role() + ", " + fde.status();
    var line = "  @|faint FDE " + fde.handle() + " is already on the roster (" + standing + ").|@";
    if ("admin".equals(fde.role())) {
      return line;
    }
    return line
        + " Make it admin: @|bold "
        + operator.sail("fde update " + fde.handle() + " --role admin")
        + "|@";
  }

  static String notTrustedYet(String container, String why, OperatorCommand operator) {
    return "  @|yellow ⚠|@ "
        + container
        + " does not trust this key yet ("
        + why
        + "). Make it: '"
        + operator.sail("project apply " + container)
        + "'.";
  }

  static String disabledForPairing(String handle, OperatorCommand operator) {
    return "FDE '"
        + handle
        + "' is disabled on this box, so a code for it would open nothing. Remove it with '"
        + operator.sail("fde rm " + handle)
        + "' and pair again.";
  }

  static String noFdes(OperatorCommand operator) {
    return "  No FDEs. Add one with '" + operator.sail("fde add <handle>") + "'.";
  }

  static String unknownFde(String handle, OperatorCommand operator) {
    return "Unknown FDE '"
        + handle
        + "'. Add it with '"
        + operator.sail("fde add " + handle)
        + "'.";
  }

  static String noKeys(OperatorCommand operator) {
    return "  No SSH keys. Register one with '" + operator.sail("fde key add") + "'.";
  }

  static String neitherHandleNorFingerprint(String target, OperatorCommand operator) {
    return "'"
        + target
        + "' is neither a known FDE handle nor a SHA256: fingerprint. See '"
        + operator.sail("fde key list")
        + "'.";
  }

  static String keyRemoval(String fingerprint, OperatorCommand operator) {
    return "    " + operator.sail("fde key rm " + fingerprint);
  }

  static String noPasskeys(String handle, OperatorCommand operator) {
    return "  No passkeys for '"
        + handle
        + "'. Enroll one with '"
        + operator.sail("fde enroll " + handle)
        + "'.";
  }

  static String noPasskeysLeft(String handle, OperatorCommand operator) {
    return "  @|faint '"
        + handle
        + "' has no passkeys left. Re-enroll with '"
        + operator.sail("fde enroll " + handle)
        + "'.|@";
  }

  /**
   * This box's own FDE acts as an admin here whatever its roster row says ({@code
   * RoleRule.scopeOf}), so a scope on it would hold no one: refused, as an admin's is.
   */
  private static void requireNotThisBoxsOwn(Box box, String handle) {
    if (handle.equals(box.handle())) {
      throw new IllegalArgumentException(
          "FDE '"
              + handle
              + "' is this box's own and acts as an admin here, so it cannot be scoped. Scope a"
              + " member or a viewer.");
    }
  }

  /**
   * Refuses a connect code for anyone but this box's own FDE. A code logs in as the operator, who
   * sees every project, container and secret here; another FDE holds a code for its own box.
   */
  private static void requireOwnFde(Box box, String handle, String host, OperatorCommand operator) {
    var login = "A connect code logs in as " + box.operator() + "@" + host;
    if (box.handle() == null) {
      throw new IllegalStateException(
          login
              + ", which sees every project here, so it is minted only for this box's own FDE,"
              + " and this box names none. If it is "
              + handle
              + ", name it with '"
              + operator.sail("host config set sync-handle " + handle)
              + "' and pair again.");
    }
    if (!handle.equals(box.handle())) {
      throw new IllegalStateException(
          login
              + ", which sees every project here. Pair "
              + handle
              + " on its own box instead: set it up with '"
              + OperatorCommand.SUDO.sail(
                  "init --main " + Objects.requireNonNullElse(box.main(), host))
              + "', then run '"
              + OperatorCommand.SUDO.sail("fde pair " + handle + " --host <its address>")
              + "' there.");
    }
  }

  /**
   * Refuses an FDE roster edit on a node. The roster is main-authoritative and replicates one-way,
   * so an add/update/remove on a node is silently overwritten by the next sync — failing loud with
   * the right place to run it is far better than losing the edit.
   */
  private static void requireMainForRosterEdit(boolean node, String action) {
    if (node) {
      throw new IllegalStateException(rosterEditOnNodeMessage(action));
    }
  }

  static String rosterEditOnNodeMessage(String action) {
    return "The FDE roster is managed on the main devbox. "
        + action
        + " on a node is not propagated — it would be overwritten on the next sync.\n"
        + "  Run this on main; the change replicates to every node automatically.";
  }

  private static void registerKey(
      Sqlite db, FdeStore.Fde fde, String publicKey, OperatorCommand operator) throws Exception {
    var key = SshPublicKey.parse(publicKey);
    try {
      new FdeSshKeyStore(db).add(fde.id(), key);
    } catch (SqliteException e) {
      throw new IllegalArgumentException(
          "That key (" + key.fingerprint() + ") is already registered.");
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|green ✓|@ Registered key for " + fde.handle() + ": " + key.fingerprint()));
    applyKeys(db, operator);
  }

  private static void applyKeys(Sqlite db, OperatorCommand operator) throws Exception {
    System.out.println(Ansi.AUTO.string(keysOutcome(new AuthorizedKeysSync().sync(db), operator)));
  }

  /**
   * The line {@code outcome} prints. Only a non-root caller is told to sync, so its remedy carries
   * {@code sudo} whatever this box's plain {@code sail} would do.
   */
  static String keysOutcome(AuthorizedKeysSync.Outcome outcome, OperatorCommand operator) {
    return switch (outcome) {
      case AuthorizedKeysSync.Synced synced -> "  @|green ✓|@ " + synced.describe();
      case AuthorizedKeysSync.NeedsRoot _ ->
          "  @|faint Run '" + OperatorCommand.SUDO.sail("host keys sync") + "' to apply.|@";
      case AuthorizedKeysSync.NotProvisioned _ ->
          "  @|faint SSH-key login is not provisioned on this host. Run '"
              + operator.sail("host ssh-identity")
              + "' to enable it.|@";
    };
  }

  /** This box's SSH host key, which a connect code pins and a joining node pinned. */
  static SshPublicKey hostKey(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IllegalStateException(
          "This box has no SSH host key at "
              + file
              + ". Generate one with 'sudo ssh-keygen -A', then run this again.");
    }
    return SshPublicKey.parse(Files.readString(file));
  }

  @Command(name = "add", description = "Add an FDE.", mixinStandardHelpOptions = true)
  static final class Add implements Runnable {

    @Parameters(index = "0", description = "Unique handle (e.g. uday).")
    private String handle;

    @Option(names = "--name", description = "Display name.")
    private String displayName;

    @Option(names = "--email", description = "Email address.")
    private String email;

    @Option(
        names = "--role",
        description = "Authorization role: admin, member, or viewer.",
        defaultValue = FdeStore.DEFAULT_ROLE)
    private String role;

    @Option(
        names = "--key",
        description =
            "SSH public key line to register for terminal login, e.g."
                + " \"ssh-ed25519 AAAA... me@host\".")
    private String publicKey;

    @Option(
        names = "--scoped",
        description =
            "Scope the FDE to the projects it is granted (fde grant); it sees nothing until one"
                + " is.")
    private boolean scoped;

    @Option(
        names = "--main-host-key",
        paramLabel = "FINGERPRINT",
        description =
            "The SHA256 fingerprint of this box's SSH host key that the joining node pinned, as"
                + " its join printed it; the add is refused when it is not this box's.")
    private String mainHostKey;

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            var operator = operator();
            var box = command.box.get();
            requireMainForRosterEdit(box.node(), "Adding an FDE");
            if (scoped) {
              requireNotThisBoxsOwn(box, handle);
            }
            var key = publicKey == null ? null : SshPublicKey.parse(publicKey);
            if (mainHostKey != null) {
              requirePinnedHostKey(box, requireFingerprint(mainHostKey));
            }
            try (var db = Sqlite.open(box.database())) {
              var fdeStore = new FdeStore(db);
              if (fdeStore.byHandle(handle).isPresent()) {
                throw new IllegalArgumentException(alreadyExists(handle, operator));
              }
              var fde = db.transaction(() -> add(fdeStore, key));
              System.out.println(Ansi.AUTO.string(added(fde, scoped, operator)));
              if (key != null) {
                System.out.println(
                    Ansi.AUTO.string(
                        "  @|green ✓|@ Registered key for "
                            + fde.handle()
                            + ": "
                            + key.fingerprint()));
                applyKeys(db, operator);
              }
            }
          });
    }

    private static String requireFingerprint(String value) {
      if (!FINGERPRINT.matcher(value).matches()) {
        throw new IllegalArgumentException(
            "--main-host-key takes the SHA256 fingerprint the node's join printed (SHA256: and 43"
                + " base64 characters), not '"
                + value
                + "'.");
      }
      return value;
    }

    /**
     * Refuses a node that pinned another key for this box: something between it and this box
     * answered its join, and the key it would authorise syncs through that.
     */
    private static void requirePinnedHostKey(Box box, String pinned) throws IOException {
      var own = hostKey(box.hostKey()).fingerprint();
      if (!own.equals(pinned)) {
        throw new IllegalStateException(
            "The node pinned "
                + pinned
                + " for this box, but this box's host key is "
                + own
                + ": something between that node and this box answered for it. Do not add this"
                + " key; ask the engineer to re-run '"
                + OperatorCommand.SUDO.sail("init --main <this box>")
                + "' on a network path you trust.");
      }
    }

    /** Adds the FDE scoped from its first moment when asked, never unscoped and then narrowed. */
    private FdeStore.Fde add(FdeStore fdeStore, SshPublicKey key) {
      var fde =
          key == null ? fdeStore.add(handle, displayName, email, role) : addWithKey(fdeStore, key);
      if (scoped) {
        fdeStore.restrict(fde.handle());
      }
      return fde;
    }

    private FdeStore.Fde addWithKey(FdeStore fdeStore, SshPublicKey key) {
      try {
        return fdeStore.addWithKey(handle, displayName, email, role, key);
      } catch (SqliteException e) {
        throw new IllegalArgumentException(
            "That key (" + key.fingerprint() + ") is already registered.");
      }
    }
  }

  @Command(
      name = "update",
      description =
          "Update an FDE's display name, email or role, or change its scope: --scoped narrows it"
              + " to the projects it is granted, --unscoped lets it see every project again.",
      mixinStandardHelpOptions = true)
  static final class Update implements Runnable {

    @Parameters(index = "0", description = "FDE handle.")
    private String handle;

    @Option(names = "--name", description = "New display name.")
    private String displayName;

    @Option(names = "--email", description = "New email address.")
    private String email;

    @Option(names = "--role", description = "New role: admin, member, or viewer.")
    private String role;

    @Option(
        names = "--scoped",
        description =
            "Scope the FDE to the projects it is granted. Its box drops every other project, with"
                + " its container and unpushed work, on its next sync.")
    private boolean scoped;

    @Option(
        names = "--grant",
        split = ",",
        paramLabel = "PROJECT",
        description = "With --scoped: a project the FDE keeps. Repeatable.")
    private List<String> grants = List.of();

    @Option(
        names = "--unscoped",
        description =
            "Let the FDE see every project again; its grants are forgotten. Its box holds every"
                + " project after its next sync.")
    private boolean unscoped;

    @Option(names = "--dry-run", description = "Say what would change without changing it.")
    private boolean dryRun;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            var operator = operator();
            requireOneScopeChange(operator);
            var box = command.box.get();
            requireMainForRosterEdit(box.node(), "Updating an FDE");
            if (displayName == null && email == null && role == null && !scoped && !unscoped) {
              throw new IllegalArgumentException(
                  "Nothing to update. Pass --name, --email, --role, --scoped (with --grant) or"
                      + " --unscoped.");
            }
            NameValidator.requireValidFdeHandle(handle);
            if (scoped) {
              requireNotThisBoxsOwn(box, handle);
              grants.forEach(NameValidator::requireValidProjectName);
            }
            try (var db = Sqlite.open(box.database())) {
              Supplier<Updated> work = () -> update(db, operator);
              var updated = dryRun ? db.rehearse(work) : db.transaction(work);
              System.out.println(updated.describe(handle, dryRun, json, operator));
            }
          });
    }

    private void requireOneScopeChange(OperatorCommand operator) {
      if (scoped && unscoped) {
        throw new IllegalArgumentException(
            "--scoped narrows an FDE and --unscoped widens it; pass one of them.");
      }
      if (!grants.isEmpty() && !scoped) {
        throw new IllegalArgumentException(
            "--grant names a project an FDE keeps as it is narrowed; pass it with --scoped, or"
                + " grant a scoped FDE a project with '"
                + operator.sail("fde grant " + handle + " " + grants.getFirst())
                + "'.");
      }
    }

    /**
     * Widens before the role is set, so an FDE widened and made an admin is never a scoped admin,
     * and narrows after, so an admin demoted and narrowed in one call is never one either.
     */
    private Updated update(Sqlite db, OperatorCommand operator) {
      var fdes = new FdeStore(db);
      if (fdes.byHandle(handle).isEmpty()) {
        throw new IllegalArgumentException("Unknown FDE '" + handle + "'.");
      }
      var before = fdes.scopeOf(handle);
      if (unscoped && !before.unrestricted()) {
        fdes.unrestrict(handle);
      }
      var fde =
          displayName == null && email == null && role == null
              ? null
              : fdes.update(handle, displayName, email, role).orElseThrow();
      if (scoped) {
        narrow(db, fdes, before, operator);
      }
      var after = fdes.scopeOf(handle);
      var catalog = new ProjectStore(db).list().stream().map(ProjectStore.ProjectRow::name);
      return new Updated(
          fde, before, after, catalog.filter(project -> !after.admits(project)).toList());
    }

    private void narrow(Sqlite db, FdeStore fdes, ProjectScope before, OperatorCommand operator) {
      var projects = new ProjectStore(db);
      for (var project : grants) {
        if (projects.findByName(project).isEmpty()) {
          throw new IllegalArgumentException(unknownProject(project, operator));
        }
      }
      if (!before.unrestricted() && before.projects().containsAll(grants)) {
        return;
      }
      if (!grants.isEmpty()) {
        new ChangeLog(db).backfillProjects();
      }
      fdes.restrict(handle);
      grants.forEach(project -> fdes.grant(handle, project));
    }
  }

  /**
   * What an update did: the FDE as it now reads when a field was asked of it, and its scope before
   * and after, with the catalog's projects the scope after no longer admits.
   */
  record Updated(FdeStore.Fde fde, ProjectScope before, ProjectScope after, List<String> dropped) {

    boolean scopeChanged() {
      return !before.equals(after);
    }

    String describe(String handle, boolean dryRun, boolean json, OperatorCommand operator) {
      if (json) {
        var map = new LinkedHashMap<String, Object>();
        map.put("handle", handle);
        map.put("scope", after.unrestricted() ? "all" : "granted");
        map.put("projects", List.copyOf(after.projects()));
        map.put("changed", fde != null || scopeChanged());
        map.put("dry_run", dryRun);
        return YamlUtil.dumpJson(map);
      }
      var lines = new ArrayList<String>();
      if (fde != null) {
        lines.add(
            "  @|green ✓|@ "
                + (dryRun ? "Would update " : "Updated ")
                + fde.handle()
                + " ("
                + fde.role()
                + ")");
      }
      if (scopeChanged()) {
        lines.add("  @|green ✓|@ " + scopeLine(handle, dryRun, operator));
      } else if (fde == null) {
        lines.add(
            "  @|faint "
                + handle
                + (after.unrestricted()
                    ? " already sees every project"
                    : " is already scoped to " + named(after))
                + "; nothing to do.|@");
      }
      return Ansi.AUTO.string(String.join("\n", lines));
    }

    private String scopeLine(String handle, boolean dryRun, OperatorCommand operator) {
      if (after.unrestricted()) {
        return handle
            + (dryRun ? " would see" : " sees")
            + " every project again. Its box holds them after its next sync; provision a"
            + " container with '"
            + OperatorCommand.SUDO.sail("project apply <project>")
            + "'.";
      }
      var to =
          after.projects().isEmpty()
              ? "no project yet: grant with '"
                  + operator.sail("fde grant " + handle + " <project>")
                  + "'"
              : named(after);
      var drops =
          dropped.isEmpty()
              ? " Its box drops no project."
              : " Its box drops "
                  + String.join(", ", dropped)
                  + " with their containers and unpushed work on its next sync.";
      return (dryRun ? "Would scope " : "Scoped ") + handle + " to " + to + "." + drops;
    }

    private static String named(ProjectScope scope) {
      return scope.projects().isEmpty() ? "no project" : String.join(", ", scope.projects());
    }
  }

  private static String unknownProject(String project, OperatorCommand operator) {
    return "Unknown project '"
        + project
        + "'. List the catalog with '"
        + operator.sail("project list")
        + "'.";
  }

  @Command(
      name = "grant",
      description = "Grant a scoped FDE a project: it sees it after its box's next sync.",
      mixinStandardHelpOptions = true)
  static final class Grant implements Runnable {

    @Parameters(index = "0", description = "FDE handle.")
    private String handle;

    @Parameters(index = "1", description = "Project name.")
    private String project;

    @Option(names = "--dry-run", description = "Say what would change without changing it.")
    private boolean dryRun;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            var operator = operator();
            var box = command.box.get();
            requireMainForRosterEdit(box.node(), "Granting a project");
            requireNotThisBoxsOwn(box, handle);
            try (var db = Sqlite.open(box.database())) {
              System.out.println(scopeEdit(db, handle, project, true, dryRun, json, operator));
            }
          });
    }
  }

  @Command(
      name = "revoke",
      description =
          "Take a project back from a scoped FDE: its box drops the project, with its container"
              + " and unpushed work, on its next sync.",
      mixinStandardHelpOptions = true)
  static final class Revoke implements Runnable {

    @Parameters(index = "0", description = "FDE handle.")
    private String handle;

    @Parameters(index = "1", description = "Project name.")
    private String project;

    @Option(names = "--dry-run", description = "Say what would change without changing it.")
    private boolean dryRun;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            var operator = operator();
            var box = command.box.get();
            requireMainForRosterEdit(box.node(), "Revoking a project");
            try (var db = Sqlite.open(box.database())) {
              System.out.println(scopeEdit(db, handle, project, false, dryRun, json, operator));
            }
          });
    }
  }

  /**
   * Grants ({@code grant}) or revokes {@code project} for the scoped FDE {@code handle} and says
   * what that did. A grant names a project in the catalog; a revoke takes back whatever was
   * granted. Either one repeated changes nothing. A grant first fills the project of any head
   * written without one — by the server of the release before, in the moments the upgrade migrated
   * under it — since main pages a scoped node by its heads alone and such a head would never reach
   * it.
   */
  static String scopeEdit(
      Sqlite db,
      String handle,
      String project,
      boolean grant,
      boolean dryRun,
      boolean json,
      OperatorCommand operator) {
    NameValidator.requireValidFdeHandle(handle);
    NameValidator.requireValidProjectName(project);
    var fdes = new FdeStore(db);
    if (grant && new ProjectStore(db).findByName(project).isEmpty()) {
      throw new IllegalArgumentException(unknownProject(project, operator));
    }
    var changed = fdes.grantsOf(handle).admits(project) != grant;
    if (changed && !dryRun) {
      if (grant) {
        new ChangeLog(db).backfillProjects();
        fdes.grant(handle, project);
      } else {
        fdes.revoke(handle, project);
      }
    }
    var projects = List.copyOf(fdes.scopeOf(handle).projects());
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("handle", handle);
      map.put("project", project);
      map.put(grant ? "granted" : "revoked", changed);
      map.put("dry_run", dryRun);
      map.put("projects", projects);
      return YamlUtil.dumpJson(map);
    }
    if (!changed) {
      return Ansi.AUTO.string(
          "  @|faint "
              + handle
              + (grant ? " already holds " : " does not hold ")
              + project
              + "; nothing to do.|@");
    }
    var did =
        grant ? (dryRun ? "Would grant " : "Granted ") : (dryRun ? "Would revoke " : "Revoked ");
    return Ansi.AUTO.string(
        "  @|green ✓|@ "
            + did
            + project
            + (grant ? " to " : " from ")
            + handle
            + (grant
                ? ". Its box holds the project after its next sync."
                : ". Its box drops the project with its container and unpushed work on its next"
                    + " sync."));
  }

  @Command(
      name = "release-box",
      description =
          "Forget the box an FDE syncs from, so the next box to sync as it is recorded instead.",
      mixinStandardHelpOptions = true)
  static final class ReleaseBox implements Runnable {

    @Parameters(index = "0", description = "FDE handle.")
    private String handle;

    @Option(names = "--json", description = "Output in JSON format.")
    private boolean json;

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            if (command.box.get().node()) {
              throw new IllegalStateException(
                  "Main records the box each FDE syncs from, and a node records none. Run '"
                      + OperatorCommand.SUDO.sail("fde release-box " + handle)
                      + "' on main.");
            }
            try (var db = Sqlite.open(command.database())) {
              System.out.println(release(new FdeBoxes(db), handle, json));
            }
          });
    }

    /**
     * Releases {@code handle}'s box and says what that did: the box it syncs from is forgotten, or
     * none was recorded. The old box should be retired first — it is refused once another box has
     * synced as the FDE.
     */
    static String release(FdeBoxes boxes, String handle, boolean json) {
      var box = boxes.boxOf(handle);
      var released = boxes.release(handle);
      if (json) {
        var map = new LinkedHashMap<String, Object>();
        map.put("handle", handle);
        map.put("released", released);
        map.put("box", box.orElse(null));
        return YamlUtil.dumpJson(map);
      }
      return released
          ? Ansi.AUTO.string(
              "  @|green ✓|@ Released box '"
                  + box.orElseThrow()
                  + "' for "
                  + handle
                  + ". The next box to sync as "
                  + handle
                  + " is recorded as its box.")
          : Ansi.AUTO.string("  @|faint No box is recorded for " + handle + "; nothing to do.|@");
    }
  }

  @Command(
      name = "rm",
      description = "Remove an FDE and revoke everything that authenticates as it.",
      mixinStandardHelpOptions = true)
  static final class Remove implements Runnable {

    @Parameters(index = "0", description = "FDE handle.")
    private String handle;

    @Option(names = "--force", description = "Skip confirmation.")
    private boolean force;

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            var operator = operator();
            var box = command.box.get();
            requireMainForRosterEdit(box.node(), "Removing an FDE");
            if (!force) {
              ConsoleHelper.confirmDestructive(
                  "Remove FDE '" + handle + "' and revoke all its credentials?",
                  "Not removed",
                  "--force");
            }
            try (var held = PairingTrust.hold(box.database());
                var db = Sqlite.open(box.database())) {
              var fdeStore = new FdeStore(db);
              var fde =
                  fdeStore
                      .byHandle(handle)
                      .orElseThrow(
                          () -> new IllegalArgumentException("Unknown FDE '" + handle + "'."));
              var pairing = new FdePairingStore(db).find(fde.id());
              if (pairing.isPresent()) {
                requireOperator(box, OperatorCommand.SUDO.sail("fde rm " + handle));
              }
              var hadSshKeys = !new FdeSshKeyStore(db).listForFde(fde.id()).isEmpty();
              fdeStore.requireRemovable(fde);
              var revoked =
                  box.gateway()
                      ? Optional.<String>empty()
                      : PairingRevocation.revoke(box, db, fde.handle(), pairing);
              fdeStore.remove(fde.id());
              System.out.println(Ansi.AUTO.string("  @|green ✓|@ FDE removed: " + fde.handle()));
              System.out.println(
                  Ansi.AUTO.string(
                      "  @|faint Owned tokens, SSH keys, sessions, passkeys, and enrollment"
                          + " tickets are revoked.|@"));
              revoked.ifPresent(
                  removed ->
                      System.out.println(
                          Ansi.AUTO.string(
                              "  @|green ✓|@ Its Mast pairing is revoked: " + removed)));
              if (hadSshKeys) {
                applyKeys(db, operator);
              }
            }
          });
    }
  }

  @Command(
      name = "pair",
      description =
          "Mint the connect code this box's own FDE pastes into Mast. The code logs in as the"
              + " account sail-api runs as, which sees every project here, so it is minted for no"
              + " other FDE: onboard another on its own box (init --main <main>) and pair it"
              + " there. Run it as the account sail-api runs as.",
      mixinStandardHelpOptions = true)
  static final class Pair implements Runnable {

    @Parameters(
        index = "0",
        description = "This box's own FDE handle (its sync-handle); added on main when absent.")
    private String handle;

    @Option(
        names = "--host",
        description = "The address Mast reaches this box at: an IP or a DNS name.")
    private String host;

    @Option(names = "--email", description = "The FDE's email address.")
    private String email;

    @Option(names = "--name", description = "The FDE's display name.")
    private String displayName;

    @Option(
        names = "--role",
        description =
            "The FDE's role, and the code's: admin, member, or viewer (default member for a new"
                + " FDE).")
    private String role;

    @Option(names = "--ttl-days", description = "Lifetime of the code in days (default 365).")
    private Integer ttlDays;

    @Option(names = "--no-expiry", description = "The code never expires.")
    private boolean noExpiry;

    @Option(
        names = "--as-workstation-key",
        description =
            "Replace the workstation key this box's containers trust with this pairing's key.")
    private boolean asWorkstationKey;

    @ParentCommand private FdeCommand command;

    private record MintedKey(SshPublicKey publicKey, String privateKey) {}

    @Override
    public void run() {
      CliCommand.run(this::execute);
    }

    private void execute() throws Exception {
      var operator = operator();
      var box = command.box.get();
      validate();
      requireOwnFde(box, handle, host, operator);
      if (box.node() && (displayName != null || email != null || role != null)) {
        throw new IllegalStateException(
            rosterEditOnNodeMessage("Changing an FDE's name, email or role"));
      }
      requireOperator(box, OperatorCommand.SUDO.sail("fde pair " + handle));
      var ttl = ServerTokenCommand.Create.resolveTtl(noExpiry, ttlDays, PAIRING_TTL);
      try (var held = PairingTrust.hold(box.database());
          var db = Sqlite.open(box.database())) {
        var fdes = new FdeStore(db);
        var existing = fdes.byHandle(handle);
        if (box.node() && existing.isEmpty()) {
          throw new IllegalStateException(
              "This node holds no roster row for '"
                  + handle
                  + "' yet, and main's roster names the FDEs here. Run '"
                  + operator.sail("sync")
                  + "', then pair again.");
        }
        var address = address(box, existing, operator);
        var hostKey = hostKey(box.hostKey());
        var pinned = hostKey.type() + " " + hostKey.blob();
        var replaced =
            replacedWorkstationKey(
                box, existing.flatMap(fde -> new FdePairingStore(db).find(fde.id())), operator);
        var untrusted = replaced.map(old -> untrustReplaced(box, old)).orElse(List.of());
        var key = mintKey(box.shell());
        var fde = box.node() ? existing.orElseThrow() : rosterRow(fdes, existing);
        if (existing.isEmpty()) {
          System.out.println(
              Ansi.AUTO.string(
                  "  @|green ✓|@ FDE added: " + fde.handle() + " (" + fde.role() + ")"));
        }
        PairingRevocation.revoke(box, db, handle)
            .ifPresent(
                removed ->
                    System.out.println(
                        Ansi.AUTO.string(
                            "  @|green ✓|@ Unpaired " + handle + " first: " + removed)));
        var token = mintToken(db, fde, key.publicKey(), ttl);
        new OperatorAuthorizedKeys(box.home())
            .append(
                key.publicKey(),
                pairingComment(handle),
                Optional.ofNullable(token.expiresAt()).map(Instant::parse));
        HostConfigSetCommand.writeWorkstationKey(box.workstationKey(), key.publicKey().line());
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Paired "
                    + handle
                    + ": Mast logs in as "
                    + box.operator()
                    + "@"
                    + host
                    + ". "
                    + (token.expiresAt() == null
                        ? "The code never expires."
                        : "The code expires " + token.expiresAt() + ".")));
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Workstation key "
                    + replaced.map(old -> "replaced: it was " + describe(old)).orElse("set")
                    + "."
                    + (untrusted.isEmpty()
                        ? ""
                        : " Containers of "
                            + String.join(", ", untrusted)
                            + " no longer trust it.")));
        System.out.println();
        System.out.println("  Paste this code into Mast. It is a secret, and it is shown once:");
        System.out.println();
        System.out.println(code(box, address, pinned, key.privateKey(), token.token()));
        var refusing = trustInContainers(box, db, key.publicKey());
        if (!refusing.isEmpty()) {
          System.out.println();
          refusing.forEach(
              container ->
                  System.out.println(
                      Ansi.AUTO.string(
                          notTrustedYet(container.container(), container.reason(), operator))));
        }
      }
    }

    /**
     * Takes the workstation key this pairing replaces out of every container, as unpair takes a
     * pairing's, before anything of the pairing is written: one that cannot be taken out refuses
     * the pairing, and running it again tries again.
     */
    private static List<String> untrustReplaced(Box box, SshPublicKey replaced) {
      try {
        return new ContainerAuthorizedKeys(box.shell()).remove(replaced);
      } catch (IOException | TimeoutException failure) {
        throw new IllegalStateException(
            "Could not take the workstation key "
                + describe(replaced)
                + " out of this box's containers ("
                + failure.getMessage().strip()
                + "), so nothing is paired: check that 'incus list' answers, then run the command"
                + " again.",
            failure);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while taking the workstation key out of this box's containers.",
            interrupted);
      }
    }

    /**
     * Writes {@code key} into every container of this box once its code is shown, and returns those
     * that do not trust it. A box whose containers cannot be listed names each project instead.
     */
    private static List<ContainerAuthorizedKeys.Untrusted> trustInContainers(
        Box box, Sqlite db, SshPublicKey key) throws InterruptedException {
      try {
        return new ContainerAuthorizedKeys(box.shell()).add(key);
      } catch (IOException | TimeoutException failure) {
        return new ProjectStore(db)
            .list().stream()
                .map(
                    project ->
                        new ContainerAuthorizedKeys.Untrusted(
                            project.name(), failure.getMessage().strip()))
                .toList();
      }
    }

    private void validate() {
      NameValidator.requireValidFdeHandle(handle);
      if (host == null) {
        throw new IllegalArgumentException(
            "Pass --host <ip-or-dns>: a box cannot know the address Mast reaches it at.");
      }
      NameValidator.requireValidHost(host);
      if (email != null) {
        NameValidator.requireValidEmail(email);
      }
      if (displayName != null) {
        NameValidator.requireValidDisplayName(displayName);
      }
    }

    /**
     * The FDE main's roster holds for the code once this pairing has added it or applied the name,
     * email and role passed.
     */
    private FdeStore.Fde rosterRow(FdeStore fdes, Optional<FdeStore.Fde> existing) {
      return existing.isPresent()
          ? fdes.update(handle, displayName, email, role).orElseThrow()
          : fdes.add(
              handle, displayName, email, Objects.requireNonNullElse(role, FdeStore.DEFAULT_ROLE));
    }

    /** The email the code names: the one passed, else the one this box holds for the FDE. */
    private String address(Box box, Optional<FdeStore.Fde> existing, OperatorCommand operator) {
      if (existing.isPresent() && !existing.get().active()) {
        throw new IllegalStateException(disabledForPairing(handle, operator));
      }
      var address = email != null ? email : existing.map(FdeStore.Fde::email).orElse(null);
      if (address == null) {
        throw new IllegalArgumentException(
            "This box holds no email for '"
                + handle
                + "', and a connect code names one. "
                + (box.node()
                    ? "Set it on main with '"
                        + OperatorCommand.SUDO.sail("fde update " + handle + " --email <email>")
                        + "', run '"
                        + operator.sail("sync")
                        + "' here, then pair again."
                    : "Pass --email <email>."));
      }
      return address;
    }

    /**
     * The registered workstation key this pairing takes over from someone else, if any. The key of
     * the FDE's own earlier pairing is replaced without asking; another's only with {@code
     * --as-workstation-key}, since containers trust one key and its owner would lose them. A file
     * that holds no key is nobody's, so it is replaced without asking too.
     */
    private Optional<SshPublicKey> replacedWorkstationKey(
        Box box, Optional<FdePairingStore.Pairing> pairing, OperatorCommand operator) {
      var own = pairing.map(held -> SshPublicKey.parse(held.publicKey()).fingerprint());
      var replaced =
          heldKey(box.workstationKey(), operator)
              .filter(key -> !own.equals(Optional.of(key.fingerprint())));
      if (replaced.isPresent() && !asWorkstationKey) {
        throw new IllegalStateException(
            "This box's containers trust the workstation key "
                + describe(replaced.get())
                + ", and they trust one key. Pass --as-workstation-key to replace it with "
                + handle
                + "'s.");
      }
      return replaced;
    }

    /**
     * The key the workstation key file holds, or none when it holds nothing a pairing would keep; a
     * file that cannot be read may hold one, so that stays refused.
     */
    private static Optional<SshPublicKey> heldKey(Path file, OperatorCommand operator) {
      try {
        return WorkstationIdentity.registeredAt(file);
      } catch (IllegalStateException refused) {
        if (refused.getCause() instanceof IllegalArgumentException) {
          return Optional.empty();
        }
        throw refused;
      }
    }

    private static String describe(SshPublicKey key) {
      return key.comment() == null
          ? key.fingerprint()
          : key.fingerprint() + " (" + key.comment() + ")";
    }

    /**
     * Makes the pair in a directory that is gone before anything else is written: the private key
     * leaves this box in the code and stays nowhere on it.
     */
    private MintedKey mintKey(ShellExec shell) throws Exception {
      var directory = Files.createTempDirectory("sail-pair");
      var privateKey = directory.resolve("key");
      var publicKey = directory.resolve("key.pub");
      try {
        var line = new SyncIdentity(shell, privateKey, publicKey).ensure(pairingComment(handle));
        return new MintedKey(SshPublicKey.parse(line), Files.readString(privateKey));
      } finally {
        Files.deleteIfExists(privateKey);
        Files.deleteIfExists(publicKey);
        Files.delete(directory);
      }
    }

    /** Mints the pairing's token in place of any of its name, and records the pairing. */
    private TokenStore.CreatedToken mintToken(
        Sqlite db, FdeStore.Fde fde, SshPublicKey publicKey, Duration ttl) {
      var name = "mast-" + handle;
      return db.transaction(
          () -> {
            var tokens = new TokenStore(db);
            tokens.revoke(name);
            var token = tokens.create(name, fde.role(), fde.id(), ttl);
            new FdePairingStore(db).put(fde.id(), publicKey.line(), name);
            return token;
          });
    }

    private String code(Box box, String address, String hostKey, String privateKey, String token) {
      var fields = new LinkedHashMap<String, Object>();
      fields.put("v", 1);
      fields.put("handle", handle);
      fields.put("email", address);
      fields.put("host", host);
      fields.put("port", 22);
      fields.put("user", box.operator());
      fields.put("host_key", hostKey);
      fields.put("key", privateKey);
      fields.put("token", token);
      fields.put("server", "http://127.0.0.1:" + SshTunnel.PORT);
      return CODE_PREFIX
          + Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(YamlUtil.dumpJson(fields).getBytes(StandardCharsets.UTF_8));
    }
  }

  @Command(
      name = "unpair",
      description = "Revoke an FDE's connect code: its key lines and its token.",
      mixinStandardHelpOptions = true)
  static final class Unpair implements Runnable {

    @Parameters(index = "0", description = "FDE handle.")
    private String handle;

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            NameValidator.requireValidFdeHandle(handle);
            var box = command.box.get();
            requireOperator(box, OperatorCommand.SUDO.sail("fde unpair " + handle));
            try (var held = PairingTrust.hold(box.database());
                var db = Sqlite.open(box.database())) {
              System.out.println(
                  Ansi.AUTO.string(
                      PairingRevocation.revoke(box, db, handle)
                          .map(removed -> "  @|green ✓|@ Unpaired " + handle + ": " + removed)
                          .orElse(
                              "  @|faint "
                                  + handle
                                  + " has no pairing on this box; nothing to do.|@")));
            }
          });
    }
  }

  @Command(name = "list", description = "List FDEs.", mixinStandardHelpOptions = true)
  static final class ListFdes implements Runnable {

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            var operator = operator();
            var box = command.box.get();
            try (var db = Sqlite.open(box.database())) {
              var fdeStore = new FdeStore(db);
              var fdes = fdeStore.list();
              if (fdes.isEmpty()) {
                System.out.println(noFdes(operator));
                return;
              }
              Banner.printFdeTable(fdes, scopes(box, db, fdeStore), System.out, Ansi.AUTO);
            }
          });
    }
  }

  /**
   * The scope each FDE's row shows: main holds every FDE's own; a node holds only its own FDE's,
   * the one main last named for it ({@link SyncScope}), since main syncs no FDE's scope to a node.
   */
  static Function<String, Optional<ProjectScope>> scopes(Box box, Sqlite db, FdeStore fdeStore) {
    if (!box.node()) {
      return handle -> Optional.of(fdeStore.scopeOf(handle));
    }
    var own = new SyncScope(db).held();
    return handle -> handle.equals(box.handle()) ? Optional.of(own) : Optional.empty();
  }

  @Command(
      name = "enroll",
      description = "Mint a one-time passkey enrollment ticket for an FDE.",
      mixinStandardHelpOptions = true)
  static final class Enroll implements Runnable {

    @Parameters(index = "0", description = "FDE handle to enroll (must already exist).")
    private String handle;

    @Option(names = "--json", description = "Print the ticket as JSON.")
    private boolean json;

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            try (var db = Sqlite.open(command.database())) {
              var ticket =
                  new EnrollmentService(new EnrollmentTicketStore(db), new FdeStore(db))
                      .issue(handle);
              if (json) {
                System.out.println(YamlUtil.dumpJson(ticketJson(ticket, enrollOrigin())));
                return;
              }
              System.out.println(
                  Ansi.AUTO.string(
                      "  @|green ✓|@ Enrollment ticket for "
                          + ticket.fdeHandle()
                          + " (expires "
                          + ticket.expiresAt()
                          + "):"));
              System.out.println("    " + ticket.ticket());
              System.out.println();
              var origin = enrollOrigin();
              if (origin != null) {
                System.out.println("  Open in a browser to enroll a passkey:");
                System.out.println(
                    Ansi.AUTO.string(
                        "    @|cyan " + origin + "/enroll?ticket=" + ticket.ticket() + "|@"));
              } else {
                System.out.println(
                    Ansi.AUTO.string(
                        "  @|faint No webauthn origin configured; browse to"
                            + " <origin>/enroll?ticket=<ticket>.|@"));
              }
            }
          });
    }

    static Map<String, Object> ticketJson(EnrollmentTickets.Ticket ticket, String origin) {
      var map = new LinkedHashMap<String, Object>();
      map.put("ticket", ticket.ticket());
      map.put("fde", ticket.fdeHandle());
      map.put("expires_at", ticket.expiresAt());
      if (origin != null) {
        map.put("enroll_url", origin + "/enroll?ticket=" + ticket.ticket());
      }
      return map;
    }

    private static String enrollOrigin() throws Exception {
      var path = SailPaths.hostConfigPath();
      if (!Files.exists(path)) {
        return null;
      }
      var webauthn = HostYaml.fromMap(YamlUtil.parseFile(path)).webauthn();
      return webauthn.isConfigured() ? webauthn.origins().getFirst() : null;
    }
  }

  @Command(
      name = "key",
      description = "Manage the SSH keys an FDE authenticates the terminal with.",
      mixinStandardHelpOptions = true,
      subcommands = {Key.Add.class, Key.ListKeys.class, Key.Remove.class})
  static final class Key implements Runnable {

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      new picocli.CommandLine(this).usage(System.out);
    }

    private Path database() {
      return command.database();
    }

    @Command(
        name = "add",
        description = "Register an SSH public key for an FDE.",
        mixinStandardHelpOptions = true)
    static final class Add implements Runnable {

      @Parameters(index = "0", description = "FDE handle (must already exist).")
      private String handle;

      @Parameters(
          index = "1",
          description = "SSH public key line, e.g. \"ssh-ed25519 AAAA... me@host\".")
      private String publicKey;

      @ParentCommand private Key key;

      @Override
      public void run() {
        CliCommand.run(
            () -> {
              var operator = operator();
              try (var db = Sqlite.open(key.database())) {
                var fde =
                    new FdeStore(db)
                        .byHandle(handle)
                        .orElseThrow(
                            () -> new IllegalArgumentException(unknownFde(handle, operator)));
                registerKey(db, fde, publicKey, operator);
              }
            });
      }
    }

    @Command(
        name = "list",
        description = "List registered SSH keys.",
        mixinStandardHelpOptions = true)
    static final class ListKeys implements Runnable {

      @Parameters(index = "0", arity = "0..1", description = "Optional FDE handle to filter by.")
      private String handle;

      @ParentCommand private Key key;

      @Override
      public void run() {
        CliCommand.run(
            () -> {
              var operator = operator();
              try (var db = Sqlite.open(key.database())) {
                var keyStore = new FdeSshKeyStore(db);
                var keys =
                    handle == null
                        ? keyStore.list()
                        : new FdeStore(db)
                            .byHandle(handle)
                            .map(fde -> keyStore.listForFde(fde.id()))
                            .orElseThrow(
                                () ->
                                    new IllegalArgumentException("Unknown FDE '" + handle + "'."));
                if (keys.isEmpty()) {
                  System.out.println(noKeys(operator));
                  return;
                }
                Banner.printFdeKeyTable(keys, System.out, Ansi.AUTO);
              }
            });
      }
    }

    @Command(
        name = "rm",
        description = "Remove a registered SSH key by FDE handle or SHA256: fingerprint.",
        mixinStandardHelpOptions = true)
    static final class Remove implements Runnable {

      @Parameters(index = "0", description = "FDE handle, or a key's SHA256: fingerprint.")
      private String target;

      @ParentCommand private Key key;

      @Override
      public void run() {
        CliCommand.run(
            () -> {
              var operator = operator();
              try (var db = Sqlite.open(key.database())) {
                var keyStore = new FdeSshKeyStore(db);
                var fingerprint =
                    target.startsWith("SHA256:")
                        ? Optional.of(target)
                        : resolveByHandle(db, keyStore, operator);
                if (fingerprint.isEmpty()) {
                  return;
                }
                if (keyStore.remove(fingerprint.get())) {
                  System.out.println(
                      Ansi.AUTO.string("  @|green ✓|@ Removed key " + fingerprint.get()));
                  applyKeys(db, operator);
                } else {
                  System.out.println(
                      Ansi.AUTO.string(
                          "  @|yellow ⚠|@ No key with fingerprint " + fingerprint.get()));
                }
              }
            });
      }

      /**
       * Resolves a handle to the single key it owns, or empty after printing guidance — no keys, or
       * several (removing all on an ambiguous request would be a surprise revocation).
       */
      private Optional<String> resolveByHandle(
          Sqlite db, FdeSshKeyStore keyStore, OperatorCommand operator) {
        var fde =
            new FdeStore(db)
                .byHandle(target)
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            neitherHandleNorFingerprint(target, operator)));
        var keys = keyStore.listForFde(fde.id());
        if (keys.isEmpty()) {
          System.out.println(
              Ansi.AUTO.string("  @|yellow ⚠|@ No SSH keys registered for '" + target + "'."));
          return Optional.empty();
        }
        if (keys.size() > 1) {
          System.out.println("  '" + target + "' has " + keys.size() + " keys; specify one:");
          for (var key : keys) {
            System.out.println(keyRemoval(key.fingerprint(), operator));
          }
          return Optional.empty();
        }
        return Optional.of(keys.getFirst().fingerprint());
      }
    }
  }

  @Command(
      name = "passkey",
      description = "Manage the passkeys an FDE signs in to the web door with.",
      mixinStandardHelpOptions = true,
      subcommands = {Passkey.ListPasskeys.class, Passkey.Remove.class})
  static final class Passkey implements Runnable {

    @ParentCommand private FdeCommand command;

    @Override
    public void run() {
      new picocli.CommandLine(this).usage(System.out);
    }

    private Path database() {
      return command.database();
    }

    private static FdeStore.Fde requireFde(Sqlite db, String handle) {
      return new FdeStore(db)
          .byHandle(handle)
          .orElseThrow(() -> new IllegalArgumentException("Unknown FDE '" + handle + "'."));
    }

    static String noMatchMessage(
        String handle,
        String prefix,
        List<WebauthnCredentialStore.Credential> credentials,
        OperatorCommand operator) {
      if (credentials.isEmpty()) {
        return "'"
            + handle
            + "' has no passkeys. Enroll one with '"
            + operator.sail("fde enroll " + handle)
            + "'.";
      }
      return "No passkey of '"
          + handle
          + "' matches '"
          + prefix
          + "'. Registered passkeys:\n"
          + candidateLines(credentials);
    }

    static String ambiguousMessage(
        String handle, String prefix, List<WebauthnCredentialStore.Credential> candidates) {
      return "'"
          + prefix
          + "' matches "
          + candidates.size()
          + " passkeys of '"
          + handle
          + "'; use a longer prefix:\n"
          + candidateLines(candidates);
    }

    private static String candidateLines(List<WebauthnCredentialStore.Credential> credentials) {
      return credentials.stream()
          .map(
              credential ->
                  "    "
                      + Passkeys.encodeId(credential.credentialId())
                      + "  "
                      + Passkeys.displayLabel(credential))
          .collect(Collectors.joining("\n"));
    }

    @Command(
        name = "list",
        description = "List an FDE's registered passkeys.",
        mixinStandardHelpOptions = true)
    static final class ListPasskeys implements Runnable {

      @Parameters(index = "0", description = "FDE handle.")
      private String handle;

      @ParentCommand private Passkey passkey;

      @Override
      public void run() {
        CliCommand.run(
            () -> {
              var operator = operator();
              try (var db = Sqlite.open(passkey.database())) {
                var fde = requireFde(db, handle);
                var credentials = new WebauthnCredentialStore(db).listForFde(fde.id());
                if (credentials.isEmpty()) {
                  System.out.println(noPasskeys(handle, operator));
                  return;
                }
                Banner.printFdePasskeyTable(credentials, handle, System.out, Ansi.AUTO);
              }
            });
      }
    }

    @Command(
        name = "rm",
        description =
            "Revoke one of an FDE's passkeys by credential id prefix. Sessions it minted stay"
                + " valid unless --revoke-sessions is passed.",
        mixinStandardHelpOptions = true)
    static final class Remove implements Runnable {

      @Parameters(index = "0", description = "FDE handle.")
      private String handle;

      @Option(
          names = "--credential",
          required = true,
          description = "Credential id or an unambiguous prefix; see fde passkey list <handle>.")
      private String credential;

      @Option(
          names = "--revoke-sessions",
          description = "Also end the FDE's active web sessions (the lost-device case).")
      private boolean revokeSessions;

      @ParentCommand private Passkey passkey;

      @Override
      public void run() {
        CliCommand.run(
            () -> {
              var operator = operator();
              try (var db = Sqlite.open(passkey.database())) {
                var fde = requireFde(db, handle);
                var store = new WebauthnCredentialStore(db);
                var credentials = store.listForFde(fde.id());
                var match =
                    switch (Passkeys.resolveByPrefix(credentials, credential)) {
                      case Passkeys.Match m -> m.credential();
                      case Passkeys.NotFound _ ->
                          throw new IllegalArgumentException(
                              noMatchMessage(handle, credential, credentials, operator));
                      case Passkeys.Ambiguous a ->
                          throw new IllegalArgumentException(
                              ambiguousMessage(handle, credential, a.candidates()));
                    };
                store.delete(match.credentialId());
                System.out.println(
                    Ansi.AUTO.string(
                        "  @|green ✓|@ Revoked passkey '"
                            + Passkeys.displayLabel(match)
                            + "' ("
                            + Passkeys.shortId(match.credentialId())
                            + ") of "
                            + handle));
                if (revokeSessions) {
                  var revoked = new AuthSessionStore(db).revokeForFde(fde.id());
                  System.out.println(
                      Ansi.AUTO.string(
                          "  @|green ✓|@ Revoked "
                              + revoked
                              + " active web session(s) of "
                              + handle));
                } else {
                  System.out.println(
                      Ansi.AUTO.string(
                          "  @|faint Sessions this passkey minted stay valid until they expire;"
                              + " pass --revoke-sessions to end them now.|@"));
                }
                if (credentials.size() == 1) {
                  System.out.println(Ansi.AUTO.string(noPasskeysLeft(handle, operator)));
                }
              }
            });
      }
    }
  }
}
