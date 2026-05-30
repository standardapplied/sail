/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

/**
 * The outcome of a verified assertion. {@code signCount} is the authenticator's new signature
 * counter, which the caller must persist (it must strictly increase across assertions to detect a
 * cloned authenticator); {@code backupState} reflects whether the passkey is currently synced.
 */
public record AssertionResult(long signCount, boolean userVerified, boolean backupState) {}
