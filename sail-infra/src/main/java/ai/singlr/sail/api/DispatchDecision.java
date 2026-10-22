/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;

/**
 * The outcome of {@link DispatchPolicy#check}: either {@link Allowed} or a {@link Refused} carrying
 * the structured {@link ErrorCode} a GUI client renders verbatim, a human-readable reason, and the
 * concrete fixing action. Never-silent: a refusal always names both why it happened and how to
 * unblock it.
 */
public sealed interface DispatchDecision
    permits DispatchDecision.Allowed, DispatchDecision.Refused {

  record Allowed() implements DispatchDecision {}

  record Refused(ErrorCode code, String message, Remedy fix) implements DispatchDecision {}
}
