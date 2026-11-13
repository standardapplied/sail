/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Every host command reads as this box's person, or says why it runs as the box: the one command
 * line {@link SailCommandLine} builds is the only way a command runs, so a command added to the
 * tree reads as the operator unless it is named in {@link SailCommandLine#AS_THE_BOX}.
 */
class SailCommandLineTest {

  private static final Actor ADA =
      new Actor("ada", Role.MEMBER, Actor.Lane.CLI).withScope(ProjectScope.of(List.of("alpha")));

  @Test
  void theCommandsThatReadAsTheBoxAreExactlyTheseOfTheTree() {
    var tree = new TreeSet<String>();
    var asTheBox = new TreeSet<String>();
    walk(SailCommandLine.of(new Sail()), "sail", tree, asTheBox);

    assertEquals(
        Set.of(
            "sail migrate", "sail server start", "sail sync", "sail sync status", "sail upgrade"),
        asTheBox);
    assertEquals(SailCommandLine.AS_THE_BOX.size(), asTheBox.size(), "no stale entry");
    assertTrue(tree.contains("sail project config get"), "the walk reaches every leaf");
  }

  @Test
  void noCommandLineIsBuiltButBySailCommandLine() throws Exception {
    var root =
        Path.of(Sail.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("ai/singlr/sail");
    var built = new ArrayList<String>();
    try (var classes = Files.walk(root)) {
      for (var path : classes.filter(p -> p.toString().endsWith(".class")).toList()) {
        built.addAll(buildsACommandLine(Files.readAllBytes(path)));
      }
    }

    assertEquals(List.of("ai/singlr/sail/commands/SailCommandLine"), built);
  }

  @Test
  void theGuardSeesACommandLineBuiltBesideTheSeam() throws IOException {
    try (var bytes = Bypass.class.getResourceAsStream("SailCommandLineTest$Bypass.class")) {
      assertEquals(
          List.of("ai/singlr/sail/commands/SailCommandLineTest$Bypass"),
          buildsACommandLine(bytes.readAllBytes()));
    }
  }

  @Test
  void theGuardSeesACommandRunByPicocliItself() throws IOException {
    try (var bytes =
        BypassByRun.class.getResourceAsStream("SailCommandLineTest$BypassByRun.class")) {
      assertEquals(
          List.of("ai/singlr/sail/commands/SailCommandLineTest$BypassByRun"),
          buildsACommandLine(bytes.readAllBytes()));
    }
  }

  @Test
  void aCommandReadsAsTheOperatorResolvedOnceAtItsFirstReadAndWritesAsNoOneUnbound() {
    var asked = new AtomicInteger();
    var line =
        SailCommandLine.of(
            new Probe(),
            () -> {
              asked.incrementAndGet();
              return ADA;
            });

    var exit = line.execute("reads");
    Probe.Reads reads = line.getSubcommands().get("reads").getCommand();

    assertEquals(0, exit);
    assertEquals(List.of(ADA, ADA), reads.read);
    assertEquals(1, asked.get());
    assertEquals(
        "No actor is bound: every write must name who is acting. Bind one with Actor.run or"
            + " Actor.call at the entry point that started this work.",
        reads.unbound.getMessage());
  }

  @Test
  void theOperatorIsTheBoxsPersonWithTheScopeTheRosterGivesThem(@TempDir Path dir) {
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var roster = new FdeStore(db);
      roster.add("mady", null, null, "member");
      roster.restrict("mady");
      roster.grant("mady", "alpha");
      Supplier<HostOperations> node =
          () ->
              OperationsFactory.create(
                      db,
                      new ScriptedShellExecutor(),
                      null,
                      SyncScheduler.disabled(),
                      SessionYield.NONE)
                  .useControlPlane(
                      db,
                      dir,
                      new SyncOperations(
                          db,
                          "box",
                          dir,
                          () -> new SyncConfig("node", "sail@main", "mady", "box"),
                          target -> {
                            throw new IOException("main is not asked here");
                          },
                          SyncOperations.PLAIN_NOTICES));

      assertEquals(
          new Actor("mady", Role.MEMBER, Actor.Lane.CLI)
              .withScope(ProjectScope.of(List.of("alpha"))),
          SailCommandLine.operator(node));
    }
  }

  @Test
  void aCommandThatReadsNothingNeverPlacesTheOperator() {
    var exit =
        SailCommandLine.of(
                new Probe(),
                () -> {
                  throw new AssertionError("a command that reads nothing places no one");
                })
            .execute("quiet");

    assertEquals(0, exit);
  }

  @Test
  void aSubcommandNamedAsTheBoxsOwnReadsAsTheBoxAndPlacesNoOne() {
    var read = new ArrayList<Actor>();
    var line =
        SailCommandLine.of(
            new Probe(),
            () -> {
              throw new AssertionError("the box's own machinery places no one");
            });
    line.addSubcommand(
        "status",
        new SyncCommand.Status(
            operator -> {
              read.add(Actor.reader());
              throw new IllegalStateException("read once");
            }));
    line.setExecutionExceptionHandler((failure, cli, parsed) -> 1);

    line.execute("status");

    assertEquals(List.of(Actor.system()), read);
  }

  private static void walk(CommandLine line, String path, Set<String> tree, Set<String> asTheBox) {
    tree.add(path);
    if (SailCommandLine.AS_THE_BOX.containsKey(line.getCommand().getClass())) {
      asTheBox.add(path);
    }
    line.getSubcommands()
        .forEach(
            (name, sub) -> {
              if (sub.getCommandName().equals(name)) {
                walk(sub, path + " " + name, tree, asTheBox);
              }
            });
  }

  private static List<String> buildsACommandLine(byte[] bytes) {
    var model = ClassFile.of().parse(bytes);
    var built = new ArrayList<String>();
    for (var entry : model.constantPool()) {
      if (entry instanceof MemberRefEntry member
          && member.owner().asInternalName().equals("picocli/CommandLine")
          && Set.of("<init>", "run", "call").contains(member.name().stringValue())
          && !built.contains(model.thisClass().asInternalName())) {
        built.add(model.thisClass().asInternalName());
      }
    }
    return List.copyOf(built);
  }

  @Command(
      name = "probe",
      subcommands = {Probe.Reads.class, Probe.Quiet.class})
  static final class Probe implements Runnable {
    @Override
    public void run() {}

    @Command(name = "reads")
    static final class Reads implements Runnable {
      final List<Actor> read = new ArrayList<>();
      IllegalStateException unbound;

      @Override
      public void run() {
        read.add(Actor.reader());
        read.add(Actor.reader());
        unbound = assertThrows(IllegalStateException.class, Actor::current);
      }
    }

    @Command(name = "quiet")
    static final class Quiet implements Runnable {
      @Override
      public void run() {}
    }
  }

  private static final class Bypass {
    CommandLine line() {
      return new CommandLine(new Sail());
    }
  }

  private static final class BypassByRun {
    void run() {
      CommandLine.run(new Sail(), "--version");
    }
  }
}
