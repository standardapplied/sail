/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.Resolution;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.ConflictMerge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Lists and resolves the sync conflicts parked on this box, across every synced entity type — specs
 * and shared project files alike. A conflict is opened only when a remote change clashes with a
 * local edit on the same field (or a delete races an edit); the local row is never touched while it
 * is open. {@code resolve} rebases the row onto main's version and writes the chosen value, so a
 * follow-up {@code sail sync} converges and the conflict cannot re-raise — and every version stays
 * in the change log, so no choice is destructive. File content is an opaque blob, so a file
 * conflict offers only {@code --mine}/{@code --theirs}, never a field-level {@code --merge}.
 */
@Command(
    name = "conflicts",
    description = "List and resolve sync conflicts.",
    mixinStandardHelpOptions = true,
    subcommands = {ConflictsCommand.Show.class, ConflictsCommand.Resolve.class})
public final class ConflictsCommand implements Callable<Integer> {

  static final String FILE = "file";

  @Option(names = "--json", description = "Output the pending conflicts as JSON.")
  private boolean json;

  private final Supplier<HostOperations> operations;

  public ConflictsCommand() {
    this(OperationsFactory::open);
  }

  ConflictsCommand(Supplier<HostOperations> operations) {
    this.operations = operations;
  }

  /** Which conflict a verb acts on: ids are unique only within a type. */
  static final class Address {

    @Parameters(index = "0", description = "Entity id of the conflict.")
    private String entity;

    @Option(
        names = "--type",
        description = "Entity type (spec, room, file, ...), when the id is parked under several.")
    private String type;
  }

  @Override
  public Integer call() {
    try (var operations = this.operations.get()) {
      var pending = operations.conflicts();
      System.out.println(renderList(pending, json));
      return 0;
    }
  }

