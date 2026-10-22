/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.Sail;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.OperatorCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * The refusal of a verb only root runs, naming the caller's own command to run again: every word
 * they typed, shell-quoted, with {@code sudo} in front, so the re-run does what they asked.
 */
public final class RootRequired {

  private RootRequired() {}

  /** "Root privileges required." and the caller's command, re-run with sudo. */
  static IllegalStateException refusal(CommandSpec spec) {
    return refusal("Root privileges required.", spec);
  }

  /** {@code what} needs root, and the caller's command, re-run with sudo. */
  static IllegalStateException refusal(String what, CommandSpec spec) {
    var words = new ArrayList<>(verbOf(spec));
    words.addAll(spec.commandLine().getParseResult().expandedArgs());
    return new IllegalStateException(what + " Run with: " + rerun(words));
  }

  /** {@code args}, the words after {@code sail}, as root runs them for a caller who is not root. */
  public static String rerun(List<String> args) {
    return OperatorCommand.SUDO
        .root(args.stream().map(Strings::shellWord).collect(Collectors.joining(" ")))
        .strip();
  }

  /** The words naming {@code spec}'s command under {@code sail}, however it was invoked. */
  private static List<String> verbOf(CommandSpec spec) {
    var type = spec.userObject().getClass();
    return pathTo(new CommandLine(new Sail()), type, List.of())
        .orElseThrow(() -> new IllegalStateException(type.getName() + " is no command of sail."));
  }

  private static Optional<List<String>> pathTo(
      CommandLine command, Class<?> type, List<String> path) {
    for (var sub : command.getSubcommands().values()) {
      var next = new ArrayList<>(path);
      next.add(sub.getCommandName());
      if (sub.getCommandSpec().userObject().getClass() == type) {
        return Optional.of(List.copyOf(next));
      }
      var found = pathTo(sub, type, next);
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }
}
