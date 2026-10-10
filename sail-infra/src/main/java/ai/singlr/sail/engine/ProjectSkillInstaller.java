/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.gen.SpecSkillGenerator;
import ai.singlr.sail.harness.Harness;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Puts the project's skills, and sail's own {@code spec-board}, where a harness looks for skills,
 * so the folders a harness sees are the skills the project holds. Each folder carries a stamp, the
 * fingerprint of the skill it holds: an install that finds a skill's stamp writes nothing for it,
 * and any other install replaces the folder whole. A stamped folder whose skill the project no
 * longer holds is removed; a folder without a stamp is not sail's and is left alone — and one that
 * stands where a held skill's folder goes is not replaced either: the install fails, naming it, so
 * a person's own skill under a name the project later takes is never deleted. The one unstamped
 * folder sail replaces is the {@code spec-board} it wrote before it stamped (0.46 and earlier),
 * known by its content: nothing in it but regular files, no link or folder among them, of the
 * skill's own names, each holding byte for byte what a release of sail wrote ({@link
 * SpecSkillGenerator#UNSTAMPED_HASHES}). A file of another content, a name sail never wrote, or a
 * folder or link under one of its names is someone's, and the folder is refused.
 *
 * <p>A replacement is built outside the skills directory, beside it in a folder of the install's
 * own, stamped last and put in place in one step — so a harness never opens a folder half-written
 * and never finds a build among its skills. Putting it in place is removing the old folder and
 * renaming the build to it, and an install does both holding a lock on the skills directory: two
 * installs that replace one folder at once take turns, each leaves a whole, stamped folder, and
 * neither build lands inside the other's. The rename is {@code mv -T}, which fails on a folder
 * something else made and filled in between, where a plain {@code mv} would move the build into it.
 *
 * <p>A build folder's name starts {@link #BUILD_PREFIX}, which no skill's name can, so nothing else
 * beside the skills directory is ever taken for a build. An install that fails removes what it
 * built. One that died left its build folder behind, and the next install of that skill removes it
 * once it is a day old: a sweep of every build folder would delete the files of an install building
 * beside this one, and a younger one may still be a live install's, since writes into a build's
 * subfolders leave the build's own modification time alone.
 *
 * <p>A skill's name and paths were checked when the {@link StageSkill} was made; here each reaches
 * a shell only as an argument.
 */
public final class ProjectSkillInstaller {

  /** How old a build folder is before the install that made it is taken for dead. */
  static final int STALE_BUILD_MINUTES = 24 * 60;

  /** What a build folder's name starts with, before the skill's name and the install's id. */
  static final String BUILD_PREFIX = ".sail-stage-build-";

  private static final String SWEEP =
      "[ ! -d \"$1\" ] || find \"$1\" -maxdepth 1 -name \"$2.*\" -mmin \"+$3\" -exec rm -rf {} +";
  private static final String PLACE =
      """
      refuse() {
        echo "$1 holds a skill sail did not install (no $2 in it). Move it aside, or give the project's skill another name." >&2
        exit 1
      }
      if { [ -e "$1" ] || [ -L "$1" ]; } && [ ! -f "$1/$3" ]; then
        if [ -z "$4" ] || [ -L "$1" ] || [ ! -d "$1" ]; then refuse "$1" "$3"; fi
        for entry in "$1"/* "$1"/.[!.]* "$1"/..?*; do
          if [ -e "$entry" ] || [ -L "$entry" ]; then
            if [ -L "$entry" ] || [ ! -f "$entry" ]; then refuse "$1" "$3"; fi
            sum=$(sha256sum < "$entry") || refuse "$1" "$3"
            case " $4 " in *" ${entry##*/}=${sum%% *} "*) ;; *) refuse "$1" "$3" ;; esac
          fi
        done
      fi
      rm -rf "$1" && mv -T "$2" "$1"
      """;
  private static final String STAMPED =
      "for d in \"$1\"/*/; do [ -f \"$d" + StageSkill.STAMP + "\" ] && basename \"$d\"; done; :";

  private ProjectSkillInstaller() {}

  /**
   * What one install of a project's skills did: the names it installed (the project's valid folders
   * and {@code spec-board}) and, for each folder skipped, one sentence naming it and why.
   */
  public record Report(List<String> installed, List<String> skipped) {}

