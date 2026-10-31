/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A skill: an <a href="https://agentskills.io/specification">Agent Skills</a> folder, a {@code
 * SKILL.md} (front matter, then instructions) and optional scripts and reference files, installed
 * where a harness looks for skills and invoked when the agent judges it relevant. A project holds
 * its own among its files, and sail ships one, {@code spec-board}.
 *
 * <p>A skill's name, paths, front matter and body are untrusted input, checked here once against
 * the standard's rules: a value of this type is one every client of the standard reads, is bounded,
 * and names only files inside its own folder. The front matter is checked, never rewritten: a key
 * the standard does not define, such as a harness's own {@code argument-hint}, passes through.
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

  /** The most characters a skill's {@code description} may hold. */
  public static final int MAX_DESCRIPTION = 1024;

  /** The most characters a skill's {@code compatibility} may hold. */
  public static final int MAX_COMPATIBILITY = 500;

  /** The most lines the standard recommends a {@code SKILL.md} body hold. */
  public static final int ADVISED_LINES = 500;

  private static final int MAX_NAME = 64;
  private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final String FENCE = "---";
  private static final Pattern LOCATION = Pattern.compile("^\\s+in reader, line (\\d+),");

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
    requireWithinBounds(name, files.size(), files.stream().mapToLong(File::size).sum());
    files = files.stream().sorted(Comparator.comparing(File::path)).toList();
  }

  /**
   * Refuses a folder of {@code files} files and {@code bytes} bytes in all that no skill may be,
   * before a byte of it is read.
   */
  public static void requireWithinBounds(String name, int files, long bytes) {
    if (files > MAX_FILES) {
      throw new IllegalArgumentException(
          "Skill '%s' has %d files; the limit is %d.".formatted(name, files, MAX_FILES));
    }
    if (bytes > MAX_BYTES) {
      throw new IllegalArgumentException(
          "Skill '%s' is %d bytes; the limit is %d.".formatted(name, bytes, MAX_BYTES));
    }
  }

  /**
   * Whether {@code name} can name a skill, by the standard's rule: 1 to 64 lowercase letters,
   * digits and hyphens, with no hyphen first, last or next to another. Safe as a folder's name.
   */
  public static boolean isName(String name) {
    return name != null && name.length() <= MAX_NAME && NAME.matcher(name).matches();
  }

  /** Refuses a {@code name} that cannot name a skill, before anything is looked up under it. */
  public static void requireName(String name) {
    if (!isName(name)) {
      throw new IllegalArgumentException(
          "Skill name '"
              + name
              + "' must be 1 to 64 lowercase letters, digits and hyphens, with no hyphen first,"
              + " last or next to another.");
    }
  }

  /**
   * What the standard advises about this skill and sail does not refuse: a body longer than {@link
   * #ADVISED_LINES}, said in one sentence, or empty.
   */
  public Optional<String> advice() {
    var lines = body.lines().count();
    return lines > ADVISED_LINES
        ? Optional.of(
            "Skill '%s' has a SKILL.md body of %d lines; the Agent Skills standard advises under %d, with detail moved to files under references/."
                .formatted(name, lines, ADVISED_LINES))
        : Optional.empty();
  }

  /**
   * The skill a {@code SKILL.md} describes, and the only reader of one. A leading byte-order mark
   * is dropped and line endings are read as {@code \n}. The first line is exactly {@code ---}, the
   * front matter runs to the next such line, and the body is what follows, stripped. The front
   * matter is YAML holding the standard's fields: {@code name}, the folder's own, and {@code
   * description}, both required; {@code license}, {@code compatibility}, {@code metadata} and
   * {@code allowed-tools} when present. A file with no front matter, front matter that is never
   * closed or is not YAML, or a field that breaks the standard is refused, naming the field.
   */
  public static StageSkill of(String name, String skillMd, List<File> files) {
    var text = skillMd.startsWith("\uFEFF") ? skillMd.substring(1) : skillMd;
    var lines = text.lines().toList();
    if (lines.isEmpty() || !lines.getFirst().equals(FENCE)) {
      throw new IllegalArgumentException(
          "Skill '%s' has no front matter: start %s with a line '%s', then its name and description, then '%s'."
              .formatted(name, MANIFEST, FENCE, FENCE));
    }
    var closing = lines.subList(1, lines.size()).indexOf(FENCE);
    if (closing < 0) {
      throw new IllegalArgumentException(
          "Skill '%s' has front matter that is never closed: end it with a line that is exactly %s."
              .formatted(name, FENCE));
    }
    requireFrontMatter(name, String.join("\n", lines.subList(1, closing + 1)));
    var body = lines.subList(closing + 2, lines.size());
    return new StageSkill(name, String.join("\n", body).strip(), files);
  }

  private static void requireFrontMatter(String name, String yaml) {
    Map<String, Object> fields;
    try {
      fields = YamlUtil.parseMapStrict(yaml);
    } catch (IllegalArgumentException notYaml) {
      throw new IllegalArgumentException(
          "Skill '%s' has front matter that is not YAML: %s."
              .formatted(name, yamlProblem(notYaml.getMessage())));
    }
    if (fields.isEmpty()
        && yaml.lines().anyMatch(line -> !line.isBlank() && !line.strip().startsWith("#"))) {
      throw new IllegalArgumentException(
          "Skill '%s' has front matter that is not a map of fields: write each as 'field: value'."
              .formatted(name));
    }
    var declared = text(name, fields, "name", true);
    if (!declared.equals(name)) {
      throw new IllegalArgumentException(
          "Skill '%s' names itself '%s' in its front matter: a skill's name is its folder's."
              .formatted(name, declared));
    }
    var description = text(name, fields, "description", true);
    if (description.isBlank()) {
      throw new IllegalArgumentException(
          "Skill '%s' has a blank description: say what it does and when to use it."
              .formatted(name));
    }
    requireAtMost(name, "description", description, MAX_DESCRIPTION);
    text(name, fields, "license", false);
    requireAtMost(
        name, "compatibility", text(name, fields, "compatibility", false), MAX_COMPATIBILITY);
    text(name, fields, "allowed-tools", false);
    requireMetadata(name, fields.get("metadata"));
  }

  private static String text(
      String name, Map<String, Object> fields, String field, boolean required) {
    var value = fields.get(field);
    if (value == null && !required) {
      return "";
    }
    if (value == null) {
      throw new IllegalArgumentException(
          "Skill '%s' has no %s in its front matter: the Agent Skills standard requires one."
              .formatted(name, field));
    }
    if (!(value instanceof String string)) {
      throw new IllegalArgumentException(
          "Skill '%s' has a %s that is not text: quote it.".formatted(name, field));
    }
    return string;
  }

  private static void requireAtMost(String name, String field, String value, int limit) {
    var characters = value.codePointCount(0, value.length());
    if (characters > limit) {
      throw new IllegalArgumentException(
          "Skill '%s' has a %s of %d characters; the limit is %d."
              .formatted(name, field, characters, limit));
    }
  }

  private static void requireMetadata(String name, Object metadata) {
    if (metadata == null) {
      return;
    }
    if (!(metadata instanceof Map<?, ?> entries)) {
      throw new IllegalArgumentException(
          "Skill '%s' has metadata that is not a map of text to text.".formatted(name));
    }
    for (var entry : entries.entrySet()) {
      if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
        throw new IllegalArgumentException(
            "Skill '%s' has metadata.%s that is not text: quote it."
                .formatted(name, entry.getKey()));
      }
    }
  }

  /**
   * The parser's own problem, without its echo of the text: its last unindented line, then where
   * the next location line puts it, as a line of the front matter.
   */
  private static String yamlProblem(String message) {
    var lines = message.lines().toList();
    var problem = 0;
    for (var i = 1; i < lines.size(); i++) {
      if (!lines.get(i).isBlank() && !Character.isWhitespace(lines.get(i).charAt(0))) {
        problem = i;
      }
    }
    var text = lines.get(problem).replaceFirst("^Malformed document: ", "");
    return lines.subList(problem + 1, lines.size()).stream()
        .map(LOCATION::matcher)
        .filter(Matcher::find)
        .findFirst()
        .map(at -> text + " at line " + at.group(1) + " of the front matter")
        .orElse(text);
  }

  private static boolean isRelative(String path) {
    return path != null
        && path.codePoints().noneMatch(Character::isISOControl)
        && Arrays.stream(path.split("/", -1))
            .noneMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."));
  }
}
