/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectDefinitions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Edits a project's definition in the catalog — the source of truth — and re-materializes the
 * on-disk {@code sail.yaml}. With the database authoritative, hand-editing the descriptor file is a
 * no-op; this is the supported way to change a project, and the edit replicates to every other box
 * on the next {@code sail sync}. Opens {@code $EDITOR} by default, or reads the new definition from
 * {@code --file} for non-interactive use.
 */
@Command(
    name = "edit",
    description = "Edit a project's definition (saves to the catalog; syncs to other boxes).",
    mixinStandardHelpOptions = true)
public final class ProjectEditCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(
      names = {"-f", "--file"},
      description = "Read the new definition from this file instead of opening $EDITOR.")
  private String file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    var current =
        ProjectDefinitions.definition(name, null)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No project '"
                            + name
                            + "' to edit. Create it with 'sail project create', or sync it from"
                            + " main with 'sail sync'."));

    var edited = file != null ? Files.readString(Path.of(file)) : editInEditor(current);
    if (edited.equals(current)) {
      if (!json) {
        System.out.println(Ansi.AUTO.string("  @|faint No changes.|@"));
      }
      return;
    }

    validate(name, edited);
    ProjectDefinitions.persist(name, null, edited, Actor.current());

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("status", "updated");
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|green ✓|@ Updated '"
                + name
                + "'. It replicates to other boxes on the next @|bold sail sync|@."));
  }

  /**
   * Parses {@code edited} and rejects it unless it is a valid descriptor whose {@code name} still
   * matches the project — renaming a project through {@code edit} is not allowed, since the name is
   * its identity in the catalog. Pure, so the validation is unit-tested without a database.
   */
  static void validate(String name, String edited) {
    SailYaml config;
    try {
      config = SailYaml.fromMap(YamlUtil.parseMap(edited));
    } catch (Exception e) {
      throw new IllegalArgumentException("Edited descriptor is not valid YAML: " + e.getMessage());
    }
    if (config.name() == null || !config.name().equals(name)) {
      throw new IllegalArgumentException(
          "The 'name' field must stay '"
              + name
              + "'. Renaming a project through edit is not"
              + " supported.");
    }
  }

  private String editInEditor(String seed) throws IOException, InterruptedException {
    var editor = System.getenv("EDITOR");
    if (editor == null || editor.isBlank()) {
      editor = "vi";
    }
    var tmp = Files.createTempFile("sail-project-" + name + "-", ".yaml");
    try {
      Files.writeString(tmp, seed);
      var process = new ProcessBuilder(editor, tmp.toString()).inheritIO().start();
      if (process.waitFor() != 0) {
        throw new IllegalStateException("Editor exited without saving; no changes made.");
      }
      return Files.readString(tmp);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