  static String renderList(List<SyncConflicts.Conflict> pending, boolean json) {
    if (json) {
      var rows =
          pending.stream()
              .map(
                  c -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("type", c.entityType());
                    row.put("entity", c.entityId());
                    row.put("fields", c.fields());
                    row.put("detected_at", c.detectedAt());
                    return (Object) row;
                  })
              .toList();
      return YamlUtil.dumpJson(rows);
    }
    if (pending.isEmpty()) {
      return Ansi.AUTO.string("  @|green ✓|@ No conflicts.");
    }
    var out = new StringBuilder();
    out.append(
        Ansi.AUTO.string("  @|bold " + pending.size() + "|@ conflict(s) need your decision:\n"));
    for (var c : pending) {
      out.append(
          Ansi.AUTO.string(
              "    @|faint "
                  + c.entityType()
                  + "|@ @|yellow "
                  + c.entityId()
                  + "|@ — fields: "
                  + String.join(", ", c.fields())
                  + " @|faint ("
                  + c.detectedAt()
                  + ")|@\n"));
    }
    out.append(Ansi.AUTO.string("  Run @|bold sail conflicts show <id>|@ to inspect, "));
    out.append(Ansi.AUTO.string("@|bold sail conflicts resolve <id> --mine|--theirs|--merge|@."));
    return out.toString();
  }

  @Command(
      name = "show",
      description = "Show the field-level diff of a conflict.",
      mixinStandardHelpOptions = true)
  static final class Show implements Callable<Integer> {

    @Mixin private Address address;

    @Option(
        names = "--template",
        description = "Print the record to edit and pass to 'resolve --merge-file' (specs only).")
    private boolean template;

    private final Supplier<HostOperations> operations;

    Show() {
      this(OperationsFactory::open);
    }

    Show(Supplier<HostOperations> operations) {
      this.operations = operations;
    }

    @Override
    public Integer call() {
      try (var operations = this.operations.get()) {
        if (template) {
          System.out.print(operations.conflictMergeTemplate(address.type, address.entity));
          return 0;
        }
        var conflict = operations.conflict(address.type, address.entity);
        if (conflict == null) {
          System.err.println(
              Banner.errorLine("No open conflict for '" + address.entity + "'.", Ansi.AUTO));
          return 1;
        }
        System.out.println(render(conflict));
        return 0;
      }
    }

    static String render(SyncConflicts.Conflict conflict) {
      var isFile = conflict.entityType().equals(FILE);
      var diff =
          ConflictMerge.diff(
              parse(conflict.baseSnapshot()),
              parse(conflict.localSnapshot()),
              parse(conflict.remoteSnapshot()),
              conflict.fields());
      var out = new StringBuilder();
      out.append(
          Ansi.AUTO.string(
              "  Conflict on @|faint "
                  + conflict.entityType()
                  + "|@ @|yellow "
                  + conflict.entityId()
                  + "|@:\n"));
      for (var change : diff) {
        var marker =
            change.clash() ? Ansi.AUTO.string("@|red ✗|@") : Ansi.AUTO.string("@|green ·|@");
        out.append("    ").append(marker).append(' ').append(change.field()).append('\n');
        out.append("        base:   ").append(show(change.base(), isFile)).append('\n');
        out.append("        mine:   ").append(show(change.mine(), isFile)).append('\n');
        out.append("        theirs: ").append(show(change.theirs(), isFile)).append('\n');
      }
      return out.toString().stripTrailing();
    }

    static String show(Object value, boolean isFile) {
      var text = ConflictMerge.render(value);
      return isFile && text.contains("\n") ? "\n" + text.stripTrailing() : text;
    }
  }

  @Command(
      name = "resolve",
      description = "Resolve a conflict: --mine, --theirs, or --merge (specs only).",
      mixinStandardHelpOptions = true)
  static final class Resolve implements Callable<Integer> {

    /** Opens a file for the engineer to edit and answers the editor's exit status. */
    @FunctionalInterface
    interface Editor {
      int edit(Path file) throws IOException, InterruptedException;

      static Editor command(String command) {
        return file -> new ProcessBuilder(command, file.toString()).inheritIO().start().waitFor();
      }
    }

    @Mixin private Address address;

    private final Supplier<HostOperations> operations;
    private final Editor editor;

    Resolve() {
      this(OperationsFactory::open, Editor.command(System.getenv().getOrDefault("EDITOR", "vi")));
    }

    Resolve(Supplier<HostOperations> operations, Editor editor) {
      this.operations = operations;
      this.editor = editor;
    }

    @Option(names = "--mine", description = "Keep this box's version.")
    private boolean mine;

    @Option(names = "--theirs", description = "Adopt main's version (yours stays in history).")
    private boolean theirs;

    @Option(names = "--merge", description = "Merge in $EDITOR, pre-filled with a 3-way merge.")
    private boolean merge;

    @Option(
        names = "--merge-file",
        description = "Read the merged record from a file made by 'show --template' (no editor).")
    private Path mergeFile;

    @Override
    public Integer call() throws Exception {
      var strategy = strategy(mine, theirs, merge || mergeFile != null);
      if (strategy == null) {
        System.err.println(
            Banner.errorLine("Choose exactly one of --mine, --theirs, or --merge.", Ansi.AUTO));
        return 1;
      }
      try (var operations = this.operations.get()) {
        var conflict = operations.conflict(address.type, address.entity);
        if (conflict == null) {
          System.err.println(
              Banner.errorLine("No open conflict for '" + address.entity + "'.", Ansi.AUTO));
          return 1;
        }
        var type = conflict.entityType();
        if (strategy == Resolution.Strategy.MERGE && mergeFile == null) {
          return mergeInEditor(operations, type);
        }
        resolve(operations, type, strategy, mergeFile == null ? null : Files.readString(mergeFile));
        return 0;
      }
    }

    /** The single chosen strategy, or {@code null} unless exactly one was requested. */
    static Resolution.Strategy strategy(boolean mine, boolean theirs, boolean merge) {
      if ((mine ? 1 : 0) + (theirs ? 1 : 0) + (merge ? 1 : 0) != 1) {
        return null;
      }
      if (mine) {
        return Resolution.Strategy.MINE;
      }
      return theirs ? Resolution.Strategy.THEIRS : Resolution.Strategy.MERGE;
    }

    /**
     * Merges in the editor. The template is fetched first, so a conflict that cannot be merged is
     * refused before the editor opens; once the engineer has saved, the file outlives any refusal
     * of the resolve, as reference for the redo.
     */
    private int mergeInEditor(HostOperations operations, String type)
        throws IOException, InterruptedException {
      var template = operations.conflictMergeTemplate(type, address.entity);
      var file = Files.writeString(Files.createTempFile("sail-merge-", ".yaml"), template);
      if (!edited(file)) {
        return 1;
      }
      try {
        resolve(operations, type, Resolution.Strategy.MERGE, Files.readString(file));
      } catch (IOException | RuntimeException e) {
        System.err.println(
            Banner.warnLine("Your merge is kept for reference at " + file + ".", Ansi.AUTO));
        throw e;
      }
      Files.delete(file);
      return 0;
    }

    private boolean edited(Path file) throws IOException, InterruptedException {
      int exit;
      try {
        exit = editor.edit(file);
      } catch (IOException | InterruptedException e) {
        Files.delete(file);
        throw e;
      }
      if (exit != 0) {
        Files.delete(file);
        System.err.println(Banner.errorLine("Editor exited non-zero; aborting.", Ansi.AUTO));
      }
      return exit == 0;
    }

    private void resolve(
        HostOperations operations, String type, Resolution.Strategy strategy, String merged) {
      operations.resolveConflict(type, address.entity, new Resolution(strategy, merged));
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Resolved @|yellow "
                  + address.entity
                  + "|@. Run @|bold sail sync|@ to propagate."));
    }
  }

  static Map<String, Object> parse(String json) {
    return Strings.isBlank(json) ? null : YamlUtil.parseMap(json);
  }
}
