/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The box's template builds as sail-api runs them: a build started on a thread of its own, so it
 * outlives the request that started it, at most one per project in this process and none while
 * another process builds it, and what each project's template is now. A build cut short (its record
 * has no outcome, and its lock is free) is ended by the first read that finds it, and by {@link
 * #endCutShort} when sail-api starts, so no builder's token waits in its template.
 */
public final class TemplateBuilds {

  /** Why a build cut short ended, said to a person. */
  public static final String CUT_SHORT = "The build stopped when the runner restarted.";

  /** What a project's template is, for a person. */
  public enum State {
    /** A version is current: workspaces are created from it. */
    READY,
    /** No version is current, and no build of one failed. */
    MISSING,
    /** It is being built now. */
    BUILDING,
    /** No version is current, and the last build failed or was cut short. */
    FAILED;

    /** As the wire says it. */
    public String wire() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * A project's template now: its state, the version workspaces are created from when one is
   * current, whether that version was built from the definition as it is, and its last build's
   * record, running or ended; {@code joined} when a request that asked for a build found this one
   * under way.
   */
  public record Status(
      String project,
      State state,
      Optional<WorkspaceTemplate.Template> current,
      boolean stale,
      Optional<TemplateRecord> build,
      boolean joined) {
    public Status {
      Objects.requireNonNull(project, "project");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(current, "current");
      Objects.requireNonNull(build, "build");
    }
  }

  private final TemplateBuild builds;
  private final WorkspaceTemplate lane;
  private final ShellExec shell;
  private final Function<String, Collection<String>> tokensOf;
  private final Consumer<Exception> log;
  private final Supplier<Instant> clock;
  private final ConcurrentHashMap<String, CompletableFuture<Optional<WorkspaceTemplate.Template>>>
      running = new ConcurrentHashMap<>();

  /**
   * @param builds keeps each build's record
   * @param lane the template lane every build runs
   * @param shell changes Incus, ending a build cut short
   * @param tokensOf the git tokens a builder, by handle, stored: what a cut build's template is
   *     searched for once stripped
   * @param log told of a failure of a build no request waits for any more, its whole cause
   */
  public TemplateBuilds(
      TemplateBuild builds,
      WorkspaceTemplate lane,
      ShellExec shell,
      Function<String, Collection<String>> tokensOf,
      Consumer<Exception> log,
      Supplier<Instant> clock) {
    this.builds = Objects.requireNonNull(builds, "builds");
    this.lane = Objects.requireNonNull(lane, "lane");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.tokensOf = Objects.requireNonNull(tokensOf, "tokensOf");
    this.log = Objects.requireNonNull(log, "log");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * {@code config}'s template now, never waiting on a build: its lock is probed, its record read
   * and Incus read once. A build cut short is ended first.
   */
  public Status status(ProjectDefinition config) throws Exception {
    return status(config, false);
  }

  /**
   * {@code config}'s template joined, when a build of it is under way: this process's, or another's
   * whose record has no outcome. A person joining one needs none of what starting one needs.
   */
  public Optional<Status> joining(ProjectDefinition config) throws Exception {
    return running.containsKey(config.name()) || runningElsewhere(config.name())
        ? Optional.of(status(config, true))
        : Optional.empty();
  }

  /**
   * Builds {@code config}'s template from {@code source}, which a door makes with {@link
   * TemplateBuild#source} and the record's {@code started} it is given here, unless one is under
   * way (this process's, or another's whose record has no outcome), which is joined. Answers once
   * the build holds the template's lock and its record says so, or once it ended without building,
   * when its version was current; a failure before the lock is held is thrown, a failure after is
   * the record's.
   */
  public Status start(
      ProjectDefinition config,
      String by,
      Function<Consumer<TemplateRecord>, WorkspaceTemplate.Source> source)
      throws Exception {
    var project = config.name();
    var answer = new CompletableFuture<Optional<WorkspaceTemplate.Template>>();
    var under = running.putIfAbsent(project, answer);
    if (under != null || runningElsewhere(project)) {
      if (under == null) {
        running.remove(project, answer);
      }
      return status(config, true);
    }
    Thread.ofVirtual()
        .name("template-build-" + project)
        .start(
            Actor.carrying(
                () -> {
                  try {
                    var built =
                        lane.ensure(
                            config, source.apply(record -> answer.complete(Optional.empty())));
                    answer.complete(Optional.of(built));
                  } catch (Exception failed) {
                    if (!answer.completeExceptionally(failed)) {
                      log.accept(failed);
                    }
                  } finally {
                    running.remove(project, answer);
                  }
                }));
    Optional<WorkspaceTemplate.Template> ended;
    try {
      ended = answer.get(Workspaces.CREATE_WAIT.toSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException stillWaiting) {
      return status(config, true);
    } catch (ExecutionException failed) {
      throw failed.getCause() instanceof Exception cause ? cause : failed;
    }
    var status = status(config, false);
    return ended.filter(template -> !template.built()).isPresent()
        ? new Status(
            project,
            status.state(),
            status.current(),
            status.stale(),
            Optional.of(nothingToBuild(by)),
            false)
        : status;
  }

  /**
   * Ends every build of {@code projects} cut short, as sail-api starts: each found with a record
   * that has no outcome and a free lock. One that cannot be ended is logged and left to the next
   * read.
   */
  public void endCutShort(Collection<String> projects) {
    for (var project : projects) {
      try {
        endCutShort(project);
      } catch (Exception failed) {
        log.accept(failed);
      }
    }
  }

  /**
   * The state of each of {@code projects}' templates, as {@link #status} reads one: Incus listed
   * once for all of them.
   */
  public Map<String, State> states(Collection<String> projects) throws Exception {
    var currents = lane.peekAll();
    var states = new TreeMap<String, State>();
    for (var project : projects) {
      states.put(project, read(project, Optional.ofNullable(currents.get(project))).state());
    }
    return states;
  }

  private Status status(ProjectDefinition config, boolean joined) throws Exception {
    var project = config.name();
    var now = read(project, lane.peek(project));
    return new Status(
        project,
        now.state(),
        now.current(),
        now.current().map(t -> !t.inputs().equals(inputsOf(config))).orElse(false),
        now.build(),
        joined && now.state() == State.BUILDING);
  }

  /**
   * {@code project}'s template whose current version is {@code current}: a build cut short ended
   * first, then its lock probed and its record read.
   */
  private Status read(String project, Optional<WorkspaceTemplate.Template> current)
      throws Exception {
    try {
      endCutShort(project);
    } catch (Exception failed) {
      log.accept(failed);
    }
    var record = builds.record(project);
    var building = record.filter(TemplateRecord::running).isPresent() && builds.building(project);
    return new Status(project, stateOf(building, current, record), current, false, record, false);
  }

  private TemplateRecord nothingToBuild(String by) {
    var now = clock.get();
    return TemplateRecord.started(by, now, TemplateBuild.STEPS)
        .ended(TemplateRecord.Outcome.CURRENT, now, null, null);
  }

  private static State stateOf(
      boolean building,
      Optional<WorkspaceTemplate.Template> current,
      Optional<TemplateRecord> record) {
    if (building) {
      return State.BUILDING;
    }
    if (current.isPresent()) {
      return State.READY;
    }
    return record
            .filter(
                last ->
                    last.running()
                        || last.outcome() == TemplateRecord.Outcome.FAILED
                        || last.outcome() == TemplateRecord.Outcome.INTERRUPTED)
            .isPresent()
        ? State.FAILED
        : State.MISSING;
  }

  private boolean runningElsewhere(String project) throws IOException, InterruptedException {
    return builds.record(project).filter(TemplateRecord::running).isPresent()
        && builds.building(project);
  }

  private void endCutShort(String project) throws Exception {
    var record = builds.record(project).filter(TemplateRecord::running);
    if (record.isEmpty() || running.containsKey(project)) {
      return;
    }
    builds.endCutShort(project, shell, tokensOf.apply(record.get().by()), CUT_SHORT);
  }

  private static String inputsOf(ProjectDefinition config) {
    return ProjectProvisioner.inputs(config, Optional.empty());
  }
}
