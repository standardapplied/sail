/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.BoxCredentialStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

/**
 * Reconciles the ambient credential file next to the API socket with the hashed row in the
 * database. The file rides the same bind-mounted directory as the socket, so provisioning it on the
 * host makes it instantly readable in every project container with zero per-container work.
 * Self-healing by construction: a missing file, tampered contents, a rotated row, or a changed box
 * handle all converge to a freshly minted pair on the next call. World-readable on purpose — the
 * mount surfaces unmapped uids inside containers, and the directory sits inside the trust boundary
 * the box owner's SSH key already defines.
 */
public final class BoxCredentialFile {

  public static final String FILE_NAME = "box.credential";

  private static final String FILE_PERMISSIONS = "rw-r--r--";

  /** What {@link #ensure} found and did. */
  public enum Outcome {
    ALREADY_PRESENT,
    PROVISIONED
  }

  private BoxCredentialFile() {}

  /**
   * Ensures {@code dir/box.credential} holds a plaintext that resolves to {@code handle}, minting
   * and rewriting when it does not.
   */
  public static Outcome ensure(BoxCredentialStore store, String handle, Path dir)
      throws IOException {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(handle, "handle");
    var file = dir.resolve(FILE_NAME);
    if (Files.isRegularFile(file)
        && store.resolve(Files.readString(file).strip()).filter(handle::equals).isPresent()) {
      return Outcome.ALREADY_PRESENT;
    }
    Files.createDirectories(dir);
    Files.writeString(file, store.replace(handle) + "\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(FILE_PERMISSIONS));
    return Outcome.PROVISIONED;
  }
}
