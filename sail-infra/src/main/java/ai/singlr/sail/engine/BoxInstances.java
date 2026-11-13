/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * What every instance on this box is (W10), read from Incus in one {@code incus list} and told from
 * its keys alone: a workspace, the one instance named {@code <project>-<workspace>} that carries
 * {@value #PROJECT_KEY} and {@value #WORKSPACE_KEY} of that pair; a template, the one named {@code
 * <project>--tmpl} that carries {@value #TEMPLATE_OF_KEY} from its launch; a stray, an instance
 * carrying either under another name (a hand copy); or plain, carrying neither, which is the
 * container of the project it is named after when the box holds one and otherwise not Sail's. Every
 * command that walks the box's instances reads them here, so none takes a workspace, a template or
 * a stray for a project.
 */
public final class BoxInstances {

  /** The project a workspace instance belongs to. */
  public static final String PROJECT_KEY = "user.sail.project";

  /** The workspace an instance is. */
  public static final String WORKSPACE_KEY = "user.sail.workspace";

  /** The project a template instance is the template of, set when it is launched. */
  public static final String TEMPLATE_OF_KEY = "user.sail.template.of";

  /** Written last when a workspace is made, so one without it is not whole (W12). */
  public static final String READY_KEY = "user.sail.ready";

  /** What a template's instance name ends with after its project's name. */
  public static final String TEMPLATE_SUFFIX = "--tmpl";

  /** What an instance is. */
  public sealed interface Kind {}

  /**
   * Carries no workspace or template key: the container of the project it is named after when the
   * box holds that project, and otherwise not Sail's.
   */
  public record Plain() implements Kind {}

  /** Workspace {@code workspace} of {@code project}, {@code ready} once it was made whole. */
  public record Workspace(String project, String workspace, boolean ready) implements Kind {}

  /** The template of {@code project}. */
  public record Template(String project) implements Kind {}

  /**
   * Carries the keys of workspace {@code workspace} of {@code project} (null for a template's)
   * under a name that is not that workspace's or template's: a copy, which is none of them.
   */
  public record Stray(String project, String workspace) implements Kind {}

  /**
   * An instance as Incus lists it: its name, state and limits, its type, the pool its root disk is
   * on (null when it names none), its own configuration keys, when Incus created it, and its kind.
   */
  public record Instance(
      ContainerManager.ContainerInfo info,
      String type,
      String pool,
      Map<String, String> config,
      String createdAt) {

    public String name() {
      return info.name();
    }

    public ContainerState state() {
      return info.state();
    }

    /** The value of configuration key {@code key}, empty when the instance carries none. */
    public Optional<String> key(String key) {
      return Optional.ofNullable(config.get(key)).filter(value -> !value.isBlank());
    }

    /** What this instance is, from its keys and its name. */
    public Kind kind() {
      var project = key(PROJECT_KEY);
      var workspace = key(WORKSPACE_KEY);
      if (project.isPresent() && workspace.isPresent()) {
        return name().equals(project.get() + "-" + workspace.get())
            ? new Workspace(project.get(), workspace.get(), key(READY_KEY).isPresent())
            : new Stray(project.get(), workspace.get());
      }
      var templateOf = key(TEMPLATE_OF_KEY);
      if (templateOf.isPresent()) {
        return name().equals(templateOf.get() + TEMPLATE_SUFFIX)
            ? new Template(templateOf.get())
            : new Stray(templateOf.get(), null);
      }
      return new Plain();
    }

    /** Whether this is a plain instance, the only kind a project's own container is. */
    public boolean plain() {
      return kind() instanceof Plain;
    }

    /**
     * Whether this is project {@code project}'s own container, its template or a workspace of it.
     */
    public boolean belongsTo(String project) {
      return switch (kind()) {
        case Plain ignored -> name().equals(project);
        case Workspace workspace -> workspace.project().equals(project);
        case Template template -> template.project().equals(project);
        case Stray ignored -> false;
      };
    }
  }

  private final ShellExec shell;

  public BoxInstances(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /** Every instance Incus has, read in one {@code incus list}. */
  public List<Instance> all() throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "list", "--format", "json"));
    if (!result.ok()) {
      throw new IOException("incus list failed: " + result.stderr());
    }
    return YamlUtil.parseList(result.stdout()).stream().map(BoxInstances::instance).toList();
  }

  /** Every instance that is a system container, not a virtual machine: sail provisions none. */
  public List<Instance> containers() throws IOException, InterruptedException, TimeoutException {
    return all().stream().filter(instance -> "container".equals(instance.type())).toList();
  }

  /** Every plain instance, the only kind that can be a project's own container. */
  public List<Instance> plain() throws IOException, InterruptedException, TimeoutException {
    return all().stream().filter(Instance::plain).toList();
  }

  /** The instance named {@code name}, when Incus has one. */
  public Optional<Instance> named(String name)
      throws IOException, InterruptedException, TimeoutException {
    return all().stream().filter(instance -> instance.name().equals(name)).findFirst();
  }

  /**
   * Refuses to act on {@code instance}, the instance named {@code project}, as that project's own
   * container when it is a workspace, a template or a stray, naming what it is: a project is never
   * given another's instance (W10).
   */
  public static void requireNotTakenFor(String project, Optional<Instance> instance) {
    var kind = instance.map(Instance::kind).orElse(new Plain());
    var what =
        switch (kind) {
          case Plain ignored -> null;
          case Workspace workspace ->
              "workspace '" + workspace.workspace() + "' of project " + workspace.project();
          case Template template -> "the template of project " + template.project();
          case Stray stray -> "a copy carrying the keys of project " + stray.project();
        };
    if (what != null) {
      throw Remedied.state(
          "Instance '"
              + project
              + "' is "
              + what
              + ", not a project's container: project "
              + project
              + " may not take it.",
          switch (kind) {
            case Workspace workspace ->
                Remedy.of(
                    "Use the workspace's own commands, e.g. ",
                    Remedy.sail(
                        "project workspace shell", workspace.project(), workspace.workspace()),
                    ".");
            case Template template ->
                Remedy.of(
                    "Only ",
                    Remedy.sail("project template build", template.project()),
                    " changes it.");
            default -> Remedy.of("Delete the copy, or its keys, if it is not wanted.");
          });
    }
  }

  static Instance instance(Map<String, Object> entry) {
    var config = new LinkedHashMap<String, String>();
    if (entry.get("config") instanceof Map<?, ?> keys) {
      keys.forEach((key, value) -> config.put(String.valueOf(key), String.valueOf(value)));
    }
    var pool =
        entry.get("expanded_devices") instanceof Map<?, ?> devices
                && devices.get("root") instanceof Map<?, ?> root
                && root.get("pool") instanceof String named
            ? named
            : null;
    return new Instance(
        ContainerManager.info(entry),
        Objects.toString(entry.get("type"), null),
        pool,
        Map.copyOf(config),
        Objects.toString(entry.get("created_at"), null));
  }
}
