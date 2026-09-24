/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The engineer's editor, opened on a file. {@code $EDITOR} is read as git reads it: a shell
 * command, so {@code code --wait} works, with the file passed as its argument and never spliced
 * into the command. Unset or blank means {@code vi}.
 */
@FunctionalInterface
interface Editor {

  /** Opens {@code file} and answers the editor's exit status once it closes. */
  int edit(Path file) throws IOException, InterruptedException;

  static Editor fromEnvironment() {
    return command(System.getenv("EDITOR"));
  }

  static Editor command(String command) {
    var script = script(command);
    return file ->
        new ProcessBuilder("/bin/sh", "-c", script, "sh", file.toString())
            .inheritIO()
            .start()
            .waitFor();
  }

  /** The shell script that runs {@code command} on the file given as {@code $1}. */
  static String script(String command) {
    return (Strings.isBlank(command) ? "vi" : command.strip()) + " \"$1\"";
  }
}