  /**
   * Installs {@code project}'s skills, as {@code skills} reads them now, and {@code spec-board} for
   * {@code harness}: every valid folder whole and stamped, every stamped folder the project no
   * longer holds removed, and every folder that is no skill skipped and reported.
   */
  public static Report installProject(
      ShellExec shell, String project, Harness harness, ProjectSkills skills)
      throws IOException, InterruptedException, TimeoutException {
    var held = new ArrayList<StageSkill>();
    var skipped = new ArrayList<String>();
    for (var folder : skills.all(project)) {
      switch (folder) {
        case ProjectSkills.Folder.Held found -> held.add(found.skill());
        case ProjectSkills.Folder.Invalid invalid ->
            skipped.add("skill folder '" + invalid.name() + "' skipped: " + invalid.why());
      }
    }
    held.add(SpecSkillGenerator.skill());
    installAll(
        shell,
        project,
        skillsDirOf(harness),
        held,
        (skill, file) -> skills.open(project, skill, file));
    return new Report(held.stream().map(StageSkill::name).toList(), List.copyOf(skipped));
  }

  /** The absolute skills directory of {@code harness} in a container, without a trailing slash. */
  public static String skillsDirOf(Harness harness) {
    var dir = ContainerExec.DEV_HOME + "/" + harness.skillsDir();
    return dir.substring(0, dir.length() - 1);
  }

  /**
   * Makes {@code skillsDir} in {@code project}'s container hold exactly {@code skills}, each whole
   * and stamped, and no other folder sail stamped.
   *
   * @param skillsDir a harness's skills directory, absolute, directly under the home directory the
   *     replacements are built in
   * @param content the bytes of one file of one of the skills
   */
  public static void installAll(
      ShellExec shell,
      String project,
      String skillsDir,
      List<StageSkill> skills,
      BiFunction<StageSkill, StageSkill.File, InputStream> content)
      throws IOException, InterruptedException, TimeoutException {
    var installId = DateTimeUtils.newId().toString();
    var held = new HashSet<String>();
    for (var skill : skills) {
      held.add(skill.name());
      install(
          shell,
          project,
          skillsDir + "/" + skill.name(),
          installId,
          skill,
          file -> content.apply(skill, file));
    }
    for (var stale : stamped(shell, project, skillsDir)) {
      if (!held.contains(stale)) {
        var removed =
            shell.exec(
                ContainerExec.asDevUser(project, List.of("rm", "-rf", skillsDir + "/" + stale)));
        if (!removed.ok()) {
          throw new IOException(
              "Failed to remove the skill folder '%s' the project no longer holds in %s: %s"
                  .formatted(stale, project, removed.stderr()));
        }
      }
    }
  }

  /** The names of the folders sail stamped under {@code skillsDir}. */
  private static List<String> stamped(ShellExec shell, String project, String skillsDir)
      throws IOException, InterruptedException, TimeoutException {
    var listed =
        shell.exec(ContainerExec.asDevUser(project, List.of("sh", "-c", STAMPED, "sh", skillsDir)));
    if (!listed.ok()) {
      throw new IOException(
          "Failed to list the installed skills in " + project + ": " + listed.stderr());
    }
    return listed.stdout().lines().map(String::strip).filter(name -> !name.isEmpty()).toList();
  }

