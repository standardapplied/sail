/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

/**
 * Models the possible states of an Incus container, queried live from {@code incus list --format
 * json}. Used by {@link ContainerManager} and all lifecycle commands.
 */
public sealed interface ContainerState {

  /** Whether its processes exist, running or frozen, so stopping it ends them. */
  default boolean live() {
    return this instanceof Running || this instanceof Frozen;
  }

  /** Container does not exist — {@code incus list} returned an empty array. */
  record NotCreated() implements ContainerState {}

  /** Container is running. {@code ipv4} may be null if no address is assigned yet. */
  record Running(String ipv4) implements ContainerState {}

  /** Container exists but is stopped. */
  record Stopped() implements ContainerState {}

  /**
   * Container is paused ({@code incus pause}): its processes and memory are kept, none of them is
   * scheduled, and Incus refuses to exec in it. {@code incus start} resumes it.
   */
  record Frozen() implements ContainerState {}

  /** Container is in a state Incus did not name as one of these (e.g. Error, or mid-change). */
  record Error(String message) implements ContainerState {}
}
