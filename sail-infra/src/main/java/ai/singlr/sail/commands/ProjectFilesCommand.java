/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectFiles;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerFileSource;
import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.engine.FilePicker;
import ai.singlr.sail.engine.FileSource;
import ai.singlr.sail.engine.HostFileSource;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.TerminalFilePicker;
import ai.singlr.sail.engine.WorkspaceFiles;
import ai.singlr.sail.store.FileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Shares arbitrary workspace files across every FDE on a project through the same sync engine as
 * specs — no git, no specific repo. {@code add} stores a file's content hash keyed by its relative
 * path; the next {@code sail sync} replicates it and {@link FileMaterializer} drops it onto every
 * other box. {@code export} runs that materialization on demand, for the main box that never calls
 * {@code sail sync}. Removal tombstones the row so the deletion propagates too.
 */
@Command(
    name = "files",
    description = "Share project files across FDEs (add, ls, cat, rm, pull).",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProjectFilesCommand.Add.class,
      ProjectFilesCommand.Ls.class,
      ProjectFilesCommand.Cat.class,
      ProjectFilesCommand.Rm.class,
      ProjectFilesCommand.Export.class,
    })
public final class ProjectFilesCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(
      name = "add",
      description =
          "Share files with every FDE on the project. Give a file, or omit it to browse and pick.",
      mixinStandardHelpOptions = true)
  static final class Add implements Callable<Integer> {

    /**
     * A bulk share above this many files asks for confirmation — a guard against a stray folder.
     */
    static final int CONFIRM_OVER = 100;

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project from 'sail project switch').")
    private String project;

    @Parameters(
        index = "0",
        arity = "0..1",
        description = "Local file to share. Omit to browse and pick interactively.")
    private Path source;

    @Option(
        names = "--as",
        description = "Relative path to store it under (default: the file's name).")
    private String as;

    @Option(
        names = "--from",
        description = "Directory to browse when picking (default: the current directory).")
    private Path from;

    @Option(
        names = "--plain",
        description = "Use the typed picker instead of the arrow-key checkbox UI.")
    private boolean plain;

    @Override
    public Integer call() throws Exception {
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      if (source != null) {
        return addOne();
      }
      if (System.console() == null) {
        System.err.println(
            Banner.errorLine(
                "Give a file to share, or run in a terminal to browse and pick.", Ansi.AUTO));
        return 1;
      }
      return pick();
    }

    private Integer addOne() throws Exception {
      var path = Strings.isNotBlank(as) ? as : source.getFileName().toString();
      if (!Files.isRegularFile(source)) {
        System.err.println(Banner.errorLine("Not a file: " + source, Ansi.AUTO));
        return 1;
      }
      if (!FilePicker.isShareablePath(path)) {
        System.err.println(Banner.errorLine("Unsafe share path: '" + path + "'.", Ansi.AUTO));
        return 1;
      }
      try (var operations = OperationsFactory.open()) {
        var files = operations.projectFiles(project);
        var problem = files.limits().problem(Files.size(source));
        if (problem.isPresent()) {
          System.err.println(Banner.errorLine(source + ": " + problem.get(), Ansi.AUTO));
          return 1;
        }
        try (var input = Files.newInputStream(source)) {
          files.put(path, input, Files.size(source), WorkspaceFiles.mode(source));
        }
        files.materialize();
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Shared @|bold "
                    + path
                    + "|@ on @|bold "
                    + project
                    + "|@."
                    + propagationSuffix()));
      }
      return 0;
    }

    private record Browse(FileSource source, Path root) {}

    private Integer pick() throws Exception {
      var browse = resolveBrowse();
      if (browse == null) {
        return 1;
      }
      var picked =
          TerminalFilePicker.isAvailable() && !plain
              ? new TerminalFilePicker(browse.source(), browse.root()).run()
              : typedPick(browse.source(), browse.root());
      if (picked.isEmpty()) {
        System.out.println(Ansi.AUTO.string("  @|faint Nothing shared.|@"));
        return 0;
      }
      var state = new FilePicker.State(browse.root(), browse.root(), picked.get());
      return shareSelected(
          browse.source(), browse.root(), FilePicker.selectedFiles(browse.source(), state));
    }

    private Browse resolveBrowse() {
      if (from != null) {
        var root = from.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
          System.err.println(Banner.errorLine("Not a directory: " + root, Ansi.AUTO));
          return null;
        }
        return new Browse(new HostFileSource(), root);
      }
      var workspace = ContainerWorkspace.resolve(project);
      if (workspace.isEmpty()) {
        return null;
      }
      return new Browse(
          new ContainerFileSource(new ShellExecutor(false), project), workspace.get());
    }

    private Optional<LinkedHashSet<Path>> typedPick(FileSource fileSource, Path root) {
      var state = FilePicker.State.at(root);
      while (true) {
        List<FilePicker.Entry> entries;
        try {
          entries = FilePicker.list(fileSource, state.cwd());
        } catch (IOException unreadable) {
          System.out.println(
              Ansi.AUTO.string("  @|yellow ⚠|@ Can't read that folder; returning to the top."));
          state = FilePicker.State.at(root);
          continue;
        }
        System.out.println(FilePicker.render(state, entries));
        System.out.print("  type a command > ");
        System.out.flush();
        var line = ConsoleHelper.readLine();
        var step = FilePicker.step(state, entries, line == null ? "q" : line);
        state = step.state();
        if (Strings.isNotBlank(step.message())) {
          System.out.println("  " + step.message());
        }
        if (step.status() == FilePicker.Status.CANCELLED) {
          return Optional.empty();
        }
        if (step.status() == FilePicker.Status.CONFIRMED) {
          return Optional.of(state.picked());
        }
      }
    }

    private Integer shareSelected(FileSource fileSource, Path root, List<Path> selected)
        throws Exception {
      if (selected.isEmpty()) {
        System.out.println(Ansi.AUTO.string("  @|faint Nothing selected.|@"));
        return 0;
      }
      if (selected.size() > CONFIRM_OVER
          && !ConsoleHelper.confirmNo("Share " + selected.size() + " files?")) {
        System.out.println(Ansi.AUTO.string("  @|faint Nothing shared.|@"));
        return 0;
      }
      Shared shared;
      try (var operations = OperationsFactory.open()) {
        shared = share(operations.projectFiles(project), fileSource, root, selected);
      }
      for (var skip : shared.skipped()) {
        System.err.println(Ansi.AUTO.string("  @|yellow ⚠|@ skipped " + skip));
      }
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Shared @|bold "
                  + shared.count()
                  + "|@ file(s) on @|bold "
                  + project
                  + "|@."
                  + propagationSuffix()));
      return 0;
    }

    record Shared(int count, List<String> skipped) {}

    /**
     * Shares each of {@code selected} through {@code files}, skipping and naming what cannot be
     * shared. The cap is consulted once, before the first file is opened.
     */
    static Shared share(ProjectFiles files, FileSource fileSource, Path root, List<Path> selected)
        throws IOException {
      var limits = files.limits();
      var skipped = new ArrayList<String>();
      var count = 0;
      for (var file : selected) {
        var path = root.relativize(file).toString();
        var problem = shareProblem(fileSource, file, path, limits);
        if (problem != null) {
          skipped.add(path + " (" + problem + ")");
          continue;
        }
        try (var input = fileSource.open(file)) {
          files.put(path, input, fileSource.size(file), fileSource.mode(file));
        }
        count++;
      }
      if (count > 0) {
        files.materialize();
      }
      return new Shared(count, List.copyOf(skipped));
    }

    /**
     * Why {@code file} cannot be shared at {@code relPath}, or {@code null} if it can — a guard for
     * what real project trees throw at us: paths that would escape the files directory, oversized
     * blobs, and entries that vanished or became unreadable since the listing.
     */
    static String shareProblem(
        FileSource fileSource, Path file, String relPath, FileLimits limits) {
      if (!FilePicker.isShareablePath(relPath)) {
        return "unsafe path";
      }
      try {
        return limits.problem(fileSource.size(file)).orElse(null);
      } catch (IOException e) {
        return "unreadable";
      }
    }
  }

  @Command(
      name = "ls",
      description = "List the shared files on a project.",
      mixinStandardHelpOptions = true)
  static final class Ls implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project from 'sail project switch').")
    private String project;

    @Option(names = "--json", description = "Output as JSON.")
    private boolean json;

    @Override
    public Integer call() {
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      try (var operations = OperationsFactory.open()) {
        var rows = operations.projectFiles(project).list();
        if (json || rows.isEmpty()) {
          System.out.println(render(rows, project, json));
        } else {
          Banner.printProjectFilesTable(rows, project, System.out, Ansi.AUTO);
        }
        return 0;
      }
    }

    /** Renders the JSON list, or the empty-state message for the human path. */
    static String render(List<FileStore.FileRow> rows, String project, boolean json) {
      if (json) {
        var list =
            rows.stream()
                .map(
                    r -> {
                      var map = new LinkedHashMap<String, Object>();
                      map.put("path", r.path());
                      map.put("bytes", r.size());
                      map.put("mode", r.mode());
                      map.put("kind", r.kind());
                      map.put("content_hash", r.contentHash());
                      return (Object) map;
                    })
                .toList();
        return YamlUtil.dumpJson(list);
      }
      return Ansi.AUTO.string("  @|faint No shared files on |@@|bold " + project + "|@@|faint .|@");
    }
  }

  @Command(
      name = "cat",
      description = "Print a shared file's content to stdout.",
      mixinStandardHelpOptions = true)
  static final class Cat implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project from 'sail project switch').")
    private String project;

    @Parameters(index = "0", description = "Relative path of the file.")
    private String path;

    @Override
    public Integer call() throws Exception {
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      try (var operations = OperationsFactory.open()) {
        var bytes = operations.projectFiles(project).get(path).orElse(null);
        if (bytes == null) {
          System.err.println(
              Banner.errorLine("No shared file '" + path + "' on " + project + ".", Ansi.AUTO));
          return 1;
        }
        try (bytes) {
          bytes.transferTo(System.out);
        }
        System.out.flush();
        return 0;
      }
    }

    static Optional<java.io.InputStream> read(FileStore files, String project, String path) {
      return new SharedProjectFiles(files, SailPaths.projectsDir(), project, FileLimits.defaults())
          .get(path);
    }
  }

  @Command(
      name = "rm",
      description = "Stop sharing a file (propagates the deletion).",
      mixinStandardHelpOptions = true)
  static final class Rm implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project from 'sail project switch').")
    private String project;

    @Parameters(index = "0", description = "Relative path of the file.")
    private String path;

    @Override
    public Integer call() throws Exception {
      project = CurrentProject.require(project);
      NameValidator.requireValidProjectName(project);
      try (var operations = OperationsFactory.open()) {
        if (!operations.projectFiles(project).remove(path)) {
          System.err.println(
              Banner.errorLine("No shared file '" + path + "' on " + project + ".", Ansi.AUTO));
          return 1;
        }
      }
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Stopped sharing @|bold " + path + "|@." + propagationSuffix()));
      return 0;
    }

    /** Tombstones the file and removes the local on-disk copy; false if it was not shared. */
    static boolean unshare(FileStore files, Path projectsDir, String project, String path)
        throws IOException {
      return new SharedProjectFiles(files, projectsDir, project, FileLimits.defaults())
          .remove(path);
    }
  }

  @Command(
      name = "pull",
      aliases = "export",
      description = "Write the shared files for a project to disk (materialize what's synced).",
      mixinStandardHelpOptions = true)
  static final class Export implements Callable<Integer> {

    @Option(
        names = {"-p", "--project"},
        description = "Project (default: the current project from 'sail project switch').")
    private String project;

    @Option(names = "--all", description = "Pull every project that has shared files.")
    private boolean all;

    @Override
    public Integer call() throws Exception {
      if (all && Strings.isNotBlank(project)) {
        System.err.println(Banner.errorLine("Pass --project OR --all, not both.", Ansi.AUTO));
        return 1;
      }
      try (var operations = OperationsFactory.open()) {
        var targets =
            all
                ? operations.catalog().projectsWithFiles()
                : List.of(CurrentProject.require(project));
        var written = 0;
        var deleted = 0;
        var skipped = new ArrayList<String>();
        for (var target : targets) {
          var materialized = operations.projectFiles(target).materialize();
          written += materialized.written();
          deleted += materialized.deleted();
          for (var skip : materialized.skipped()) {
            skipped.add(target + "/" + skip);
          }
        }
        var report = new ExportReport(written, deleted, List.copyOf(skipped));
        for (var skip : report.skipped()) {
          System.err.println(
              Ansi.AUTO.string("  @|yellow ⚠|@ kept local edit: " + skip + " (unchanged)"));
        }
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Pulled: "
                    + report.written()
                    + " written, "
                    + report.deleted()
                    + " removed."));
      }
      return 0;
    }

    record ExportReport(int written, int deleted, List<String> skipped) {}

    static ExportReport export(FileStore files, Path projectsDir, Collection<String> targets)
        throws IOException {
      var materializer = new FileMaterializer(files, projectsDir);
      var written = 0;
      var deleted = 0;
      var skipped = new ArrayList<String>();
      for (var target : targets) {
        NameValidator.requireValidProjectName(target);
        var report = materializer.materialize(target);
        written += report.written();
        deleted += report.deleted();
        for (var skip : report.skipped()) {
          skipped.add(target + "/" + skip);
        }
      }
      return new ExportReport(written, deleted, List.copyOf(skipped));
    }
  }

  /** A leading-space propagation hint, or empty on a standalone box where sync does not apply. */
  static String propagationSuffix() {
    var hint = HostSync.propagationHint(HostSync.config());
    return hint.isEmpty() ? "" : " " + hint;
  }
}
