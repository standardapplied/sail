/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Option;

/**
 * The shared {@code --no-sync} opt-out for spec commands. Mixed into a command with {@code @Mixin}
 * so the flag that skips the automatic pre-command sync with main is declared once rather than
 * copied into a dozen commands.
 */
public final class SyncOptions {

  @Option(
      names = "--no-sync",
      description =
          "Skip the automatic sync with main before this command; act on the local replica as-is.")
  private boolean noSync;

  public boolean noSync() {
    return noSync;
  }
}
