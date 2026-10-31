/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.SkillFolders;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.gen.SpecSkillGenerator;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FileStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The skills a project holds among its files, one folder each under {@code .sail/skills/}. Every
 * folder is read at each launch and each apply, from the rows as they are then, and the folder a
 * harness sees is made from that one reading: a file that changed since is refused when it is
 * opened, never installed beside files that did not. A folder that is not a skill is reported, not
 * fatal: the valid ones are installed and whoever asked is told which folder was skipped and why. A
 * box with no project files wired holds no project skill.
 *
 * <p>A skill is shared and removed here, whichever door asked: the devbox's {@code sail project
 * skills} and the container's {@code skill} write through these, as the actor their door bound, so
 * the journal decides each write and it syncs to every box with the project.
 */
public final class ProjectSkills {

  private final Function<String, ProjectFiles> files;

  /**
   * @param files a project's shared files
   */
  public ProjectSkills(Function<String, ProjectFiles> files) {
    this.files = files;
  }

  /** The skills of a box that has no project files wired: none. */
  public static ProjectSkills none() {
    return new ProjectSkills(null);
  }

  /** The name sail's own skill takes, which no project skill may. */
  public static boolean isReserved(String name) {
    return name.equals(SpecSkillGenerator.NAME) || name.startsWith(StageSkill.RESERVED);
  }

  /** Refuses a name a project skill may not take, saying which names are sail's. */
  public static void requireOwnName(String name) {
    StageSkill.requireName(name);
    if (isReserved(name)) {
      throw new IllegalArgumentException(
          "Skill name '"
              + name
              + "' is sail's own: "
              + SpecSkillGenerator.NAME
              + " and names starting "
              + StageSkill.RESERVED
              + " are reserved. Give the skill another name.");
    }
  }

  /** One folder under {@code .sail/skills/}, as a launch finds it. */
  public sealed interface Folder {

    /** The folder's name. */
    String name();

    /** A skill the project holds, read whole. */
    record Held(StageSkill skill) implements Folder {
      @Override
      public String name() {
        return skill.name();
      }
    }

    /**
     * A folder that is no skill: its name is not a skill's, its {@code SKILL.md} is missing or not
     * text, {@link StageSkill} refuses it, or a file cannot be read, as when its content has not
     * reached this box yet.
     *
     * @param why what refused it
     * @param files how many files the project holds under the folder
     */
    record Invalid(String name, String why, int files) implements Folder {}
  }

