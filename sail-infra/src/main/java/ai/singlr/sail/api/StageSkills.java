/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.gen.BuiltInSkills;
import ai.singlr.sail.harness.Harness;
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
 * skill, with no cause attached: the message is what a room or an operator is told, once.
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
    return skill.block("~/" + harness.skillsDir() + skill.name() + "/");
  }

  /** The skill named {@code name}: sail's own under that name, or else {@code project}'s. */
  public StageSkill resolve(String project, String name) {
    var builtIn = BuiltInSkills.of(name);
    if (builtIn.isPresent()) {
      return builtIn.get();
    }
    try {
      StageSkill.requireName(name);
      var shared = filesOf(project, name);
      var folder = StageSkill.PROJECT_ROOT + name + "/";
      var rows = shared.list().stream().filter(row -> row.path().startsWith(folder)).toList();
      var manifest =
          rows.stream()
              .filter(row -> row.path().equals(folder + StageSkill.MANIFEST))
              .filter(row -> "text".equals(row.kind()))
              .findFirst()
              .orElseThrow(
                  () ->
                      refused(
                          project,
                          name,
                          "cannot be read: "
                              + folder
                              + StageSkill.MANIFEST
                              + " is missing or is not a text file. Add it with 'sail project"
                              + " files add <file> --as "
                              + folder
                              + StageSkill.MANIFEST
                              + "'."));
      return StageSkill.of(
          name,
          text(shared, manifest),
          rows.stream()
              .map(
                  row ->
                      new StageSkill.File(
                          row.path().substring(folder.length()),
                          row.contentHash(),
                          row.size(),
                          row.mode()))
              .toList());
    } catch (IllegalArgumentException | IOException unreadable) {
      throw refused(project, name, "is refused: " + unreadable.getMessage());
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
    var path = StageSkill.PROJECT_ROOT + skill.name() + "/" + file.path();
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
