/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.identity.Actor;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
   * The commands that read as this box itself, not its person, and why: each is the box's own
   * machinery or a door that binds the caller it serves.
   */
  static final Map<Class<?>, String> AS_THE_BOX =
      Map.ofEntries(
          Map.entry(
              ServerStartCommand.class, "the control plane, which binds each request's caller"),
          Map.entry(
              GatewayCommand.class, "the SSH gateway, which binds the person whose key it is"),
          Map.entry(
              PtyHostCommand.class, "the terminal host, which binds each connection's person"),
          Map.entry(PtyChildCommand.class, "a terminal session's process, which the host admitted"),
          Map.entry(
              SyncServerCommand.class, "main's end of sync, which binds the session's person"),
          Map.entry(SyncCommand.class, "the box's own sync, which is what places an unplaced box"),
          Map.entry(
              SyncCommand.Status.class, "the box's own sync health, read before it is placed"),
          Map.entry(SyncCommand.Gc.class, "the box's own journal, collected for every project"),
          Map.entry(HostKeysCommand.Sync.class, "the gateway's keys for every FDE the box knows"),
          Map.entry(JoinCommand.class, "makes the box a node; it places its person at first sync"),
          Map.entry(InitCommand.class, "sets the box up before it has a person to place"),
          Map.entry(HostSyncCommand.class, "names the box's FDE, before which it has no person"),
          Map.entry(MigrateCommand.class, "the box's own schema, converted for every project"),
          Map.entry(UpgradeCommand.class, "the box's own binary and schema, for every project"));

  private SailCommandLine() {}

  /**
   * {@code root}'s command line, its every command but {@link #AS_THE_BOX} read as the operator.
   */
  public static CommandLine of(Object root) {
    return of(root, SailCommandLine::operator, AS_THE_BOX.keySet());
  }

  /**
   * As {@link #of(Object)}, with the operator {@code operator} resolves, and the commands {@code
   * asTheBox} names reading as the box.
   */
  static CommandLine of(Object root, Supplier<Actor> operator, Set<Class<?>> asTheBox) {
    var line = new CommandLine(root);
    line.setExecutionStrategy(parsed -> execute(parsed, operator, asTheBox));
    return line;
  }

  private static int execute(
      CommandLine.ParseResult parsed, Supplier<Actor> operator, Set<Class<?>> asTheBox) {
    var run = new CommandLine.RunLast();
    if (asTheBox.contains(leaf(parsed).commandSpec().userObject().getClass())) {
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
    var resolved = new AtomicReference<Actor>();
    return () -> resolved.updateAndGet(held -> Objects.requireNonNullElseGet(held, resolve));
  }

  /** This box's operator, as its operations name it: the one resolver of the host CLI's person. */
  static Actor operator() {
    try (var operations = OperationsFactory.open()) {
      return operations.identity().operator();
    }
  }
}
