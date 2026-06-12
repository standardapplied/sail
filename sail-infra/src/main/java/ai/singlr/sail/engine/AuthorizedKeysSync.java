/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Installs the {@code sail} user's {@code authorized_keys} from the SSH-key registry — the single
 * write path shared by {@code sail host keys sync}, the {@code sail fde} key mutations, and the
 * upgrade reconciler. The file is fully regenerated each time, so registry membership is the only
 * source of truth and removing a key revokes its SSH access on the next sync.
 *
 * <p>Syncing is environment-dependent, so callers receive an {@link Outcome} instead of an
 * exception: writing into the sail user's home requires root, and an unprovisioned host has nowhere
 * to write. Each caller decides whether that is an error (the explicit {@code keys sync} command)
 * or a hint (an {@code fde key add} that registered the key but could not install it).
 */
public final class AuthorizedKeysSync {

  public sealed interface Outcome permits Synced, NeedsRoot, NotProvisioned {}

  /** The file was regenerated from the registry. */
  public record Synced(int keyCount, Path destination) implements Outcome {}

  /** The caller lacks root; the registry changed but the file did not. */
  public record NeedsRoot() implements Outcome {}

  /** The host has no provisioned {@code sail} user to install keys for. */
  public record NotProvisioned() implements Outcome {}

  private final Path destination;
  private final boolean root;
  private final ShellExec shell;

  public AuthorizedKeysSync() {
    this(
        Path.of(SshIdentityProvisioner.SAIL_HOME, ".ssh", "authorized_keys"),
        "root".equals(ProcessHandle.current().info().user().orElse("")),
        new ShellExecutor(false));
  }

  AuthorizedKeysSync(Path destination, boolean root, ShellExec shell) {
    this.destination = destination;
    this.root = root;
    this.shell = shell;
  }

  /** Renders the authorized_keys content without writing it, for {@code --dry-run}. */
  public String render(Sqlite db) {
    return AuthorizedKeysRenderer.render(
        new FdeSshKeyStore(db).list(), SailPaths.binaryPath().toString());
  }

  public Outcome sync(Sqlite db) throws Exception {
    var keys = new FdeSshKeyStore(db).list();
    var content = AuthorizedKeysRenderer.render(keys, SailPaths.binaryPath().toString());
    if (!root) {
      return new NeedsRoot();
    }
    if (!Files.isDirectory(destination.getParent())) {
      return new NotProvisioned();
    }
    install(content);
    return new Synced(keys.size(), destination);
  }

  private void install(String content) throws Exception {
    var temp = Files.createTempFile("sail-authorized-keys", "");
    try {
      Files.writeString(temp, content);
      var result =
          shell.exec(
              List.of(
                  "install",
                  "-m",
                  "600",
                  "-o",
                  "sail",
                  "-g",
                  "sail",
                  temp.toString(),
                  destination.toString()));
      if (!result.ok()) {
        throw new IllegalStateException("Failed to write " + destination + ":\n" + result.stderr());
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
