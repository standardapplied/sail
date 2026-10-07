/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.gen.BuiltInSkills;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FileStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Finds the skill a stage runs under: one sail ships, or one the project holds among its files
 * under {@code .sail/skills/<name>/}. A project's skill is read at every launch, from the rows as
 * they are then, and both the prompt and the folder a harness sees are made from that one reading:
 * a file that changed since is refused when it is opened, never installed beside files that did
 * not.
 *
 * <p>A skill that cannot be read is an {@link IllegalStateException} naming the project and the
 * skill, with no cause attached: the message is what a room or an operator is told, once. A box
 * with no project files wired knows only sail's own skills.
 */
public final class StageSkills {

  private final Function<String, ProjectFiles> files;

  /**
   * @param files a project's shared files
   */
  public StageSkills(Function<String, ProjectFiles> files) {
    this.files = files;
  }

  /** The skills of a box that has no project files wired: sail's own, and no project's. */
  public static StageSkills builtInOnly() {
    return new StageSkills(null);
  }

  /** {@code skill} as a prompt carries it to an agent {@code harness} runs. */
  public static String block(StageSkill skill, Harness harness) {
    return skill.block("~/" + harness.skillFolder(skill.name()) + "/");
  }

  /** How the skill a stage names stands for a project: what a launch would find. */
  public sealed interface Standing {

    /** One of the skills sail ships. */
    record BuiltIn(StageSkill skill) implements Standing {}

    /** A skill the project holds, read whole. */
    record Held(StageSkill skill) implements Standing {}

    /**
     * No skill: the project holds no text file at {@code manifest}.
     *
     * @param manifest the project file a skill of that name starts with
     */
    record Missing(String manifest) implements Standing {}

    /**
     * A skill the project holds that cannot be launched under: {@link StageSkill} refuses it, or
     * its manifest cannot be read, as when its content has not reached this box yet.
     *
     * @param why what refused it, as {@link StageSkill} or the reading says it
     * @param files how many files the project holds under the skill's folder
     */
    record Invalid(String why, int files) implements Standing {}
  }

  /** The skill named {@code name}: sail's own under that name, or else {@code project}'s. */
  public StageSkill resolve(String project, String name) {
    return switch (standing(project, name)) {
      case Standing.BuiltIn builtIn -> builtIn.skill();
      case Standing.Held held -> held.skill();
      case Standing.Missing missing ->
          throw refused(
              project,
              name,
              "cannot be read: "
                  + missing.manifest()
                  + " is missing or is not a text file. Add it with 'sail project files add <file>"
                  + " --as "
                  + missing.manifest()
                  + "'.");
      case Standing.Invalid invalid -> throw refused(project, name, "is refused: " + invalid.why());
    };
  }

  /**
   * How the skill named {@code name} stands for {@code project}, read now: the one reading {@link
   * #resolve} answers from, as a value for whoever shows it rather than launches under it.
   */
  public Standing standing(String project, String name) {
    var builtIn = BuiltInSkills.of(name);
    if (builtIn.isPresent()) {
      return new Standing.BuiltIn(builtIn.get());
    }
    var held = 0;
    try {
      StageSkill.requireName(name);
      var shared = filesOf(project, name);
      var folder = StageSkill.projectFolder(name);
      var rows = shared.list().stream().filter(row -> row.path().startsWith(folder)).toList();
      held = rows.size();
      var manifest =
          rows.stream()
              .filter(row -> row.path().equals(folder + StageSkill.MANIFEST))
              .filter(row -> "text".equals(row.kind()))
              .findFirst();
      if (manifest.isEmpty()) {
        return new Standing.Missing(folder + StageSkill.MANIFEST);
      }
      return new Standing.Held(
          StageSkill.of(
              name,
              text(shared, manifest.get()),
              rows.stream()
                  .map(
                      row ->
                          new StageSkill.File(
                              row.path().substring(folder.length()),
                              row.contentHash(),
                              row.size(),
                              row.mode()))
                  .toList()));
    } catch (IllegalArgumentException | BlobStore.NotHeld | IOException unreadable) {
      return new Standing.Invalid(unreadable.getMessage(), held);
    }
  }

  /**
   * The bytes of {@code file} of {@code skill} as it was resolved. A project file that holds other
   * content by now is refused: the skill changed under the launch that is installing it.
   */
  public InputStream open(String project, StageSkill skill, StageSkill.File file) {
    var builtIn = BuiltInSkills.text(skill.name());
    if (builtIn.isPresent()) {
      return new ByteArrayInputStream(builtIn.get().getBytes(StandardCharsets.UTF_8));
    }
    var shared = filesOf(project, skill.name());
    var path = StageSkill.projectFolder(skill.name()) + file.path();
    var row =
        shared
            .find(path)
            .filter(current -> current.contentHash().equals(file.contentHash()))
            .orElseThrow(
                () ->
                    refused(
                        project,
                        skill.name(),
                        "changed while it was being installed: "
                            + path
                            + " is no longer the file that was resolved. Launch again."));
    return shared.open(row);
  }

  private ProjectFiles filesOf(String project, String name) {
    if (files == null) {
      throw refused(project, name, "cannot be read: this box has no project files wired.");
    }
    return files.apply(project);
  }

  /** The manifest's text, read no further than a skill's folder may be large. */
  private static String text(ProjectFiles shared, FileStore.FileRow manifest) throws IOException {
    try (var bytes = shared.open(manifest)) {
      return new String(bytes.readNBytes((int) StageSkill.MAX_BYTES + 1), StandardCharsets.UTF_8);
    }
  }

  private static IllegalStateException refused(String project, String name, String why) {
    return new IllegalStateException("Skill '" + name + "' of project '" + project + "' " + why);
  }
}
