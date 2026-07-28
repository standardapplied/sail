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
 * CLI, the HTTP API, or an in-container agent run — so refusals and telemetry can name the path
 * without re-deriving it. {@code owner} is set only on the agent lane: the FDE the run's principal
 * acts for, used for attribution and policy tiering, never as a separate authorization system.
 *
 * <p>Fails closed: a machine token yields a {@code null} handle that matches no assignee, and an
 * unknown role resolves (via {@link Role#fromAttribute}) to the least-privileged {@link
 * Role#VIEWER}.
 */
public record Actor(String handle, Role role, Lane lane, String owner) {

  /** Which surface constructed the actor. */
  public enum Lane {
    CLI,
    API,
    AGENT
  }

  public Actor(String handle, Role role, Lane lane) {
    this(handle, role, lane, null);
  }

  /**
   * The local operator running {@code sail spec dispatch} in-process: the box's own FDE handle with
   * effective admin authority (the host operator already holds the admin token).
   */
  public static Actor cliOperator(String handle) {
    return new Actor(handle, Role.ADMIN, Lane.CLI);
  }

  /**
   * A run's agent principal authenticated over the local socket: write-capable on the spec, event,
   * and content surface, never admin, and refused outright on the dispatch and stop routes.
   */
  public static Actor agentPrincipal(String handle, String owner) {
    return new Actor(handle, Role.MEMBER, Lane.AGENT, owner);
  }

  /** True when this actor's role grants full administrative authority. */
  public boolean isAdmin() {
    return role == Role.ADMIN;
  }

  /** True when this actor's role grants the {@code WRITE} capability dispatch requires. */
  public boolean canWrite() {
    return role.allows(Capability.WRITE);
  }

  /** True when this actor was built from a run credential on the in-container agent lane. */
  public boolean agentLane() {
    return lane == Lane.AGENT;
  }

  /**
   * Whether this actor is {@code identity} or acts on its behalf: an agent principal carries its
   * owning FDE, so a spec assigned to that FDE is the agent's to work exactly as if the FDE edited
   * it directly.
   */
  public boolean actsFor(String identity) {
    return identity != null && (identity.equals(handle) || identity.equals(owner));
  }
}
