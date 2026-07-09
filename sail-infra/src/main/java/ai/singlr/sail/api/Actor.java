/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

/**
 * The principal a dispatch runs on behalf of, built once per boundary and handed to {@link
 * DispatchPolicy}. {@code handle} is the caller's FDE handle (the {@code assignee} a spec is
 * matched against); it is null for a machine/CI credential that owns no FDE. {@code role} carries
 * the caller's capabilities. {@code lane} records which surface built the actor — the in-process
 * CLI or the HTTP API — so refusals and telemetry can name the path without re-deriving it.
 *
 * <p>Fails closed: a machine token yields a {@code null} handle that matches no assignee, and an
 * unknown role resolves (via {@link Role#fromAttribute}) to the least-privileged {@link
 * Role#VIEWER}.
 */
public record Actor(String handle, Role role, Lane lane) {

  /** Which surface constructed the actor. */
  public enum Lane {
    CLI,
    API
  }

  /**
   * The local operator running {@code sail spec dispatch} in-process: the box's own FDE handle with
   * effective admin authority (the host operator already holds the admin token).
   */
  public static Actor cliOperator(String handle) {
    return new Actor(handle, Role.ADMIN, Lane.CLI);
  }

  /** True when this actor's role grants full administrative authority. */
  public boolean isAdmin() {
    return role == Role.ADMIN;
  }

  /** True when this actor's role grants the {@code WRITE} capability dispatch requires. */
  public boolean canWrite() {
    return role.allows(Capability.WRITE);
  }
}
