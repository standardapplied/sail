/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.auth.Passkeys;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.store.WebauthnCredentialStore.Credential;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The doors to a person's own passkeys (Keep passkeys): list them, saying which one the calling
 * session was signed in with, and remove one, ending every session it signed in. Each acts on its
 * caller's own alone, and answers another person's id as it answers an id that names nothing, so no
 * id is probed. The last passkey and the one the caller is signed in with stay: a person never
 * locks themselves out or signs themselves out by removing one. An admin removes another person's
 * only on the host, which keeps the lost-device case.
 */
public final class PasskeyOperations {

  private final Sqlite db;
  private final FdeStore fdes;
  private final WebauthnCredentialStore credentials;
  private final AuthSessionStore sessions;

  PasskeyOperations(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
    this.fdes = new FdeStore(db);
    this.credentials = new WebauthnCredentialStore(db);
    this.sessions = new AuthSessionStore(db);
  }

  /** The caller's passkeys, oldest first, the one {@code credential} signed in with current. */
  PasskeysResponse list(String credential) {
    var signedInWith = sessions.passkeyOf(credential);
    return new PasskeysResponse(
        own().stream()
            .map(passkey -> new PasskeyView(passkey, signedIn(passkey, signedInWith)))
            .toList());
  }

  /**
   * Removes the caller's passkey {@code id}, its credential id as {@link Passkeys#encodeId} writes
   * it, and ends every session it signed in, at once; refused when it is not theirs, their last, or
   * the one {@code credential}, the caller's own, signed in with, the last said first.
   */
  void remove(String id, String credential) {
    var held = own();
    var passkey =
        held.stream()
            .filter(each -> Passkeys.encodeId(each.credentialId()).equals(id))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.NOT_FOUND,
                        "No passkey of yours has that id.",
                        Remedy.of("List your passkeys again.")));
    if (held.size() == 1) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "This is your only passkey, so removing it would leave you no way to sign in.",
          Remedy.of("Enroll another passkey with a ticket from your admin, then remove this one."));
    }
    if (signedIn(passkey, sessions.passkeyOf(credential))) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "You are signed in with this passkey.",
          Remedy.of("Sign in with another passkey to remove this one."));
    }
    db.transaction(
        () -> {
          credentials.delete(passkey.credentialId());
          sessions.revokeMintedBy(passkey.credentialId());
        });
  }

  /**
   * The passkeys of the person the bound caller is, once they may keep passkeys at all: none for a
   * credential that acts for no person on this box's roster.
   */
  private List<Credential> own() {
    var actor = Actor.current();
    if (Access.decide(actor, Action.KEEP_PASSKEYS, Resources.people())
        instanceof Decision.Refused(var refusal)) {
      throw Refusals.exception(refusal);
    }
    return Access.attendee(actor)
        .flatMap(fdes::byHandle)
        .map(person -> credentials.listForFde(person.id()))
        .orElse(List.of());
  }

  private static boolean signedIn(Credential passkey, Optional<byte[]> signedInWith) {
    return signedInWith.filter(id -> Arrays.equals(id, passkey.credentialId())).isPresent();
  }
}
