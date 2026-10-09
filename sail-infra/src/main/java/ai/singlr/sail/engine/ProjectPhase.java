/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

/**
 * Ordered phases of project container provisioning. Each value represents a completed milestone.
 * The {@link ProvisionTracker} resumes from the phase after the last completed one.
 *
 * <p>Ordering contract: enum ordinal determines execution order.
 */
public enum ProjectPhase {
  /** Container launched via {@code incus launch}. */
  CONTAINER_LAUNCHED,
  /** ZFS refquota set on the container's root dataset. */
  DISK_QUOTA_SET,
  /** CPU and memory limits applied via {@code incus config}. */
  RESOURCE_LIMITS_SET,
  /** Container has a routable IPv4 address (network is up). */
  NETWORK_READY,
  /** System packages installed via {@code apt-get}. */
  PACKAGES_INSTALLED,
  /** SSH user created, authorized_keys written, sshd enabled. */
  SSH_CONFIGURED,
  /** Podman installed, linger enabled, socket activated. */
  PODMAN_INSTALLED,
  /** Testcontainers environment variables configured for the dev user. */
  TESTCONTAINERS_CONFIGURED,
  /** JDK installed at the version specified in sail.yaml runtimes. */
  JDK_INSTALLED,
  /** Node.js installed at the version specified in sail.yaml runtimes. */
  NODE_INSTALLED,
  /** Git configured with user name, email, known hosts, and optional credentials. */
  GIT_CONFIGURED,
  /** Maven installed at the version specified in sail.yaml runtimes. */
  MAVEN_INSTALLED,
  /** Source repositories cloned into ~/workspace/. */
  REPOS_CLONED,
  /** Workspace files from the local {@code files/} directory pushed into ~/workspace/. */
  WORKSPACE_FILES_PUSHED,
  /** Infrastructure services provisioned as rootless Podman containers. */
  SERVICES_PROVISIONED,
  /** Stale Testcontainers pruning configured via cron. */
  PRUNE_CRON_CONFIGURED,
  /** Agent CLI tools (claude, codex) installed inside the container. */
  AGENT_TOOLS_INSTALLED,
  /**
   * The project's skills and sail's spec-board installed for every harness the project installs.
   */
  SKILLS_INSTALLED,
  /** Specs scaffold directory created inside workspace. */
  SPECS_SCAFFOLD_CREATED,
  /** Project state written inside container. Provisioning complete. */
  COMPLETE,
}
