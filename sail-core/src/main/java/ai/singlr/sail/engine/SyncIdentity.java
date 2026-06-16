/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * The node's dedicated SSH identity for syncing to main: a single ed25519 keypair under {@code
 * ~/.sail/}, separate from the engineer's personal keys and used only for the {@code ssh sail@main
 * sail _sync} lane. Keeping it sail-managed makes the public key deterministic and printable at
 * join time, and lets {@code sail sync --watch} authenticate non-interactively without depending on
 * a forwarded agent.
 */
public final class SyncIdentity {

  private final ShellExec shell;
  private final Path privateKey;
  private final Path publicKey;

  public SyncIdentity(ShellExec shell) {
    this(shell, SailPaths.syncKeyPath(), SailPaths.syncPublicKeyPath());
  }

  public SyncIdentity(ShellExec shell, Path privateKey, Path publicKey) {
    this.shell = shell;
    this.privateKey = privateKey;
    this.publicKey = publicKey;
  }

  /**
   * Resolves the managed sync key to pass to {@code ssh -i}, or empty when none has been generated
   * yet. Empty keeps the sync lane on its default-key behaviour, so boxes that never ran {@code
   * sail join} are unaffected.
   */
  public static Optional<Path> resolve() {
    return resolve(SailPaths.syncKeyPath());
  }

  /** Pure resolver; visible for tests so the present/absent decision needs no real home dir. */
  static Optional<Path> resolve(Path keyPath) {
    return Files.isRegularFile(keyPath) ? Optional.of(keyPath) : Optional.empty();
  }

  /** True once the keypair exists on disk. */
  public boolean exists() {
    return Files.isRegularFile(privateKey) && Files.isRegularFile(publicKey);
  }

  /**
   * Generates the keypair if absent and returns its public-key line. Idempotent: an existing
   * keypair is reused untouched, so the public key a node hands its operator is stable across
   * re-runs of {@code sail join}.
   */
  public String ensure(String comment) throws IOException, InterruptedException, TimeoutException {
    if (!exists()) {
      SailPaths.ensureDataDir(privateKey.getParent());
      Files.deleteIfExists(privateKey);
      Files.deleteIfExists(publicKey);
      var result =
          shell.exec(
              List.of(
                  "ssh-keygen",
                  "-t",
                  "ed25519",
                  "-N",
                  "",
                  "-C",
                  comment,
                  "-f",
                  privateKey.toString()));
      if (!result.ok()) {
        throw new IOException("ssh-keygen failed: " + result.stderr().strip());
      }
    }
    return publicKeyLine();
  }

  /** Reads the public-key line, e.g. {@code ssh-ed25519 AAAA... sail-sync@host}. */
  public String publicKeyLine() throws IOException {
    return Files.readString(publicKey).strip();
  }
}
