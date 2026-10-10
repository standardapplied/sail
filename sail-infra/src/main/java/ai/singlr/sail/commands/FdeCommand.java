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
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import ai.singlr.sail.engine.SyncIdentity;
import ai.singlr.sail.engine.WorkstationIdentity;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.store.AuthSessionStore;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
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

  /** How long a connect code, and so its token, lives unless the operator says otherwise. */
  private static final Duration PAIRING_TTL = Duration.ofDays(365);

  /**
   * What a pairing touches on this box: the control plane, the operator (the account {@code
   * sail-api} runs as, whose home holds the pty socket Mast forwards to), the SSH host key a code
   * pins and the workstation key containers trust. A test puts all of it under a temporary
   * directory.
   */
  record Box(
      Path database,
      String operator,
      Path home,
      Path hostKey,
      Path workstationKey,
      ShellExec shell,
      boolean node) {

    static Box here() {
      return new Box(
          dbPath(),
          System.getProperty("user.name"),
          Path.of(System.getProperty("user.home")),
          Path.of("/etc/ssh/ssh_host_ed25519_key.pub"),
          SailPaths.workstationPublicKeyPath(),
          new ShellExecutor(false),
          HostSync.isNode(HostSync.config()));
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

  private static String keyLinesRemoved(Box box, int count) {
    return (count == 1 ? "1 key line" : count + " key lines")
        + " removed from "
        + box.operator()
        + "'s authorized_keys";
  }

  /**
   * Revokes {@code handle}'s pairing: every key line made for it in the operator's login, the token
   * the pairing names and its record. Returns what it removed, or empty when there was no pairing.
   * The lines go by their comment, so a pairing whose FDE is already gone is still revoked.
   */
  private static Optional<String> unpair(Box box, Sqlite db, String handle) throws IOException {
    var keyLines = new OperatorAuthorizedKeys(box.home()).removeCommented(pairingComment(handle));
    var pairing =
        new FdeStore(db).byHandle(handle).flatMap(fde -> new FdePairingStore(db).find(fde.id()));
    if (keyLines == 0 && pairing.isEmpty()) {
      return Optional.empty();
    }
    var revoked =
        pairing.isPresent() && forget(db, pairing.get())
            ? " and token '" + pairing.get().tokenName() + "' revoked"
            : "";
    return Optional.of(keyLinesRemoved(box, keyLines) + revoked + ".");
  }

  /**
   * Refuses an FDE roster edit on a node. The roster is main-authoritative and replicates one-way,
   * so an add/update/remove on a node is silently overwritten by the next sync — failing loud with
   * the right place to run it is far better than losing the edit.
   */
  /** Deletes a pairing's record and revokes its token, answering whether the token was there. */
  private static boolean forget(Sqlite db, FdePairingStore.Pairing pairing) {
    return db.transaction(
        () -> {
          new FdePairingStore(db).remove(pairing.fdeId());
          return new TokenStore(db).revoke(pairing.tokenName());
        });
  }

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

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          spec,
          () -> {
            requireMainForRosterEdit("Adding an FDE");
            var key = publicKey == null ? null : SshPublicKey.parse(publicKey);
            try (var db = Sqlite.open(dbPath())) {
              var fdeStore = new FdeStore(db);
              if (fdeStore.byHandle(handle).isPresent()) {
                throw new IllegalArgumentException(
                    "FDE '"
                        + handle
                        + "' already exists. Change it with 'sail fde update "
                        + handle
                        + "'.");
              }
              var fde =
                  key == null
                      ? fdeStore.add(handle, displayName, email, role)
                      : addWithKey(fdeStore, key);
              System.out.println(
                  Ansi.AUTO.string(
                      "  @|green ✓|@ FDE added: " + fde.handle() + " (" + fde.role() + ")"));
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
          spec,
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
          spec,
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
          spec,
          () -> {
            var box = command.box.get();
            requireMainForRosterEdit(box.node(), "Removing an FDE");
            try (var db = Sqlite.open(box.database())) {
              var fdeStore = new FdeStore(db);
              var fde =
                  fdeStore
                      .byHandle(handle)
                      .orElseThrow(
                          () -> new IllegalArgumentException("Unknown FDE '" + handle + "'."));
              var paired = new FdePairingStore(db).find(fde.id()).isPresent();
              if (paired) {
                requireOperator(box, "rm " + handle);
              }
              if (!confirmed()) {
                System.out.println(Ansi.AUTO.string("  @|faint Cancelled.|@"));
                return;
              }
              var hadSshKeys = !new FdeSshKeyStore(db).listForFde(fde.id()).isEmpty();
              var keyLines =
                  db.transaction(
                      () -> {
                        fdeStore.remove(fde.id());
                        return box.gateway() ? 0 : removeLogin(box, fde.handle());
                      });
              System.out.println(Ansi.AUTO.string("  @|green ✓|@ FDE removed: " + fde.handle()));
              System.out.println(
                  Ansi.AUTO.string(
                      "  @|faint Owned tokens, SSH keys, sessions, passkeys, and enrollment"
                          + " tickets are revoked.|@"));
              if (paired || keyLines > 0) {
                System.out.println(
                    Ansi.AUTO.string(
                        "  @|green ✓|@ Its Mast pairing is revoked: "
                            + keyLinesRemoved(box, keyLines)
                            + "."));
              }
              if (hadSshKeys) {
                applyKeys(db);
              }
            }
          });
    }

    /**
     * Removes the FDE's key lines from the operator's login inside the transaction that removes the
     * FDE, so a login that could not be revoked takes the removal back with it: the FDE and its
     * pairing's record stay for the next run to finish, instead of a login outliving both.
     */
    private static int removeLogin(Box box, String handle) {
      try {
        return new OperatorAuthorizedKeys(box.home()).removeCommented(pairingComment(handle));
      } catch (IOException failure) {
        throw new UncheckedIOException(
            "Could not remove "
                + handle
                + "'s key line from "
                + box.operator()
                + "'s authorized_keys ("
                + failure.getClass().getSimpleName()
                + "), so "
                + handle
                + " was not removed and its pairing still works. Check that "
                + box.home().resolve(".ssh")
                + " is writable by "
                + box.operator()
                + ", then run 'sail fde rm "
                + handle
                + "' again.",
            failure);
      }
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
          "Mint the one connect code an FDE's Mast needs to reach this box. Run it as the account"
              + " sail-api runs as.",
      mixinStandardHelpOptions = true)
  static final class Pair implements Runnable {

    @Parameters(index = "0", description = "FDE handle; added when this box has no such FDE.")
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
      CliCommand.run(spec, this::execute);
    }

    private void execute() throws Exception {
      var box = command.box.get();
      requireMainForRosterEdit(box.node(), "Pairing an FDE");
      validate();
      requireOperator(box, "pair " + handle);
      var ttl = ServerTokenCommand.Create.resolveTtl(noExpiry, ttlDays, PAIRING_TTL);
      try (var db = Sqlite.open(box.database())) {
        var fdes = new FdeStore(db);
        var existing = fdes.byHandle(handle);
        var address = address(existing);
        var hostKey = hostKey(box.hostKey());
        var replaced =
            replacedWorkstationKey(
                box, existing.flatMap(fde -> new FdePairingStore(db).find(fde.id())));
        var key = mintKey(box.shell());
        var fde =
            existing.isPresent()
                ? fdes.update(handle, displayName, email, role).orElseThrow()
                : fdes.add(
                    handle,
                    displayName,
                    email,
                    Objects.requireNonNullElse(role, FdeStore.DEFAULT_ROLE));
        if (existing.isEmpty()) {
          System.out.println(
              Ansi.AUTO.string(
                  "  @|green ✓|@ FDE added: " + fde.handle() + " (" + fde.role() + ")"));
        }
        unpair(box, db, handle)
            .ifPresent(
                removed ->
                    System.out.println(
                        Ansi.AUTO.string(
                            "  @|green ✓|@ Unpaired " + handle + " first: " + removed)));
        new OperatorAuthorizedKeys(box.home()).append(key.publicKey(), pairingComment(handle));
        HostConfigSetCommand.writeWorkstationKey(box.workstationKey(), key.publicKey().line());
        var token = mintToken(db, fde, key.publicKey(), ttl);
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
        System.out.println(code(box, address, hostKey, key.privateKey(), token.token()));
        var projects = new ProjectStore(db).list().stream().map(ProjectStore.ProjectRow::name);
        var stale = projects.collect(Collectors.joining(", "));
        if (!stale.isEmpty()) {
          System.out.println();
          System.out.println(
              "  Containers of "
                  + stale
                  + " trust this key only after their next 'sail project apply'.");
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

    /** The email the code names: the one passed, else the one this box holds for the FDE. */
    private String address(Optional<FdeStore.Fde> existing) {
      if (existing.isPresent() && !existing.get().active()) {
        throw new IllegalStateException(
            "FDE '"
                + handle
                + "' is disabled on this box, so a code for it would open nothing. Remove it"
                + " with 'sail fde rm "
                + handle
                + "' and pair again.");
      }
      var address = email != null ? email : existing.map(FdeStore.Fde::email).orElse(null);
      if (address == null) {
        throw new IllegalArgumentException(
            "This box holds no email for '"
                + handle
                + "', and a connect code names one. Pass --email <email>.");
      }
      return address;
    }

    private static String hostKey(Path file) throws IOException {
      if (!Files.isRegularFile(file)) {
        throw new IllegalStateException(
            "This box has no SSH host key at "
                + file
                + ", and a connect code pins it. Generate one with 'sudo ssh-keygen -A' and pair"
                + " again.");
      }
      var key = SshPublicKey.parse(Files.readString(file));
      return key.type() + " " + key.blob();
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
          spec,
          () -> {
            NameValidator.requireValidFdeHandle(handle);
            var box = command.box.get();
            requireOperator(box, "unpair " + handle);
            try (var db = Sqlite.open(box.database())) {
              System.out.println(
                  Ansi.AUTO.string(
                      unpair(box, db, handle)
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

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          spec,
          () -> {
            try (var db = Sqlite.open(dbPath())) {
              var fdes = new FdeStore(db).list();
              if (fdes.isEmpty()) {
                System.out.println("  No FDEs. Add one with 'sail fde add <handle>'.");
                return;
              }
              Banner.printFdeTable(fdes, System.out, Ansi.AUTO);
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
          spec,
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
            spec,
            () -> {
              try (var db = Sqlite.open(dbPath())) {
                var fde =
                    new FdeStore(db)
                        .byHandle(handle)
                        .orElseThrow(
                            () ->
                                new IllegalArgumentException(
                                    "Unknown FDE '"
                                        + handle
                                        + "'. Add it with 'sail fde add "
                                        + handle
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
            spec,
            () -> {
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
                  System.out.println("  No SSH keys. Register one with 'sail fde key add'.");
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
            spec,
            () -> {
              try (var db = Sqlite.open(dbPath())) {
                var keyStore = new FdeSshKeyStore(db);
                var fingerprint =
                    target.startsWith("SHA256:")
                        ? Optional.of(target)
                        : resolveByHandle(db, keyStore);
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
      private Optional<String> resolveByHandle(Sqlite db, FdeSshKeyStore keyStore) {
        var fde =
            new FdeStore(db)
                .byHandle(target)
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "'"
                                + target
                                + "' is neither a known FDE handle nor a SHA256:"
                                + " fingerprint. See 'sail fde key list'."));
        var keys = keyStore.listForFde(fde.id());
        if (keys.isEmpty()) {
          System.out.println(
              Ansi.AUTO.string("  @|yellow ⚠|@ No SSH keys registered for '" + target + "'."));
          return Optional.empty();
        }
        if (keys.size() > 1) {
          System.out.println("  '" + target + "' has " + keys.size() + " keys; specify one:");
          for (var key : keys) {
            System.out.println("    sail fde key rm " + key.fingerprint());
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
        String handle, String prefix, List<WebauthnCredentialStore.Credential> credentials) {
      if (credentials.isEmpty()) {
        return "'"
            + handle
            + "' has no passkeys. Enroll one with 'sail fde enroll "
            + handle
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
            spec,
            () -> {
              try (var db = Sqlite.open(dbPath())) {
                var fde = requireFde(db, handle);
                var credentials = new WebauthnCredentialStore(db).listForFde(fde.id());
                if (credentials.isEmpty()) {
                  System.out.println(
                      "  No passkeys for '"
                          + handle
                          + "'. Enroll one with 'sail fde enroll "
                          + handle
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
            spec,
            () -> {
              try (var db = Sqlite.open(dbPath())) {
                var fde = requireFde(db, handle);
                var store = new WebauthnCredentialStore(db);
                var credentials = store.listForFde(fde.id());
                var match =
                    switch (Passkeys.resolveByPrefix(credentials, credential)) {
                      case Passkeys.Match m -> m.credential();
                      case Passkeys.NotFound _ ->
                          throw new IllegalArgumentException(
                              noMatchMessage(handle, credential, credentials));
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
                              + "' has no passkeys left. Re-enroll with 'sail fde enroll "
                              + handle
                              + "'.|@"));
                }
              }
            });
      }
    }
  }
}
