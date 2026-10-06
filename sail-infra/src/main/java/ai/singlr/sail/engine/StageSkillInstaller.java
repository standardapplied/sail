/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Puts a stage's skill where the harness that runs the stage looks for skills, so the folder a
 * harness sees is the skill that was fired. The folder carries a stamp, the fingerprint of the
 * skill it holds: a launch that finds its skill's stamp writes nothing, and any other launch
 * replaces the folder whole.
 *
 * <p>A replacement is built beside the folder, in a folder of the run's own, stamped last and put
 * in place in one step — so a harness never opens a folder half-written. Putting it in place is
 * removing the old folder and renaming the build to it, and a launch does both holding a lock on
 * the skills directory: two launches that replace one folder at once take turns, each leaves a
 * whole, stamped folder, and neither build lands inside the other's. The rename is {@code mv -T},
 * which fails on a folder something else made in between where a plain {@code mv} would move the
 * build into it.
 *
 * <p>A build folder's name starts {@link #BUILD_PREFIX}, which no skill's or rule's name can, so
 * nothing a project put beside the folder is ever taken for a build. A launch that fails removes
 * what it built. One that died left its build folder behind, and the next install of that skill
 * removes it once it is old enough that no live launch can still be writing it: a sweep of every
 * build folder would delete the files of a launch building beside this one.
 *
 * <p>A skill's name and paths were checked when the {@link StageSkill} was made; here each reaches
 * a shell only as an argument.
 */
public final class StageSkillInstaller {

  /** How old a build folder is before the launch that made it is taken for dead. */
  static final int STALE_BUILD_MINUTES = 60;

  /** What a build folder's name starts with, before the skill's name and the run's id. */
  static final String BUILD_PREFIX = ".sail-stage-build-";

  private static final String SWEEP =
      "[ ! -d \"$1\" ] || find \"$1\" -maxdepth 1 -name \"$2.*\" -mmin \"+$3\" -exec rm -rf {} +";
  private static final String STAMP = "printf '%s' \"$1\" > \"$2\"";
  private static final String PLACE = "rm -rf \"$1\" && mv -T \"$2\" \"$1\"";

  private StageSkillInstaller() {}

  /**
   * Makes {@code folder} in {@code project}'s container hold exactly {@code skill}.
   *
   * @param folder the skill's folder, absolute, under the harness's skills directory
   * @param runId the launching run, which names the folder the replacement is built in
   * @param content the bytes of one of the skill's files
   */
  public static void install(
      ShellExec shell,
      String project,
      String folder,
      String runId,
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
    run(
        shell,
        project,
        "clear stale builds of",
        skill,
        List.of("sh", "-c", SWEEP, "sh", skillsDir, builds, String.valueOf(STALE_BUILD_MINUTES)));
    var build = skillsDir + "/" + builds + "." + runId;
    try {
      for (var file : skill.files()) {
        push(shell, project, build + "/" + file.path(), file, content);
      }
      run(
          shell,
          project,
          "stamp",
          skill,
          List.of("bash", "-c", STAMP, "bash", fingerprint, build + "/" + StageSkill.STAMP));
      run(
          shell,
          project,
          "put in place",
          skill,
          List.of("flock", skillsDir, "sh", "-c", PLACE, "sh", folder, build));
    } catch (IOException | InterruptedException | TimeoutException | RuntimeException failure) {
      discard(shell, project, build, failure);
      throw failure;
    }
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
