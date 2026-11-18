/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.identity.Actor;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The box's template builds as sail-api runs them: each on a thread of its own, so it outlives the
 * request that started it, at most one per project in this process, and what each project's
 * template is now. A read never changes anything and never waits: it probes the template's lock,
 * reads the record and reads Incus. A build cut short (its record has no outcome, and its lock is
 * free) reads as interrupted; it is cleaned once, as sail-api starts ({@link #endCutShort}), or by
 * the next build, whose lane strips the template whatever happens.
 */
public final class TemplateBuildService {

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
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * A project's template now: its state, the version workspaces are created from when one is
   * current, whether that version was built from the definition as it is, and its last build's
   * record, running or ended; {@code joined} when a request that asked for a build found this one
   * under way, and {@code builtNothing} when it found the version current and built nothing.
   */
  public record Status(
      String project,
      State state,
      Optional<WorkspaceTemplate.Template> current,
      boolean stale,
      Optional<TemplateRecord> build,
      boolean joined,
      boolean builtNothing) {
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
  private final ConcurrentHashMap<String, CompletableFuture<Boolean>> running =
      new ConcurrentHashMap<>();
  private final Set<String> ending = ConcurrentHashMap.newKeySet();

  /**
   * @param builds keeps each build's record
   * @param lane the template lane every build runs
   * @param shell changes Incus, ending a build cut short
   * @param tokensOf the git tokens a builder, by handle, stores: what a cut build's template is
   *     searched for once stripped
   * @param log told of a failure no request waits for any more, its whole cause
   */
  public TemplateBuildService(
      TemplateBuild builds,
      WorkspaceTemplate lane,
      ShellExec shell,
      Function<String, Collection<String>> tokensOf,
      Consumer<Exception> log) {
    this.builds = Objects.requireNonNull(builds, "builds");
    this.lane = Objects.requireNonNull(lane, "lane");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.tokensOf = Objects.requireNonNull(tokensOf, "tokensOf");
    this.log = Objects.requireNonNull(log, "log");
  }

  /** {@code config}'s template now. */
  public Status status(ProjectDefinition config) throws Exception {
    return status(config, false);
  }

  /**
   * {@code config}'s template joined, when a build of it is under way: this process's, waited for
   * until it holds the lock, or another's whose record has no outcome. A person joining one needs
   * none of what starting one needs.
   */
  public Optional<Status> joining(ProjectDefinition config) throws Exception {
    var under = running.get(config.name());
    if (under != null) {
      return Optional.of(joined(config, under));
    }
    return builds.record(config.name()).filter(TemplateRecord::running).isPresent()
            && !ending.contains(config.name())
            && builds.building(config.name())
        ? Optional.of(status(config, true))
        : Optional.empty();
  }

  /**
   * Builds {@code config}'s template from {@code source}, which a door makes with {@link
   * TemplateBuild#source} and the {@code started} it is given here, or joins this process's build
   * of it. Answers once the build holds the template's lock and its record says so, or once it
   * ended without building, its version current; a failure before the lock is held is thrown, a
   * failure after is the record's.
   */
  public Status start(ProjectDefinition config, Function<Runnable, WorkspaceTemplate.Source> source)
      throws Exception {
    var project = config.name();
    var answer = new CompletableFuture<Boolean>();
    var under = running.putIfAbsent(project, answer);
    if (under != null) {
      return joined(config, under);
    }
    Thread.ofVirtual()
        .name("template-build-" + project)
        .start(
            Actor.carrying(
                () -> {
                  try {
                    lane.ensure(config, source.apply(() -> answer.complete(true)));
                    answer.complete(false);
                  } catch (Exception failed) {
                    if (!answer.completeExceptionally(failed)) {
                      log.accept(failed);
                    }
                  } finally {
                    running.remove(project, answer);
                  }
                }));
    var began = awaitHeld(answer);
    var now = status(config, false);
    return began
        ? now
        : new Status(
            now.project(), now.state(), now.current(), now.stale(), now.build(), false, true);
  }

  /**
   * Ends every build of {@code projects} cut short, once, as sail-api starts: each found with a
   * record that has no outcome and a free lock. One whose template cannot be cleaned is logged, and
   * its record says so.
   */
  public void endCutShort(Collection<String> projects) {
    for (var project : projects) {
      try {
        var record = builds.record(project).filter(TemplateRecord::running);
        if (record.isPresent()) {
          ending.add(project);
          builds.endCutShort(project, shell, tokensOf.apply(record.get().by()));
        }
      } catch (Exception failed) {
        log.accept(failed);
      } finally {
        ending.remove(project);
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

  private Status joined(ProjectDefinition config, CompletableFuture<Boolean> under)
      throws Exception {
    try {
      awaitHeld(under);
    } catch (Exception refusedToItsStarter) {
      var told = refusedToItsStarter;
    }
    return status(config, true);
  }

  /**
   * Waits until {@code build} holds the template's lock, or ended, no longer than {@link
   * Workspaces#CREATE_WAIT}: whether it began a build, false when it ended without one, its version
   * current; one that waits on another process's lock past it is answered as the record and the
   * lock say then.
   */
  private static boolean awaitHeld(CompletableFuture<Boolean> build) throws Exception {
    try {
      return build.get(Workspaces.CREATE_WAIT.toSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException stillWaiting) {
      return true;
    } catch (ExecutionException failed) {
      throw failed.getCause() instanceof Exception cause ? cause : failed;
    }
  }

  private Status status(ProjectDefinition config, boolean joined) throws Exception {
    var now = read(config.name(), lane.peek(config.name()));
    return new Status(
        now.project(),
        now.state(),
        now.current(),
        now.current()
            .map(t -> !t.inputs().equals(ProjectProvisioner.inputs(config, Optional.empty())))
            .orElse(false),
        now.build(),
        joined && now.state() == State.BUILDING,
        false);
  }

  /**
   * {@code project}'s template whose current version is {@code current}, its lock probed and its
   * record read: a record with no outcome whose lock is free, or held by this process ending it, is
   * a build cut short, read as interrupted until it is ended.
   */
  private Status read(String project, Optional<WorkspaceTemplate.Template> current)
      throws Exception {
    var record = builds.record(project);
    var building =
        record.filter(TemplateRecord::running).isPresent()
            && !ending.contains(project)
            && builds.building(project);
    var told =
        record.map(
            last ->
                last.running() && !building
                    ? last.ended(
                        TemplateRecord.Outcome.INTERRUPTED,
                        null,
                        TemplateBuild.CUT_PENDING,
                        TemplateBuild.RESUMES)
                    : last);
    return new Status(
        project, stateOf(building, current, told), current, false, told, false, false);
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
    return record.filter(last -> last.outcome() != TemplateRecord.Outcome.BUILT).isPresent()
        ? State.FAILED
        : State.MISSING;
  }
}
