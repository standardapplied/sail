/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.identity.Actor;
import java.util.Map;
import java.util.function.Supplier;
import picocli.CommandLine;

/**
 * The one way a {@code sail} command line is built, so every host command reads as this box's
 * person: the operator its configuration and roster name ({@link CliOperator}), resolved at the
 * command's first read and never for a command that reads nothing. Only the commands {@link
 * #AS_THE_BOX} names run as the box itself, each for the reason it gives.
 */
public final class SailCommandLine {

  /**
   * The commands that read as this box itself, not its person, and why: each makes a read that is
   * the box's, not one person's. A command that reads nothing scoped needs no entry, since the
   * person is resolved only at a first read.
   */
  static final Map<Class<?>, String> AS_THE_BOX =
      Map.of(
          ServerStartCommand.class,
          "the control plane, whose startup reads on a box that may not have placed its person",
          SyncCommand.class,
          "the box's own sync, which places its person, so it cannot wait on one",
          SyncCommand.Status.class,
          "the box's sync health, every conflict it holds counted",
          MigrateCommand.class,
          "the box's own schema, converted for every project",
          UpgradeCommand.class,
          "the box's own binary and schema, for every project");

  private SailCommandLine() {}

  /**
   * {@code root}'s command line, its every command but {@link #AS_THE_BOX} read as the operator.
   */
  public static CommandLine of(Object root) {
    return of(root, SailCommandLine::operator);
  }

  /** As {@link #of(Object)}, with the operator {@code operator} resolves. */
  static CommandLine of(Object root, Supplier<Actor> operator) {
    var line = new CommandLine(root);
    line.setExecutionStrategy(parsed -> execute(parsed, operator));
    return line;
  }

  /** Whether {@code command} reads as the box itself, not its person ({@link #AS_THE_BOX}). */
  static boolean readsAsTheBox(Object command) {
    return AS_THE_BOX.containsKey(command.getClass());
  }

  private static int execute(CommandLine.ParseResult parsed, Supplier<Actor> operator) {
    var run = new CommandLine.RunLast();
    if (readsAsTheBox(leaf(parsed).commandSpec().userObject())) {
      return run.execute(parsed);
    }
    return Actor.readingAs(once(operator), () -> run.execute(parsed));
  }

  private static CommandLine.ParseResult leaf(CommandLine.ParseResult parsed) {
    var leaf = parsed;
    while (leaf.hasSubcommand()) {
      leaf = leaf.subcommand();
    }
    return leaf;
  }

  private static Supplier<Actor> once(Supplier<Actor> resolve) {
    return new Supplier<>() {
      private Actor resolved;

      @Override
      public synchronized Actor get() {
        if (resolved == null) {
          resolved = resolve.get();
        }
        return resolved;
      }
    };
  }

  /** This box's operator, as its operations name it: the one resolver of the host CLI's person. */
  static Actor operator() {
    return operator(OperationsFactory::open);
  }

  /** The operator the operations {@code open} opens name, closed once read. */
  static Actor operator(Supplier<HostOperations> open) {
    try (var operations = open.get()) {
      return operations.identity().operator();
    }
  }
}
