/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.List;

/**
 * A container's answer to {@link RunUsageReader}'s command, as {@code tac} piped to {@code grep -m
 * <lines> -F -e <markers>} gives it over a file: the last lines holding a marker, newest first;
 * grep's exit 1 with nothing said when none does; tac's own word when the file is not there.
 */
public final class ReportLines {

  private ReportLines() {}

  /** The answer to {@code command} over {@code file}, which is not there when null. */
  public static ShellExec.Result answer(String file, List<String> command) {
    if (file == null) {
      return new ShellExec.Result(
          1,
          "",
          "tac: failed to open '"
              + command.get(command.size() - 3)
              + "' for reading: No such file or directory\n");
    }
    var markers = command.getLast().lines().toList();
    var marked =
        file
            .lines()
            .filter(line -> markers.stream().anyMatch(line::contains))
            .toList()
            .reversed()
            .stream()
            .limit(Integer.parseInt(command.get(command.size() - 2)))
            .toList();
    return marked.isEmpty()
        ? new ShellExec.Result(1, "", "")
        : new ShellExec.Result(0, String.join("\n", marked) + "\n", "");
  }
}
