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
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.EnrollmentTicketStore;
import ai.singlr.sail.store.FdeBoxes;
import ai.singlr.sail.store.FdePairingStore;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SqliteException;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

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
          dbPath(),
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

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  private static Path dbPath() {
    return SailPaths.controlPlaneDb();
  }

  private static OperatorCommand operator() {
    return OperatorCommand.here();
  }

  /**
   * Refuses to touch a pairing from the SSH gateway: its key line is in the operator's login, which
   * only the operator's own account writes.
   */
  private static void requireOperator(Box box, String command) {
    if (box.gateway()) {
      throw new IllegalStateException(
          "A pairing is a login of this box's operator, and an SSH-key session runs as the"
              + " gateway's account. Run 'sail fde "
              + command
              + "' on the host, as the account sail-api runs as.");
    }
  }

  /** The comment on every key line a pairing writes, and the only lines revoking it removes. */
  static String pairingComment(String handle) {
    return "sail-mast:" + handle;
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
  private static void requireOwnFde(Box box, String handle, String host) {
    var login = "A connect code logs in as " + box.operator() + "@" + host;
    if (box.handle() == null) {
      throw new IllegalStateException(
          login
              + ", which sees every project here, so it is minted only for this box's own FDE,"
              + " and this box names none. If it is "
              + handle
              + ", name it with 'sudo sail host config set sync-handle "
              + handle
              + "' and pair again.");
    }
    if (!handle.equals(box.handle())) {
      throw new IllegalStateException(
          login
              + ", which sees every project here. Pair "
              + handle
              + " on its own box instead: set it up with 'sudo sail init --main "
              + Objects.requireNonNullElse(box.main(), host)
              + "', then run 'sudo sail fde pair "
              + handle
              + " --host <its address>' there.");
    }
  }

  /**
   * Refuses an FDE roster edit on a node. The roster is main-authoritative and replicates one-way,
   * so an add/update/remove on a node is silently overwritten by the next sync — failing loud with
   * the right place to run it is far better than losing the edit.
   */
  private static void requireMainForRosterEdit(String action) {
    requireMainForRosterEdit(HostSync.isNode(HostSync.config()), action);
  }

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

  private static void registerKey(Sqlite db, FdeStore.Fde fde, String publicKey) throws Exception {
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
    applyKeys(db);
  }

  private static void applyKeys(Sqlite db) throws Exception {
    switch (new AuthorizedKeysSync().sync(db)) {
      case AuthorizedKeysSync.Synced synced ->
          System.out.println(Ansi.AUTO.string("  @|green ✓|@ " + synced.describe()));
      case AuthorizedKeysSync.NeedsRoot _ ->
          System.out.println(
              Ansi.AUTO.string("  @|faint Run 'sudo sail host keys sync' to apply.|@"));
      case AuthorizedKeysSync.NotProvisioned _ ->
          System.out.println(
              Ansi.AUTO.string(
                  "  @|faint SSH-key login is not provisioned on this host. Run 'sudo sail host"
                      + " ssh-identity' to enable it.|@"));
    }
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
            "Scope the FDE to the projects it is granted (sail fde grant); it sees nothing until"
                + " one is.")
    private boolean scoped;

    @Option(
        names = "--main-host-key",
        paramLabel = "FINGERPRINT",
        description =
            "The SHA256 fingerprint of this box's SSH host key that the joining node pinned, as"
                + " its join printed it; the add is refused when it is not this box's.")
    private String mainHostKey;

    @ParentCommand private FdeCommand command;

    @Spec private CommandSpec spec;

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
                throw new IllegalArgumentException(
                    "FDE '"
                        + handle
                        + "' already exists. Change it with '"
                        + operator.sail("fde update " + handle)
                        + "'.");
              }
              var fde = db.transaction(() -> add(fdeStore, key));
              System.out.println(
                  Ansi.AUTO.string(
                      "  @|green ✓|@ FDE added: "
                          + fde.handle()
                          + " ("
                          + fde.role()
                          + (scoped
                              ? ", scoped: grant projects with '" + operator.sail("fde grant") + "'"
                              : "")
                          + ")"));
              if (key != null) {
                System.out.println(
                    Ansi.AUTO.string(
                        "  @|green ✓|@ Registered key for "
                            + fde.handle()
                            + ": "
                            + key.fingerprint()));
                applyKeys(db);
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
                + " key; ask the engineer to re-run 'sudo sail init --main <this box>' on a network"
                + " path you trust.");
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
      description = "Update an FDE's display name, email, or role.",
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

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            requireMainForRosterEdit("Updating an FDE");
            if (displayName == null && email == null && role == null) {
              throw new IllegalArgumentException(
                  "Nothing to update. Pass --name, --email, and/or --role.");
            }
            try (var db = Sqlite.open(dbPath())) {
              var updated =
                  new FdeStore(db)
                      .update(handle, displayName, email, role)
                      .orElseThrow(
                          () -> new IllegalArgumentException("Unknown FDE '" + handle + "'."));
              System.out.println(
                  Ansi.AUTO.string(
                      "  @|green ✓|@ Updated " + updated.handle() + " (" + updated.role() + ")"));
            }
          });
    }
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

    @Spec private CommandSpec spec;

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
          "Take a project back from a scoped FDE: its box drops the project on its next sync.",
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

    @Spec private CommandSpec spec;

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
      throw new IllegalArgumentException(
          "Unknown project '"
              + project
              + "'. List the catalog with '"
              + operator.sail("project list")
              + "'.");
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
                : ". Its box drops the project, unpushed work included, on its next sync."));
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

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            if (HostSync.isNode(HostSync.config())) {
              throw new IllegalStateException(
                  "Main records the box each FDE syncs from, and a node records none. Run 'sail"
                      + " fde release-box "
                      + handle
                      + "' on main.");
            }
            try (var db = Sqlite.open(dbPath())) {
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

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            var box = command.box.get();
            requireMainForRosterEdit(box.node(), "Removing an FDE");
            if (!confirmed()) {
              System.out.println(Ansi.AUTO.string("  @|faint Cancelled.|@"));
              return;
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
                requireOperator(box, "rm " + handle);
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
                applyKeys(db);
              }
            }
          });
    }

    private boolean confirmed() {
      if (force) {
        return true;
      }
      System.out.print("  Remove FDE '" + handle + "' and revoke all its credentials? [y/N] ");
      var answer = System.console() != null ? System.console().readLine() : "y";
      return answer != null && answer.strip().equalsIgnoreCase("y");
    }
  }

  @Command(
      name = "pair",
      description =
          "Mint the connect code this box's own FDE pastes into Mast. The code logs in as the"
              + " account sail-api runs as, which sees every project here, so it is minted for no"
              + " other FDE: onboard another on its own box ('sudo sail init --main <main>') and"
              + " pair it there. Run it as the account sail-api runs as.",
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

    @Spec private CommandSpec spec;

    private record MintedKey(SshPublicKey publicKey, String privateKey) {}

    @Override
    public void run() {
      CliCommand.run(this::execute);
    }

    private void execute() throws Exception {
      var operator = operator();
      var box = command.box.get();
      validate();
      requireOwnFde(box, handle, host);
      if (box.node() && (displayName != null || email != null || role != null)) {
        throw new IllegalStateException(
            rosterEditOnNodeMessage("Changing an FDE's name, email or role"));
      }
      requireOperator(box, "pair " + handle);
      var ttl = ServerTokenCommand.Create.resolveTtl(noExpiry, ttlDays, PAIRING_TTL);
      try (var held = PairingTrust.hold(box.database());
          var db = Sqlite.open(box.database())) {
        var fdes = new FdeStore(db);
        var existing = fdes.byHandle(handle);
        if (box.node() && existing.isEmpty()) {
          throw new IllegalStateException(
              "This node holds no roster row for '"
                  + handle
                  + "' yet, and main's roster names the FDEs here. Run 'sudo sail sync', then pair"
                  + " again.");
        }
        var address = address(box, existing, operator);
        var hostKey = hostKey(box.hostKey());
        var pinned = hostKey.type() + " " + hostKey.blob();
        var replaced =
            replacedWorkstationKey(
                box, existing.flatMap(fde -> new FdePairingStore(db).find(fde.id())));
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
                    + "."));
        System.out.println();
        System.out.println("  Paste this code into Mast. It is a secret, and it is shown once:");
        System.out.println();
        System.out.println(code(box, address, pinned, key.privateKey(), token.token()));
        var projects = new ProjectStore(db).list().stream().map(ProjectStore.ProjectRow::name);
        var stale = projects.collect(Collectors.joining(", "));
        if (!stale.isEmpty()) {
          System.out.println();
          System.out.println(
              "  Containers of "
                  + stale
                  + " trust this key only after their next '"
                  + operator.sail("project apply")
                  + "'.");
        }
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
        throw new IllegalStateException(
            "FDE '"
                + handle
                + "' is disabled on this box, so a code for it would open nothing. Remove it"
                + " with '"
                + operator.sail("fde rm " + handle)
                + "' and pair again.");
      }
      var address = email != null ? email : existing.map(FdeStore.Fde::email).orElse(null);
      if (address == null) {
        throw new IllegalArgumentException(
            "This box holds no email for '"
                + handle
                + "', and a connect code names one. "
                + (box.node()
                    ? "Set it on main with 'sudo sail fde update "
                        + handle
                        + " --email <email>', run 'sudo sail sync' here, then pair again."
                    : "Pass --email <email>."));
      }
      return address;
    }

    /**
     * The registered workstation key this pairing takes over from someone else, if any. The key of
     * the FDE's own earlier pairing is replaced without asking; another's only with {@code
     * --as-workstation-key}, since containers trust one key and its owner would lose them.
     */
    private Optional<SshPublicKey> replacedWorkstationKey(
        Box box, Optional<FdePairingStore.Pairing> pairing) {
      var own = pairing.map(held -> SshPublicKey.parse(held.publicKey()).fingerprint());
      var replaced =
          WorkstationIdentity.registeredAt(box.workstationKey())
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

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            NameValidator.requireValidFdeHandle(handle);
            var box = command.box.get();
            requireOperator(box, "unpair " + handle);
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

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            var operator = operator();
            try (var db = Sqlite.open(command.box.get().database())) {
              var fdeStore = new FdeStore(db);
              var fdes = fdeStore.list();
              if (fdes.isEmpty()) {
                System.out.println(
                    "  No FDEs. Add one with '" + operator.sail("fde add <handle>") + "'.");
                return;
              }
              Banner.printFdeTable(fdes, fdeStore::scopeOf, System.out, Ansi.AUTO);
            }
          });
    }
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

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            try (var db = Sqlite.open(dbPath())) {
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

    @Override
    public void run() {
      new picocli.CommandLine(this).usage(System.out);
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

      @Spec private CommandSpec spec;

      @Override
      public void run() {
        CliCommand.run(
            () -> {
              var operator = operator();
              try (var db = Sqlite.open(dbPath())) {
                var fde =
                    new FdeStore(db)
                        .byHandle(handle)
                        .orElseThrow(
                            () ->
                                new IllegalArgumentException(
                                    "Unknown FDE '"
                                        + handle
                                        + "'. Add it with '"
                                        + operator.sail("fde add " + handle)
                                        + "'."));
                registerKey(db, fde, publicKey);
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

      @Spec private CommandSpec spec;

      @Override
      public void run() {
        CliCommand.run(
            () -> {
              var operator = operator();
              try (var db = Sqlite.open(dbPath())) {
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
                  System.out.println(
                      "  No SSH keys. Register one with '" + operator.sail("fde key add") + "'.");
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

      @Spec private CommandSpec spec;

      @Override
      public void run() {
        CliCommand.run(
            () -> {
              var operator = operator();
              try (var db = Sqlite.open(dbPath())) {
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
                  applyKeys(db);
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
                            "'"
                                + target
                                + "' is neither a known FDE handle nor a SHA256:"
                                + " fingerprint. See '"
                                + operator.sail("fde key list")
                                + "'."));
        var keys = keyStore.listForFde(fde.id());
        if (keys.isEmpty()) {
          System.out.println(
              Ansi.AUTO.string("  @|yellow ⚠|@ No SSH keys registered for '" + target + "'."));
          return Optional.empty();
        }
        if (keys.size() > 1) {
          System.out.println("  '" + target + "' has " + keys.size() + " keys; specify one:");
          for (var key : keys) {
            System.out.println("    " + operator.sail("fde key rm " + key.fingerprint()));
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

    @Override
    public void run() {
      new picocli.CommandLine(this).usage(System.out);
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

      @Spec private CommandSpec spec;

      @Override
      public void run() {
        CliCommand.run(
            () -> {
              var operator = operator();
              try (var db = Sqlite.open(dbPath())) {
                var fde = requireFde(db, handle);
                var credentials = new WebauthnCredentialStore(db).listForFde(fde.id());
                if (credentials.isEmpty()) {
                  System.out.println(
                      "  No passkeys for '"
                          + handle
                          + "'. Enroll one with '"
                          + operator.sail("fde enroll " + handle)
                          + "'.");
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
          description =
              "Credential id or an unambiguous prefix; see 'sail fde passkey list <handle>'.")
      private String credential;

      @Option(
          names = "--revoke-sessions",
          description = "Also end the FDE's active web sessions (the lost-device case).")
      private boolean revokeSessions;

      @Spec private CommandSpec spec;

      @Override
      public void run() {
        CliCommand.run(
            () -> {
              var operator = operator();
              try (var db = Sqlite.open(dbPath())) {
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
                  System.out.println(
                      Ansi.AUTO.string(
                          "  @|faint '"
                              + handle
                              + "' has no passkeys left. Re-enroll with '"
                              + operator.sail("fde enroll " + handle)
                              + "'.|@"));
                }
              }
            });
      }
    }
  }
}
