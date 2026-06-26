/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

/**
 * A file to be pushed into the project container. Used by generators that produce files without
 * performing I/O themselves — callers handle the actual push.
 *
 * @param remotePath absolute path inside the container
 * @param content file content
 * @param executable whether the file should be marked executable
 * @param ownership who owns the file, and therefore how the installer treats it on regeneration
 */
public record GeneratedFile(
    String remotePath, String content, boolean executable, Ownership ownership) {

  /** Who owns an installed file, which decides whether the installer overwrites it. */
  public enum Ownership {
    /** Sail owns it: regenerated and overwritten on every run (the core, the shipped skills). */
    SAIL,

    /**
     * The engineer owns it: sail writes it once when it is absent (or on {@code --force}) and never
     * touches it again — it is an ordinary file the engineer is free to edit and share.
     */
    ENGINEER
  }

  /** A sail-owned file, overwritten on every run. */
  public GeneratedFile(String remotePath, String content, boolean executable) {
    this(remotePath, content, executable, Ownership.SAIL);
  }

  /**
   * An engineer-owned file: sail writes it once when absent (or on {@code --force}) and otherwise
   * leaves it untouched. Never executable.
   */
  public static GeneratedFile engineerOwned(String remotePath, String content) {
    return new GeneratedFile(remotePath, content, false, Ownership.ENGINEER);
  }
}
