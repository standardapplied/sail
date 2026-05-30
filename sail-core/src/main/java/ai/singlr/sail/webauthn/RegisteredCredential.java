/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

/**
 * The result of a successful registration: everything the control plane must persist to later
 * verify this passkey's assertions. {@code publicKeyCose} is the credential public key in its
 * original COSE wire form (re-parsed via {@link CoseKey} at assertion time); {@code signCount} is
 * the initial signature counter used for clone detection; the backup flags record whether the
 * passkey is eligible for / currently synced to the authenticator's cloud backup.
 */
public record RegisteredCredential(
    byte[] credentialId,
    byte[] publicKeyCose,
    long coseAlgorithm,
    long signCount,
    byte[] aaguid,
    boolean backupEligible,
    boolean backupState) {}
