/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Option;

/**
 * The {@code -f/--file} the agent and dispatch commands took while a project was read from its
 * descriptor file. Declared once so a script, and a watcher a 0.46.3 server spawned, still starts
 * with it, and read by nothing: the project is read from the catalog.
 */
public final class IgnoredFileOption {

  @Option(
      names = {"-f", "--file"},
      description = "Ignored: the project is read from the catalog.")
  private String file;
}
