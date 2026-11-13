/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * A container's answer to {@link RunUsageReader}'s command, as its script gives it over a file: for
 * each marker in turn, the last lines holding it, newest first; nothing, with tac's word on stderr,
 * when the file is not there; exit 0 either way.
 */
public final class ReportLines {

  private ReportLines() {}

  /** Whether {@code command} is the reader's. */
  public static boolean asks(List<String> command) {
    return command.contains(RunUsageReader.LAST_MARKED);
  }

  /** The answer to {@code command} over {@code file}, which is not there when null. */
  public static ShellExec.Result answer(String file, List<String> command) {
    var at = command.indexOf(RunUsageReader.LAST_MARKED);
    var path = command.get(at + 2);
    if (file == null) {
      return new ShellExec.Result(
          0, "", "tac: failed to open '" + path + "' for reading: No such file or directory\n");
    }
    var lines = Integer.parseInt(command.get(at + 3));
    var marked = new ArrayList<String>();
    for (var marker : command.subList(at + 4, command.size())) {
      file.lines().filter(line -> line.contains(marker)).toList().reversed().stream()
          .limit(lines)
          .forEach(marked::add);
    }
    return new ShellExec.Result(0, marked.isEmpty() ? "" : String.join("\n", marked) + "\n", "");
  }
}
