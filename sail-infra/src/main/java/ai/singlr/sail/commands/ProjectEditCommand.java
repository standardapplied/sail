/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Edits a project's definition in the catalog in {@code $EDITOR}: the whole JSON document, or one
 * section of it. The edit is checked whole and only the sections it changed are written, in one
 * revision, refused if another write changed any of them while the editor was open; it replicates
 * to every other box on the next {@code sail sync}.
 */
@Command(
    name = "edit",
    description = "Edit a project's definition in $EDITOR (saves to the catalog; syncs to boxes).",
    mixinStandardHelpOptions = true)
public final class ProjectEditCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(names = "--section", description = "Edit this section alone.")
  private String section;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  private final Editor editor;
  private final ProjectMutations mutations;

  public ProjectEditCommand() {
    this(Editor.fromEnvironment(), ProjectMutations.here());
  }

  ProjectEditCommand(Editor editor, ProjectMutations mutations) {
    this.editor = editor;
    this.mutations = mutations;
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    NameValidator.requireValidProjectName(name);
    var only = section == null ? null : Section.of(section);
    var actor = mutations.writer(name, false);
    var current = mutations.current(name, operator);
    var seed = seed(current, only);
    var draft = edit(editor, name, seed);
    List<Section> changed;
    try {
      var edited = draft.text().equals(seed) ? current : edited(name, current, only, draft.text());
      changed = mutations.catalog().writeChanged(current, edited, actor);
    } catch (RuntimeException refused) {
      throw kept(refused, draft.file());
    }
    Files.deleteIfExists(draft.file());

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("status", changed.isEmpty() ? "unchanged" : "updated");
      map.put("sections", changed.stream().map(Section::key).toList());
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    if (changed.isEmpty()) {
      System.out.println(Ansi.AUTO.string("  @|faint No changes.|@"));
      return;
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|green ✓|@ Updated "
                + String.join(", ", changed.stream().map(Section::key).toList())
                + " of '"
                + name
                + "'. It replicates to other boxes on the next sync."));
  }

  /** What the editor opens on: the whole definition, or {@code only}'s value, as JSON. */
  static String seed(ProjectDefinition current, Section only) {
    var document = YamlUtil.parseMap(ProjectDefinitionJson.write(current));
    var value = only == null ? document : document.get(only.key());
    return YamlUtil.dumpJsonPretty(value) + "\n";
  }

  /**
   * The definition {@code text} leaves project {@code name} as: the whole document, whose name must
   * stay {@code name}, or {@code only}'s new value; refused, with the reason, when it is not one.
   */
  static ProjectDefinition edited(
      String name, ProjectDefinition current, Section only, String text) {
    ProjectDefinition edited;
    try {
      edited =
          only == null
              ? ProjectDefinitionJson.read(text)
              : ProjectDefinitionJson.withSection(current, only, text.strip());
    } catch (RuntimeException e) {
      throw Remedied.argument(
          Remedied.joined("The edited definition is not valid:", Remedied.reasonOf(e)),
          Remedied.remedyOf(e));
    }
    if (!name.equals(edited.name())) {
      throw new IllegalArgumentException(
          "The 'name' field must stay '"
              + name
              + "'. Renaming a project through edit is not supported.");
    }
    return edited;
  }

  /** What the engineer left in the editor, and the scratch file it is in until it is saved. */
  record Draft(Path file, String text) {}

  /**
   * {@code seed} as the engineer left it in {@code editor}. The scratch file stays until the edit
   * is saved, so a refused edit is never lost; an editor that fails takes it with it.
   */
  static Draft edit(Editor editor, String name, String seed)
      throws IOException, InterruptedException {
    var tmp = Files.createTempFile("sail-project-" + name + "-", ".json");
    try {
      Files.writeString(tmp, seed);
      var exit = editor.edit(tmp);
      if (exit != 0) {
        throw new IllegalStateException("Editor exited with status " + exit + "; no changes made.");
      }
      return new Draft(tmp, Files.readString(tmp));
    } catch (IOException | InterruptedException | RuntimeException failed) {
      Files.deleteIfExists(tmp);
      throw failed;
    }
  }

  /** {@code refused}, saying where the edit it refused is kept, so nothing typed is lost. */
  static RuntimeException kept(RuntimeException refused, Path file) {
    var note = "Your edit is kept at " + file + ".";
    var remedy = Remedied.remedyOf(refused);
    var fix = remedy.isEmpty() ? Remedy.of(note) : remedy.then(" " + note);
    return refused instanceof IllegalArgumentException
        ? Remedied.argument(Remedied.reasonOf(refused), fix)
        : Remedied.state(Remedied.reasonOf(refused), fix, refused);
  }
}
