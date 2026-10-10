/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A skill: a folder with a {@code SKILL.md} (front matter, then instructions) and optional scripts
 * and reference files, the shape Claude Code and Codex share, installed where a harness looks for
 * skills and invoked when the agent judges it relevant. A project holds its own among its files,
 * and sail ships one, {@code spec-board}.
 *
 * <p>A skill's name, paths and body are untrusted input, checked here once: a value of this type is
 * bounded and names only files inside its own folder.
 *
 * @param name the skill's name, which is its folder's
 * @param body the instructions, without front matter
 * @param files every file of the folder, {@code SKILL.md} included, in path order
 */
public record StageSkill(String name, String body, List<File> files) {

  /** Where a project's own skills live among its files, each in a folder named for it. */
  public static final String PROJECT_ROOT = ".sail/skills/";

  /** The file every skill has: its front matter and instructions. */
  public static final String MANIFEST = "SKILL.md";

  /** The file sail writes into an installed folder to say which skill it holds. */
  public static final String STAMP = ".sail-skill";

  /** The most files a skill's folder may hold, {@code SKILL.md} included. */
  public static final int MAX_FILES = 32;

  /** The most bytes a skill's folder may hold, {@code SKILL.md} included. */
  public static final long MAX_BYTES = 1024 * 1024;

  /** What no project skill's name may start with: those names are sail's. */
  public static final String RESERVED = "sail-";

  /** The folder a project's skill named {@code name} lives in among its files, slash-ended. */
  public static String projectFolder(String name) {
    return PROJECT_ROOT + name + "/";
  }

  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
  private static final String FENCE = "---";

  /**
   * One file of a skill's folder.
   *
   * @param path its path relative to the folder
   * @param contentHash the hash of its bytes, as the blob store names them
   * @param size its size in bytes
   * @param mode its permission bits
   */
  public record File(String path, String contentHash, long size, int mode) {}

  public StageSkill {
    requireName(name);
    if (Strings.isBlank(body)) {
      throw new IllegalArgumentException(
          "Skill '" + name + "' has no instructions: its " + MANIFEST + " body is blank.");
    }
    var codePoints = body.codePointCount(0, body.length());
    if (codePoints > PromptConversation.MAX_CODE_POINTS) {
      throw new IllegalArgumentException(
          "Skill '%s' has a body of %d code points; the limit is %d."
              .formatted(name, codePoints, PromptConversation.MAX_CODE_POINTS));
    }
    var seen = new HashSet<String>();
    for (var file : Objects.requireNonNull(files, "files")) {
      if (!isRelative(file.path())) {
        throw new IllegalArgumentException(
            "Skill '%s' holds the path '%s', which is not a path inside its folder."
                .formatted(name, file.path()));
      }
      if (file.path().equals(STAMP) || file.path().endsWith("/" + STAMP)) {
        throw new IllegalArgumentException(
            "Skill '%s' holds the path '%s'; %s is the name sail stamps an installed skill with."
                .formatted(name, file.path(), STAMP));
      }
      if (!seen.add(file.path())) {
        throw new IllegalArgumentException(
            "Skill '%s' holds the path '%s' twice.".formatted(name, file.path()));
      }
    }
    if (!seen.contains(MANIFEST)) {
      throw new IllegalArgumentException("Skill '%s' has no %s.".formatted(name, MANIFEST));
    }
    if (files.size() > MAX_FILES) {
      throw new IllegalArgumentException(
          "Skill '%s' has %d files; the limit is %d.".formatted(name, files.size(), MAX_FILES));
    }
    var bytes = files.stream().mapToLong(File::size).sum();
    if (bytes > MAX_BYTES) {
      throw new IllegalArgumentException(
          "Skill '%s' is %d bytes; the limit is %d.".formatted(name, bytes, MAX_BYTES));
    }
    files = files.stream().sorted(Comparator.comparing(File::path)).toList();
  }

  /** Whether {@code name} can name a skill: a short lowercase slug, safe as a folder's name. */
  public static boolean isName(String name) {
    return name != null && NAME.matcher(name).matches();
  }

  /** Refuses a {@code name} that cannot name a skill, before anything is looked up under it. */
  public static void requireName(String name) {
    if (!isName(name)) {
      throw new IllegalArgumentException(
          "Skill name '" + name + "' must match " + NAME.pattern() + ".");
    }
  }

  /**
   * The skill a {@code SKILL.md} describes, and the only reader of one. A leading byte-order mark
   * is dropped and line endings are read as {@code \n}. When the first line is exactly {@code ---}
   * the front matter runs to the next such line and the body is what follows, stripped; front
   * matter that is never closed is refused. Any other first line makes the whole file the body.
   */
  public static StageSkill of(String name, String skillMd, List<File> files) {
    var text = skillMd.startsWith("\uFEFF") ? skillMd.substring(1) : skillMd;
    var lines = text.lines().toList();
    if (lines.isEmpty() || !lines.getFirst().equals(FENCE)) {
      return new StageSkill(name, String.join("\n", lines).strip(), files);
    }
    var closing = lines.subList(1, lines.size()).indexOf(FENCE);
    if (closing < 0) {
      throw new IllegalArgumentException(
          "Skill '%s' has front matter that is never closed: end it with a line that is exactly %s."
              .formatted(name, FENCE));
    }
    var body = lines.subList(closing + 2, lines.size());
    return new StageSkill(name, String.join("\n", body).strip(), files);
  }

  private static boolean isRelative(String path) {
    return path != null
        && path.codePoints().noneMatch(Character::isISOControl)
        && Arrays.stream(path.split("/", -1))
            .noneMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."));
  }
}
