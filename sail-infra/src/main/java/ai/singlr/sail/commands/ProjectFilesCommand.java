/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Shares arbitrary workspace files across every FDE on a project through the same sync engine as
 * specs — no git, no specific repo. {@code add} stores a file's bytes (base64) keyed by its
 * relative path; the next {@code sail sync} replicates it and {@link FileMaterializer} drops it
 * onto every other box. {@code export} runs that materialization on demand, for the main box that
 * never calls {@code sail sync}. Removal tombstones the row so the deletion propagates too.
 */
@Command(
    name = "files",
    description = "Share project files across FDEs (add, ls, cat, rm, export).",
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
      description = "Share a local file with every FDE on the project.",
      mixinStandardHelpOptions = true)
  static final class Add implements Callable<Integer> {

    @Parameters(index = "0", description = "Project name.")
    private String project;

    @Parameters(index = "1", description = "Local file to share.")
    private Path source;

    @Option(
        names = "--as",
        description = "Relative path to store it under (default: the file's name).")
    private String as;

    @Override
    public Integer call() throws Exception {
      NameValidator.requireValidProjectName(project);
      if (!Files.isRegularFile(source)) {
        System.err.println(Banner.errorLine("Not a file: " + source, Ansi.AUTO));
        return 1;
      }
      try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
        var path = share(new FileStore(db), SailPaths.projectsDir(), project, source, as);
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Shared @|bold "
                    + path
                    + "|@ on @|bold "
                    + project
                    + "|@. Run @|bold sail sync|@ to propagate."));
      }
      return 0;
    }

    /**
     * Stores {@code source} under its relative path and materializes it locally; returns the path.
     */
    static String share(FileStore files, Path projectsDir, String project, Path source, String as)
        throws IOException {
      var path = Strings.isNotBlank(as) ? as : source.getFileName().toString();
      NameValidator.requireSafePath(path, "path");
      files.put(project, path, Base64.getEncoder().encodeToString(Files.readAllBytes(source)));
      new FileMaterializer(files, projectsDir).materialize(project);
      return path;
    }
  }

  @Command(
      name = "ls",
      description = "List the shared files on a project.",
      mixinStandardHelpOptions = true)
  static final class Ls implements Callable<Integer> {

    @Parameters(index = "0", description = "Project name.")
    private String project;

    @Option(names = "--json", description = "Output as JSON.")
    private boolean json;

    @Override
    public Integer call() {
      NameValidator.requireValidProjectName(project);
      try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
        System.out.println(render(new FileStore(db).list(project), project, json));
        return 0;
      }
    }

    static String render(List<FileStore.FileRow> rows, String project, boolean json) {
      if (json) {
        var list =
            rows.stream()
                .map(
                    r -> {
                      var map = new LinkedHashMap<String, Object>();
                      map.put("path", r.path());
                      map.put("bytes", decodedSize(r.content()));
                      return (Object) map;
                    })
                .toList();
        return YamlUtil.dumpJson(list);
      }
      if (rows.isEmpty()) {
        return Ansi.AUTO.string(
            "  @|faint No shared files on |@@|bold " + project + "|@@|faint .|@");
      }
      var out = new StringBuilder();
      out.append(
          Ansi.AUTO.string("  @|bold " + rows.size() + "|@ shared file(s) on " + project + ":\n"));
      for (var r : rows) {
        out.append(
            Ansi.AUTO.string(
                "    @|green " + r.path() + "|@ @|faint (" + decodedSize(r.content()) + " B)|@\n"));
      }
      return out.toString().stripTrailing();
    }
  }

  @Command(
      name = "cat",
      description = "Print a shared file's content to stdout.",
      mixinStandardHelpOptions = true)
  static final class Cat implements Callable<Integer> {

    @Parameters(index = "0", description = "Project name.")
    private String project;

    @Parameters(index = "1", description = "Relative path of the file.")
    private String path;

    @Override
    public Integer call() throws Exception {
      NameValidator.requireValidProjectName(project);
      try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
        var bytes = read(new FileStore(db), project, path).orElse(null);
        if (bytes == null) {
          System.err.println(
              Banner.errorLine("No shared file '" + path + "' on " + project + ".", Ansi.AUTO));
          return 1;
        }
        System.out.write(bytes);
        System.out.flush();
        return 0;
      }
    }

    static Optional<byte[]> read(FileStore files, String project, String path) {
      return files.find(project, path).map(row -> Base64.getDecoder().decode(row.content()));
    }
  }

  @Command(
      name = "rm",
      description = "Stop sharing a file (propagates the deletion).",
      mixinStandardHelpOptions = true)
  static final class Rm implements Callable<Integer> {

    @Parameters(index = "0", description = "Project name.")
    private String project;

    @Parameters(index = "1", description = "Relative path of the file.")
    private String path;

    @Override
    public Integer call() throws Exception {
      NameValidator.requireValidProjectName(project);
      try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
        if (!unshare(new FileStore(db), SailPaths.projectsDir(), project, path)) {
          System.err.println(
              Banner.errorLine("No shared file '" + path + "' on " + project + ".", Ansi.AUTO));
          return 1;
        }
      }
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Stopped sharing @|bold "
                  + path
                  + "|@. Run @|bold sail sync|@ to propagate."));
      return 0;
    }

    /** Tombstones the file and removes the local on-disk copy; false if it was not shared. */
    static boolean unshare(FileStore files, Path projectsDir, String project, String path)
        throws IOException {
      if (!files.delete(project, path)) {
        return false;
      }
      new FileMaterializer(files, projectsDir).materialize(project);
      return true;
    }
  }

  @Command(
      name = "export",
      description = "Write shared files to disk now (for the main box, which never runs sync).",
      mixinStandardHelpOptions = true)
  static final class Export implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Project name. Omit with --all.")
    private String project;

    @Option(names = "--all", description = "Export every project that has shared files.")
    private boolean all;

    @Override
    public Integer call() throws Exception {
      if (all == (Strings.isNotBlank(project))) {
        System.err.println(Banner.errorLine("Pass a project name OR --all, not both.", Ansi.AUTO));
        return 1;
      }
      try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
        var files = new FileStore(db);
        var targets = all ? files.projectsWithFiles() : List.of(project);
        var report = export(files, SailPaths.projectsDir(), targets);
        for (var skip : report.skipped()) {
          System.err.println(
              Ansi.AUTO.string("  @|yellow ⚠|@ kept local edit: " + skip + " (unchanged)"));
        }
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Exported: "
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

  private static int decodedSize(String base64) {
    return Base64.getDecoder().decode(base64).length;
  }
}
