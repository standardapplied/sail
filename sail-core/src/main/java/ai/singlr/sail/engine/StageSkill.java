/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * How one stage of the loop does its work: the instructions sail puts in that stage's prompt, and
 * the folder a harness finds them in. A skill is a folder with a {@code SKILL.md} (front matter,
 * then instructions) and optional scripts and reference files; sail ships one per stage and a
 * project may hold its own. Sail fires the skill itself, by rendering {@link #block} into the
 * prompt, so no launch depends on a harness deciding to load it.
 *
 * <p>A skill's name, paths and body are untrusted input, checked here once: a value of this type is
 * bounded and names only files inside its own folder.
 *
 * @param name the skill's name, which is its folder's
 * @param body the instructions, without front matter
 * @param files every file of the folder, {@code SKILL.md} included, in path order
 */
public record StageSkill(String name, String body, List<File> files) {

  /** The skill the build runs under when a project names none. */
  public static final String BUILD = "sail-build";

  /** The skill an agent review stage runs under when a project names none. */
  public static final String REVIEW = "sail-review";

  /** The skill the fix agent runs under when a project names none. */
  public static final String FIX = "sail-fix";

  /** The file every skill has: its front matter and instructions. */
  public static final String MANIFEST = "SKILL.md";

  /** The file sail writes into an installed folder to say which skill it holds. */
  public static final String STAMP = ".sail-skill";

  /** The most files a skill's folder may hold, {@code SKILL.md} included. */
  public static final int MAX_FILES = 32;

  /** The most bytes a skill's folder may hold, {@code SKILL.md} included. */
  public static final long MAX_BYTES = 1024 * 1024;

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
    if (!isName(name)) {
      throw new IllegalArgumentException(
          "Skill name '" + name + "' must match " + NAME.pattern() + ".");
    }
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

  /**
   * The skill a {@code SKILL.md} describes, and the only reader of one. A leading byte-order mark
   * is dropped and line endings are read as {@code \n}. When the first line is exactly {@code ---}
   * the front matter runs to the next such line and the body is what follows, stripped; front
   * matter that is never closed is refused. Any other first line makes the whole file the body.
   */
  public static StageSkill of(String name, String skillMd, List<File> files) {
    var text = skillMd.startsWith("﻿") ? skillMd.substring(1) : skillMd;
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

  /**
   * This skill as a prompt carries it, ending without a newline: the one rendering of a skill.
   *
   * @param folder where the harness that runs the prompt finds the skill's folder, named only when
   *     the skill has a file besides {@code SKILL.md}
   */
  public String block(String folder) {
    var block = "## How to do this work (skill: " + name + ")\n\n" + body;
    return files.size() > 1 ? block + "\n\nThe skill's other files are in " + folder + "." : block;
  }

  private static boolean isRelative(String path) {
    return path != null
        && path.codePoints().noneMatch(Character::isISOControl)
        && List.of(path.split("/", -1)).stream()
            .noneMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."));
  }
}
