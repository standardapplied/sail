/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.commands.FdeCommand.Box;
import ai.singlr.sail.engine.ContainerAuthorizedKeys;
import ai.singlr.sail.engine.OperatorAuthorizedKeys;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.store.FdePairingStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Takes back everything a pairing made this box trust: the key lines in the operator's login, the
 * key in this box's containers and, when it is the workstation key, that file, then the token and
 * the record. {@code fde unpair}, {@code fde rm} and a re-pair all revoke through here, each
 * holding {@link PairingTrust}.
 */
final class PairingRevocation {

  private static final String KEPT_UNTIL_THE_KEY_IS_GONE =
      "Its login is revoked, and its token and record are kept until the key is gone:";

  private PairingRevocation() {}

  private static String keyLinesRemoved(Box box, int count) {
    return (count == 1 ? "1 key line" : count + " key lines")
        + " removed from "
        + box.operator()
        + "'s authorized_keys";
  }

  /**
   * Removes {@code handle}'s key lines from the operator's login, and returns how many. A login
   * that cannot be removed fails the command here, before it forgets the FDE, the token or the
   * record: a login must never outlive what revokes it.
   */
  private static int revokeLogin(Box box, String handle) {
    try {
      return new OperatorAuthorizedKeys(box.home())
          .removeCommented(FdeCommand.pairingComment(handle));
    } catch (IOException failure) {
      throw new UncheckedIOException(
          "Could not remove "
              + handle
              + "'s key line from "
              + box.operator()
              + "'s authorized_keys ("
              + failure.getClass().getSimpleName()
              + "), so nothing of "
              + handle
              + " was removed and its pairing still works. Check that "
              + box.home().resolve(".ssh")
              + " is writable by "
              + box.operator()
              + ", then run the command again.",
          failure);
    }
  }

  private static Optional<FdePairingStore.Pairing> pairingOf(Sqlite db, String handle) {
    return new FdeStore(db).byHandle(handle).flatMap(fde -> new FdePairingStore(db).find(fde.id()));
  }

  static Optional<String> revoke(Box box, Sqlite db, String handle) {
    return revoke(box, db, handle, pairingOf(db, handle));
  }

  /**
   * Revokes {@code handle}'s pairing and every trust it left on this box, the one seam each
   * revocation goes through. Its key lines leave the operator's login; then its key leaves the
   * containers of this box and, when it is the workstation key, that file; last its token and
   * record are forgotten. A step that fails stops it before the record that names the key is
   * forgotten, so running it again finishes it. Returns what it removed, or empty when there was no
   * pairing. The login lines go by their comment, so a pairing whose record is already gone still
   * loses its login.
   */
  static Optional<String> revoke(
      Box box, Sqlite db, String handle, Optional<FdePairingStore.Pairing> pairing) {
    var keyLines = revokeLogin(box, handle);
    var key = pairing.map(held -> SshPublicKey.parse(held.publicKey()));
    var containers = key.map(held -> untrustContainers(box, handle, held)).orElse(List.of());
    var workstation = key.filter(held -> removeWorkstationKey(box, handle, held)).isPresent();
    if (keyLines == 0 && pairing.isEmpty()) {
      return Optional.empty();
    }
    var revoked =
        pairing.isPresent() && forget(db, pairing.get())
            ? " and token '" + pairing.get().tokenName() + "' revoked"
            : "";
    return Optional.of(
        keyLinesRemoved(box, keyLines)
            + revoked
            + "."
            + (workstation ? " The workstation key was its key and is removed." : "")
            + (containers.isEmpty()
                ? ""
                : " Containers of " + String.join(", ", containers) + " no longer trust it."));
  }

  /** Removes {@code key} from every container of this box, and returns those it was in. */
  private static List<String> untrustContainers(Box box, String handle, SshPublicKey key) {
    try {
      return new ContainerAuthorizedKeys(box.shell()).remove(key);
    } catch (IOException | TimeoutException failure) {
      throw new IllegalStateException(
          "Could not remove "
              + handle
              + "'s key from this box's containers ("
              + failure.getMessage()
              + "). "
              + KEPT_UNTIL_THE_KEY_IS_GONE
              + " check that 'incus list' answers, then run the command again.",
          failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while removing " + handle + "'s key from this box's containers.",
          interrupted);
    }
  }

  /**
   * Deletes the workstation key file when it holds {@code key}, answering whether it did. Only a
   * missing file holds nothing: one that cannot be read may hold the key, so it fails the
   * revocation before the record that names the key is forgotten.
   */
  private static boolean removeWorkstationKey(Box box, String handle, SshPublicKey key) {
    try {
      if (!heldBy(Files.readString(box.workstationKey()), key)) {
        return false;
      }
      Files.delete(box.workstationKey());
      return true;
    } catch (NoSuchFileException missing) {
      return false;
    } catch (IOException failure) {
      throw new UncheckedIOException(
          "Could not remove "
              + box.workstationKey()
              + ", which may hold "
              + handle
              + "'s key ("
              + failure.getClass().getSimpleName()
              + "). "
              + KEPT_UNTIL_THE_KEY_IS_GONE
              + " check that "
              + box.workstationKey()
              + " and its directory are readable and writable by "
              + box.operator()
              + ", then run the command again.",
          failure);
    }
  }

  private static boolean heldBy(String workstationKey, SshPublicKey key) {
    try {
      return SshPublicKey.parse(workstationKey).fingerprint().equals(key.fingerprint());
    } catch (IllegalArgumentException notAKey) {
      return false;
    }
  }

  /** Deletes a pairing's record and revokes its token, answering whether the token was there. */
  private static boolean forget(Sqlite db, FdePairingStore.Pairing pairing) {
    return db.transaction(
        () -> {
          new FdePairingStore(db).remove(pairing.fdeId());
          return new TokenStore(db).revoke(pairing.tokenName());
        });
  }
}
