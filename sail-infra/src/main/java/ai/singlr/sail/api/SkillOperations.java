/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Action;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProjectSkillInstaller;
import ai.singlr.sail.engine.SailSkills;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SkillFolders;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.store.ProjectStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * A project's skills as every door shows and changes them: the devbox's {@code sail project skills}
 * and the container's {@code skill}. Each verb is one operation here, so a skill shared from either
 * is the same write, and a listing from either is the same rows.
 *
 * <p>A listing names every skill an agent of the project can see: the project's own, shared and
 * synced; sail's; and the ones someone made by hand in an installed agent's skills folder, which
 * reach that agent alone and no other box until someone shares them. A folder is never shared but
 * on a person's word: sail lists a hand-made skill and names the line that shares it, nothing more.
 *
 * <p>Sharing and removing are {@link Action#SHARE} of the project, asked of the bound actor ({@link
 * ProjectGate#taking}) before anything is read, the container's folder or the box's: a refused
 * caller costs no read, and hears what the journal would have said of the write.
 */
public final class SkillOperations {

  private final ProjectSkills skills;
  private final ShellExec shell;
  private final ProjectLoader projects;
  private final ProjectStore catalog;

  SkillOperations(
      ProjectSkills skills, ShellExec shell, ProjectLoader projects, ProjectStore catalog) {
    this.skills = Objects.requireNonNull(skills, "skills");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.projects = Objects.requireNonNull(projects, "projects");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  /** Where a skill an agent sees comes from. */
  public enum Kind {
    /** The project's own, among its shared files: on every box, for every agent. */
    SHARED,
    /** Sail's own, written by this binary. */
    SAIL,
    /** Made by hand in one agent's skills folder in this container: that agent's alone. */
    LOCAL
  }

  /**
   * One skill an agent of the project can see.
   *
   * @param files how many files its folder holds; for a local one, unknown and 0
   * @param agents the agents that see it, by display name
   * @param status {@code ok}, {@code invalid: <why>}, or for a local one {@code not synced}
   * @param share for a local one, the host command a person shares it by from outside the
   *     container; otherwise empty
   */
  public record Row(
      String name,
      Kind kind,
      int files,
      List<String> agents,
      String status,
      Optional<Remedy.Command> share)
      implements Mappable {

    @Override
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("kind", kind.name().toLowerCase(Locale.ROOT));
      map.put("files", files);
      map.put("agents", agents);
      map.put("status", status);
      map.put("share", share.map(command -> Remedy.of(command).plain()).orElse(null));
      return map;
    }
  }

  /**
   * Every skill an agent of the project can see, and a note when some could not be read.
   *
   * @param note why the local skills are not listed, or empty
   */
  public record Rows(List<Row> rows, Optional<String> note) implements Mappable {

    @Override
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("skills", rows.stream().map(Row::toMap).toList());
      map.put("note", note.orElse(null));
      return map;
    }

    /**
     * The rows as a reader in the container reads them: with no command, since none it could run
     * there shares a skill.
     */
    public Rows inContainer() {
      return new Rows(
          rows.stream()
              .map(
                  row ->
                      new Row(
                          row.name(),
                          row.kind(),
                          row.files(),
                          row.agents(),
                          row.status(),
                          Optional.empty()))
              .toList(),
          note);
    }
  }

  /**
   * A skill just shared: its name, how many files it holds, the agents it is installed for, whether
   * that was done now, in the running container it was shared from, or is left to the next launch
   * or apply, what stood in an install's way, and what the standard advises about it.
   *
   * @param problems one sentence for each agent the skill could not be installed for now, such as a
   *     folder someone made by hand under its name holding other files
   */
  public record Shared(
      String name,
      int files,
      List<String> agents,
      boolean installed,
      List<String> problems,
      Optional<String> advice)
      implements Mappable {

    @Override
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("files", files);
      map.put("agents", agents);
      map.put("installed", installed);
      map.put("problems", problems);
      map.put("advice", advice.orElse(null));
      return map;
    }
  }

  /**
   * Every skill an agent of {@code project} can see: shared, then sail's, then local, by name. The
   * local ones are read from the container; when it cannot be read, as when it is stopped, they are
   * left out and the note says why.
   */
  public Rows rows(String project) throws InterruptedException, TimeoutException {
    var held = skills.all(project);
    var harnesses = ProjectProvisioner.installedHarnesses(projects.definition(project));
    var agents = harnesses.stream().map(Harness::displayName).toList();
    var rows = new ArrayList<Row>();
    held.stream()
        .map(folder -> shared(folder, agents))
        .sorted(Comparator.comparing(Row::name))
        .forEach(rows::add);
    SailSkills.all()
        .forEach(
            skill ->
                rows.add(
                    new Row(
                        skill.name(),
                        Kind.SAIL,
                        skill.files().size(),
                        agents,
                        "ok",
                        Optional.empty())));
    var taken = rows.stream().map(Row::name).toList();
    var local = new LinkedHashMap<String, List<Harness>>();
    try {
      for (var harness : harnesses) {
        for (var name :
            ProjectSkillInstaller.local(
                shell, project, ProjectSkillInstaller.skillsDirOf(harness))) {
          if (!taken.contains(name)) {
            local.computeIfAbsent(name, ignored -> new ArrayList<>()).add(harness);
          }
        }
      }
    } catch (IOException unreadable) {
      return new Rows(
          List.copyOf(rows), Optional.of(unreadable.getMessage() + ", so they are not listed."));
    }
    local.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> local(project, entry.getKey(), entry.getValue()))
        .forEach(rows::add);
    return new Rows(List.copyOf(rows), Optional.empty());
  }

  /**
   * Shares the folder at the absolute {@code folder} in {@code project}'s running container as
   * skill {@code name}, or the folder's own name when blank, read there as the dev user, then
   * installs the project's skills for every agent in that container at once: whoever shared it is
   * told now, not at the next apply, when a folder in an agent's way holds other files.
   */
  public Shared shareFromContainer(String project, String folder, String name)
      throws IOException, InterruptedException, TimeoutException {
    ProjectGate.taking(catalog, Action.SHARE, project);
    var harnesses = ProjectProvisioner.installedHarnesses(projects.loadRunning(project).config());
    var skillName = nameOf(folder, name);
    var skill =
        skills.share(
            project, skillName, SkillFolders.inContainer(shell, project, folder, skillName));
    var problems = new ArrayList<String>();
    for (var harness : harnesses) {
      try {
        ProjectSkillInstaller.installProject(shell, project, harness, skills);
      } catch (IOException | TimeoutException | RuntimeException blocked) {
        problems.add(notInstalled(harness, blocked));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        problems.add(notInstalled(harness, interrupted));
        break;
      }
    }
    return shared(skill, harnesses, problems.isEmpty(), problems);
  }

  /** Shares the folder at {@code folder} on this box as skill {@code name}, or its own name. */
  public Shared shareFromHost(String project, Path folder, String name) throws IOException {
    ProjectGate.taking(catalog, Action.SHARE, project);
    var harnesses = ProjectProvisioner.installedHarnesses(projects.definition(project));
    var source = folder.toAbsolutePath().normalize();
    var skillName = nameOf(source.toString(), name);
    var skill = skills.share(project, skillName, SkillFolders.onHost(source, skillName));
    return shared(skill, harnesses, false, List.of());
  }

  /**
   * Stops sharing {@code project}'s skill {@code name}: its folder is removed on every box after
   * sync, and from each agent's skills folder at the next install. Returns how many files it held.
   *
   * @throws ApiException {@code NOT_FOUND} when the project shares no skill of that name
   */
  public int remove(String project, String name) throws IOException {
    ProjectGate.taking(catalog, Action.SHARE, project);
    var removed = skills.remove(project, name);
    if (removed == 0) {
      throw new ApiException(
          ErrorCode.NOT_FOUND, "No skill '" + name + "' is shared on " + project + ".");
    }
    return removed;
  }

  /** The {@code SKILL.md} of {@code project}'s skill {@code name}, or of sail's own. */
  public String manifest(String project, String name) throws IOException {
    return skills.manifest(project, name);
  }

  /**
   * Why {@code harness} was not installed now: an install stops at the first skill it cannot put in
   * place, so the agent's skills after it wait too.
   */
  private static String notInstalled(Harness harness, Exception why) {
    return harness.displayName()
        + ": "
        + Objects.requireNonNullElse(why.getMessage(), why.getClass().getSimpleName()).strip()
        + " Its skills are installed at the next launch or apply once that is fixed.";
  }

  private static Shared shared(
      StageSkill skill, List<Harness> harnesses, boolean installed, List<String> problems) {
    return new Shared(
        skill.name(),
        skill.files().size(),
        harnesses.stream().map(Harness::displayName).toList(),
        installed,
        List.copyOf(problems),
        skill.advice());
  }

  private static String nameOf(String folder, String name) {
    if (name != null && !name.isBlank()) {
      return name;
    }
    var trimmed = folder.endsWith("/") ? folder.substring(0, folder.length() - 1) : folder;
    return trimmed.substring(trimmed.lastIndexOf('/') + 1);
  }

  private static Row shared(ProjectSkills.Folder folder, List<String> agents) {
    return switch (folder) {
      case ProjectSkills.Folder.Held held ->
          new Row(
              held.name(),
              Kind.SHARED,
              held.skill().files().size(),
              agents,
              "ok",
              Optional.empty());
      case ProjectSkills.Folder.Invalid invalid ->
          new Row(
              invalid.name(),
              Kind.SHARED,
              invalid.files(),
              List.of(),
              "invalid: " + invalid.why(),
              Optional.empty());
    };
  }

  /**
   * A skill only {@code harnesses} of {@code project}'s container hold, with the command a person
   * outside the container shares it by: nothing inside it may.
   */
  private static Row local(String project, String name, List<Harness> harnesses) {
    var first = harnesses.getFirst();
    return new Row(
        name,
        Kind.LOCAL,
        0,
        harnesses.stream().map(Harness::displayName).toList(),
        "not synced",
        Optional.of(
            StageSkill.shareFromContainer(
                ContainerExec.DEV_HOME + "/" + first.skillFolder(name), project)));
  }
}
