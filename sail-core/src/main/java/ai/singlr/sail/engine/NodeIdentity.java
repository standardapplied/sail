/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This box's identity in the db-sync star, read from {@code host.yaml}: its sync role and, above
 * all, its FDE {@code handle} — the {@code assignee} a spec must carry to execute here. A single
 * reader shared by the CLI dispatch lane and the API server, so both agree on which specs this node
 * owns. An unconfigured or unreadable {@code host.yaml} yields {@link SyncConfig#unset()} — fails
 * closed to a null handle that matches no assignee.
 */
public final class NodeIdentity {

  private NodeIdentity() {}

  /** This box's declared sync configuration, or {@link SyncConfig#unset()} when none is set. */
  public static SyncConfig config() {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) {
      return SyncConfig.unset();
    }
    try {
      return HostYaml.fromMap(YamlUtil.parseFile(path)).sync();
    } catch (IOException e) {
      return SyncConfig.unset();
    }
  }

  /**
   * Whether this box authors erasures: main, or a box that syncs with nobody. A {@code host.yaml}
   * that cannot be read answers no — a node mistaken for main would erase what it merely has not
   * pulled yet.
   */
  public static boolean authoritative() {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) {
      return true;
    }
    try {
      return !HostYaml.fromMap(YamlUtil.parseFile(path)).sync().isNode();
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /**
   * Whether {@code host.yaml}, read whole, declares this box main or standalone: the reading on
   * which a box forgets the scope main last named it ({@code BoxDatabase}). Unlike {@link
   * #authoritative()}, a file absent, empty or unreadable answers no — one being moved into the
   * shared data directory or truncated by a writer would otherwise pass for a standalone box's, and
   * a node would lose its grant until its next round.
   */
  public static boolean declaresNotNode() {
    return declaresNotNode(SailPaths.hostConfigPath());
  }

  static boolean declaresNotNode(Path hostYaml) {
    try {
      var declared = YamlUtil.parseFile(hostYaml);
      return !declared.isEmpty() && !HostYaml.fromMap(declared).sync().isNode();
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /**
   * Whether this box narrates its own work to Slack: main or standalone. A node never posts — its
   * transitions sync to main, whose {@code _sync} process turns them into the lifecycle events
   * main's reactor announces — so there is exactly one notifier per fleet. A box with no {@code
   * host.yaml} was never made a node, so it narrates; one whose file is empty or unreadable —
   * caught mid-write — posts nothing until the file reads whole ({@link #declaresNotNode}).
   */
  public static boolean narrates() {
    return narrates(SailPaths.hostConfigPath());
  }

  static boolean narrates(Path hostYaml) {
    return !Files.exists(hostYaml) || declaresNotNode(hostYaml);
  }

  /** This box's FDE handle — the assignee dispatch matches a spec against — or null when unset. */
  public static String handle() {
    return config().handle();
  }
}