  /**
   * Makes {@code folder} in {@code project}'s container hold exactly {@code skill}.
   *
   * @param folder the skill's folder, absolute, directly under the harness's skills directory,
   *     which is itself directly under the home directory the replacement is built in
   * @param installId the install, which names the folder the replacement is built in
   * @param content the bytes of one of the skill's files
   */
  static void install(
      ShellExec shell,
      String project,
      String folder,
      String installId,
      StageSkill skill,
      Function<StageSkill.File, InputStream> content)
      throws IOException, InterruptedException, TimeoutException {
    var fingerprint = fingerprint(skill);
    var stamp =
        shell.exec(
            ContainerExec.asDevUser(project, List.of("cat", folder + "/" + StageSkill.STAMP)));
    if (stamp.ok() && stamp.stdout().strip().equals(fingerprint)) {
      return;
    }
    var skillsDir = parentOf(folder);
    var builds = BUILD_PREFIX + skill.name();
    var buildRoot = parentOf(skillsDir);
    run(
        shell,
        project,
        "clear stale builds of",
        skill,
        List.of("sh", "-c", SWEEP, "sh", buildRoot, builds, String.valueOf(STALE_BUILD_MINUTES)));
    var build = buildRoot + "/" + builds + "." + installId;
    try {
      for (var file : skill.files()) {
        push(shell, project, build + "/" + file.path(), file, content);
      }
      ContainerSailSetup.writeStamp(shell, project, build + "/" + StageSkill.STAMP, fingerprint);
      run(
          shell,
          project,
          "make the skills directory for",
          skill,
          List.of("mkdir", "-p", skillsDir));
      run(
          shell,
          project,
          "put in place",
          skill,
          List.of(
              "flock",
              skillsDir,
              "sh",
              "-c",
              PLACE,
              "sh",
              folder,
              build,
              StageSkill.STAMP,
              legacyFilesOf(skill)));
    } catch (IOException | InterruptedException | TimeoutException | RuntimeException failure) {
      discard(shell, project, build, failure);
      throw failure;
    }
  }

  /**
   * The files an unstamped folder of {@code skill} may hold and still be the one sail wrote before
   * it stamped, each as {@code <name>=<sha256>}: {@code spec-board}'s own, and none for any other
   * skill.
   */
  private static String legacyFilesOf(StageSkill skill) {
    if (!skill.name().equals(SpecSkillGenerator.NAME)) {
      return "";
    }
    return SpecSkillGenerator.UNSTAMPED_HASHES.entrySet().stream()
        .flatMap(file -> file.getValue().stream().map(hash -> file.getKey() + "=" + hash))
        .sorted()
        .collect(Collectors.joining(" "));
  }

  /**
   * What an installed folder is stamped with: every file's path under the skill's name, with its
   * content hash and mode, in path order. A file added, changed, removed or re-moded changes it.
   */
  public static String fingerprint(StageSkill skill) {
    var files = new LinkedHashMap<String, String>();
    for (var file : skill.files()) {
      files.put(skill.name() + "/" + file.path(), file.contentHash() + " " + mode(file));
    }
    return ContainerSailSetup.fingerprintOf(files);
  }

  private static void push(
      ShellExec shell,
      String project,
      String remotePath,
      StageSkill.File file,
      Function<StageSkill.File, InputStream> content)
      throws IOException, InterruptedException, TimeoutException {
    var made =
        shell.exec(ContainerExec.asDevUser(project, List.of("mkdir", "-p", parentOf(remotePath))));
    if (!made.ok()) {
      throw new IOException("Failed to create " + parentOf(remotePath) + ": " + made.stderr());
    }
    var staged = Files.createTempFile("sail-skill-", ".tmp");
    try {
      try (var bytes = content.apply(file)) {
        Files.copy(bytes, staged, StandardCopyOption.REPLACE_EXISTING);
      }
      ContainerFilePush.push(
          shell,
          project,
          remotePath,
          staged,
          List.of(
              "--uid",
              ContainerExec.DEV_UID,
              "--gid",
              ContainerExec.DEV_GID,
              "--mode",
              mode(file)));
    } finally {
      Files.deleteIfExists(staged);
    }
  }

  private static void run(
      ShellExec shell, String project, String what, StageSkill skill, List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(ContainerExec.asDevUser(project, command));
    if (!result.ok()) {
      throw new IOException(
          "Failed to %s skill '%s' in %s: %s"
              .formatted(what, skill.name(), project, result.stderr()));
    }
  }

  /** Removes what a failed install built; a container that cannot is the failure's footnote. */
  private static void discard(ShellExec shell, String project, String build, Exception failure) {
    try {
      shell.exec(ContainerExec.asDevUser(project, List.of("rm", "-rf", build)));
    } catch (IOException | InterruptedException | TimeoutException | RuntimeException cleanup) {
      failure.addSuppressed(cleanup);
    }
  }

  private static String mode(StageSkill.File file) {
    return String.format("%04o", file.mode());
  }

  private static String parentOf(String path) {
    return path.substring(0, path.lastIndexOf('/'));
  }
}
