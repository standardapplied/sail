/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.ssh.SshPublicKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The box owner's workstation SSH key — the human key authorized into this box's project containers
 * (see {@link SailPaths#workstationPublicKeyPath()}). Connect snippets read it to point their
 * {@code IdentityFile} at the matching local private key instead of guessing {@code id_ed25519},
 * and to warn when it is unset (a container provisioned without it trusts no laptop).
 */
public final class WorkstationIdentity {

  private WorkstationIdentity() {}

  /**
   * The one command that registers a box's workstation key, as {@code operator} types it — shared
   * so the guidance can't drift.
   */
  public static String setKeyHint(OperatorCommand operator) {
    return operator.sail("host config set ssh-public-key");
  }

  /**
   * The registered workstation key, or empty when the box owner has not set one. A key file that
   * holds no readable key is refused rather than read as none: every container trusts this key, so
   * a damaged file would silently take it out of all of them on the next apply.
   */
  public static Optional<SshPublicKey> registered(OperatorCommand operator) {
    return registeredAt(SailPaths.workstationPublicKeyPath(), operator);
  }

  public static Optional<SshPublicKey> registeredAt(Path path, OperatorCommand operator) {
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    String line;
    try {
      line = Files.readString(path).strip();
    } catch (IOException e) {
      throw new IllegalStateException(
          "This box's workstation key (" + path + ") could not be read: " + e.getMessage(), e);
    }
    try {
      return Optional.of(SshPublicKey.parse(line));
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "This box's workstation key ("
              + path
              + ") is not an SSH public key: "
              + e.getMessage()
              + " Register the key you connect with: "
              + setKeyHint(operator),
          e);
    }
  }

  /** The Mac-side {@code IdentityFile} a connect snippet should reference for this box's owner. */
  public static String identityFile(OperatorCommand operator) {
    return identityFileFor(registered(operator));
  }

  public static String identityFileFor(Optional<SshPublicKey> key) {
    return "~/.ssh/" + key.map(SshPublicKey::defaultPrivateKeyName).orElse("id_ed25519");
  }
}
