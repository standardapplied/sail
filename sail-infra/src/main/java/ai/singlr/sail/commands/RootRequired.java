/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.Sail;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.OperatorCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * The refusal of a verb only root runs, naming the caller's own command to run again: every word
 * they typed, shell-quoted, with {@code sudo} in front, so the re-run does what they asked.
 */
public final class RootRequired {

  private static final Set<String> SECRETS = Set.of("--token", "--git-token");
  private static final String TOKEN_PLACEHOLDER = "<token>";

  private RootRequired() {}

  /** "Root privileges required." and the caller's command, re-run with sudo. */
  static CliCommand.Refused refusal(CommandSpec spec) {
    return refusal("Root privileges required.", spec);
  }

  /** {@code what} needs root, and the caller's command, re-run with sudo. */
  static CliCommand.Refused refusal(String what, CommandSpec spec) {
    return new CliCommand.Refused(
        what + " Run with: " + OperatorCommand.SUDO.root(typed(spec).line()));
  }

  /**
   * {@code spec}'s command as its caller typed it: every word after {@code sail}, shell-quoted, for
   * the door that prints it to render for its reader. A token's value ({@code --token}, {@code
   * --git-token}) is never echoed: a printed failure reaches logs and pasted reports the command
   * line never did.
   */
  static Remedy.Command typed(CommandSpec spec) {
    var words = new ArrayList<String>();
    var secret = false;
    for (var arg : spec.commandLine().getParseResult().expandedArgs()) {
      var assigned = arg.indexOf('=');
      if (secret) {
        words.add(TOKEN_PLACEHOLDER);
      } else if (assigned > 0 && SECRETS.contains(arg.substring(0, assigned))) {
        words.add(arg.substring(0, assigned + 1) + TOKEN_PLACEHOLDER);
      } else {
        words.add(Strings.shellWord(arg));
      }
      secret = !secret && SECRETS.contains(arg);
    }
    return Remedy.sail(String.join(" ", verbOf(spec)), words.toArray(String[]::new));
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
