/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.ssh.SshPublicKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

  /** The one command that registers a box's workstation key, shared so the guidance can't drift. */
  public static final Remedy.Command SET_KEY = Remedy.sail("host config set ssh-public-key");

  /**
   * The registered workstation key, or empty when the box owner has not set one. A key file that
   * holds no readable key is refused rather than read as none: every container trusts this key, so
   * a damaged file would silently take it out of all of them on the next apply.
   */
  public static Optional<SshPublicKey> registered() {
    return registeredAt(SailPaths.workstationPublicKeyPath());
  }

  public static Optional<SshPublicKey> registeredAt(Path path) {
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    String line;
    try {
      line = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new IllegalStateException(
          "This box's workstation key ("
              + path
              + ") could not be read ("
              + e.getClass().getSimpleName()
              + "). Check that the account sail runs as can read it.",
          e);
    }
    try {
      return Optional.of(SshPublicKey.parse(line));
    } catch (IllegalArgumentException e) {
      throw Remedied.state(
          "This box's workstation key (" + path + ") is not an SSH public key: " + e.getMessage(),
          Remedy.of("Register the key you connect with: ", SET_KEY),
          e);
    }
  }

  /** The Mac-side {@code IdentityFile} a connect snippet should reference for this box's owner. */
  public static String identityFile() {
    return identityFileFor(registered());
  }

  public static String identityFileFor(Optional<SshPublicKey> key) {
    return "~/.ssh/" + key.map(SshPublicKey::defaultPrivateKeyName).orElse("id_ed25519");
  }
}
