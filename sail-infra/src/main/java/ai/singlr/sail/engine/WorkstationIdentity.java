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

  /**
   * The one command that registers a box's workstation key — shared so the guidance can't drift.
   */
  public static final String SET_KEY_HINT =
      "sail host config set ssh-public-key \"$(cat ~/.ssh/id_ed25519.pub)\"";

  private WorkstationIdentity() {}

  /** The registered workstation key, or empty when the box owner has not set one. */
  public static Optional<SshPublicKey> registered() {
    return registeredAt(SailPaths.workstationPublicKeyPath());
  }

  static Optional<SshPublicKey> registeredAt(Path path) {
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(SshPublicKey.parse(Files.readString(path).strip()));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /** The Mac-side {@code IdentityFile} a connect snippet should reference for this box's owner. */
  public static String identityFile() {
    return identityFileFor(registered());
  }

  static String identityFileFor(Optional<SshPublicKey> key) {
    return "~/.ssh/" + key.map(SshPublicKey::defaultPrivateKeyName).orElse("id_ed25519");
  }
}