  /** Every folder under {@code project}'s {@code .sail/skills/}, in name order, read now. */
  public List<Folder> all(String project) {
    if (files == null) {
      return List.of();
    }
    var shared = files.apply(project);
    var byFolder = new LinkedHashMap<String, List<FileStore.FileRow>>();
    for (var row : shared.list()) {
      if (!row.path().startsWith(StageSkill.PROJECT_ROOT)) {
        continue;
      }
      var rest = row.path().substring(StageSkill.PROJECT_ROOT.length());
      var slash = rest.indexOf('/');
      if (slash <= 0) {
        continue;
      }
      byFolder.computeIfAbsent(rest.substring(0, slash), name -> new ArrayList<>()).add(row);
    }
    return byFolder.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> read(shared, entry.getKey(), entry.getValue()))
        .toList();
  }

  /** The skills {@code project} holds that a launch installs: every valid folder's. */
  public List<StageSkill> held(String project) {
    return all(project).stream()
        .filter(Folder.Held.class::isInstance)
        .map(folder -> ((Folder.Held) folder).skill())
        .toList();
  }

  private static Folder read(ProjectFiles shared, String name, List<FileStore.FileRow> rows) {
    try {
      requireOwnName(name);
      var folder = StageSkill.projectFolder(name);
      var manifest =
          rows.stream()
              .filter(row -> row.path().equals(folder + StageSkill.MANIFEST))
              .filter(row -> "text".equals(row.kind()))
              .findFirst();
      if (manifest.isEmpty()) {
        return new Folder.Invalid(
            name, folder + StageSkill.MANIFEST + " is missing or is not a text file.", rows.size());
      }
      return new Folder.Held(
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
      return new Folder.Invalid(name, unreadable.getMessage(), rows.size());
    }
  }

  /**
   * The bytes of {@code file} of {@code skill} as it was read. A project file that holds other
   * content by now is refused: the skill changed under the launch that is installing it. So is one
   * whose content this box does not hold, naming the file.
   */
  public InputStream open(String project, StageSkill skill, StageSkill.File file) {
    if (skill.name().equals(SpecSkillGenerator.NAME)) {
      return SpecSkillGenerator.content(file);
    }
    var shared = files.apply(project);
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
                            + " is no longer the file that was read. Launch again."));
    try {
      return shared.open(row);
    } catch (BlobStore.NotHeld notHeld) {
      throw refused(
          project,
          skill.name(),
          "cannot be read: the content of "
              + path
              + " is not on this box ("
              + notHeld.getMessage()
              + ").");
    }
  }

  /**
   * Shares {@code entries}, a folder read whole, as {@code project}'s skill {@code name}: refused
   * as {@link StageSkill} refuses it, or for a file over the project's size cap, before anything is
   * written; then every file is put under the skill's folder and every file the folder held before
   * and does not now is removed, so the shared skill is exactly the folder.
   */
  public StageSkill share(String project, String name, List<SkillFolders.Entry> entries)
      throws IOException {
    requireOwnName(name);
    var manifest =
        entries.stream()
            .filter(entry -> entry.path().equals(StageSkill.MANIFEST))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Skill '" + name + "' has no " + StageSkill.MANIFEST + "."));
    var skill =
        StageSkill.of(
            name,
            new String(manifest.bytes(), StandardCharsets.UTF_8),
            entries.stream()
                .map(
                    entry ->
                        new StageSkill.File(
                            entry.path(),
                            BlobStore.hash(entry.bytes()),
                            entry.bytes().length,
                            entry.mode()))
                .toList());
    var shared = files.apply(project);
    for (var entry : entries) {
      shared
          .limits()
          .problem(entry.bytes().length)
          .ifPresent(
              problem -> {
                throw new IllegalArgumentException(entry.path() + ": " + problem);
              });
    }
    var folder = StageSkill.projectFolder(name);
    var kept = new HashSet<String>();
    for (var entry : entries) {
      kept.add(folder + entry.path());
      shared.put(
          folder + entry.path(),
          new ByteArrayInputStream(entry.bytes()),
          entry.bytes().length,
          entry.mode());
    }
    for (var path : pathsOf(shared, name)) {
      if (!kept.contains(path)) {
        shared.remove(path);
      }
    }
    shared.materialize();
    return skill;
  }

  /**
   * Stops sharing {@code project}'s skill {@code name}: every file under its folder is removed, and
   * the deletion syncs. Returns how many files it held; none when it was not shared.
   */
  public int remove(String project, String name) throws IOException {
    StageSkill.requireName(name);
    var shared = files.apply(project);
    var paths = pathsOf(shared, name);
    for (var path : paths) {
      shared.remove(path);
    }
    return paths.size();
  }

  /**
   * The {@code SKILL.md} of {@code project}'s skill {@code name}, or of sail's {@code spec-board}.
   */
  public String manifest(String project, String name) throws IOException {
    if (name.equals(SpecSkillGenerator.NAME)) {
      return SpecSkillGenerator.skillMd();
    }
    StageSkill.requireName(name);
    var path = StageSkill.projectFolder(name) + StageSkill.MANIFEST;
    var manifest =
        files
            .apply(project)
            .get(path)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No skill '" + name + "' on " + project + ": it has no " + path + "."));
    try (manifest) {
      return new String(manifest.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<String> pathsOf(ProjectFiles shared, String name) {
    var folder = StageSkill.projectFolder(name);
    return shared.list().stream()
        .map(FileStore.FileRow::path)
        .filter(path -> path.startsWith(folder))
        .toList();
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
