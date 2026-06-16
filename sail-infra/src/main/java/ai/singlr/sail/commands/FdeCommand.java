/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.auth.EnrollmentService;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AuthorizedKeysSync;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.store.EnrollmentTicketStore;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SqliteException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
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
      FdeCommand.Remove.class,
      FdeCommand.Enroll.class,
      FdeCommand.Key.class
    })
public final class FdeCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  private static Path dbPath() {
    return SailPaths.controlPlaneDb();
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
            var key = publicKey == null ? null : SshPublicKey.parse(publicKey);
            try (var db = Sqlite.open(dbPath())) {
              var fdeStore = new FdeStore(db);
              if (fdeStore.byHandle(handle).isPresent()) {
                throw new IllegalArgumentException("FDE '" + handle + "' already exists.");
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
      name = "rm",
      description = "Remove an FDE and revoke everything that authenticates as it.",
      mixinStandardHelpOptions = true)
  static final class Remove implements Runnable {

    @Parameters(index = "0", description = "FDE handle.")
    private String handle;

    @Option(names = "--force", description = "Skip confirmation.")
    private boolean force;

    @Spec private CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          spec,
          () -> {
            try (var db = Sqlite.open(dbPath())) {
              var fdeStore = new FdeStore(db);
              var fde =
                  fdeStore
                      .byHandle(handle)
                      .orElseThrow(
                          () -> new IllegalArgumentException("Unknown FDE '" + handle + "'."));
              if (!confirmed()) {
                System.out.println(Ansi.AUTO.string("  @|faint Cancelled.|@"));
                return;
              }
              var hadSshKeys = !new FdeSshKeyStore(db).listForFde(fde.id()).isEmpty();
              fdeStore.remove(fde.id());
              System.out.println(Ansi.AUTO.string("  @|green ✓|@ FDE removed: " + fde.handle()));
              System.out.println(
                  Ansi.AUTO.string(
                      "  @|faint Owned tokens, SSH keys, sessions, passkeys, and enrollment"
                          + " tickets are revoked.|@"));
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
}
