/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.pty.PtyAdmission;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A build of a project's template, the one implementation both doors run: {@code sail project
 * template build} in its own process, and the route on a thread of sail-api. It holds the
 * template's lock for the whole build, and keeps the project's {@link TemplateRecord} at every
 * step, so either door's build is seen by the other and by every reader: the provisioner's {@link
 * ProjectPhase}s, then the lane's own finish (strip, check, stop, record and snapshot) as the last
 * of {@link #STEPS}. A build of the same inputs resumes after the last step a cut or failed one
 * finished.
 */
public final class TemplateBuild {

  /** The steps a build reports: the provisioner's own, then the lane's finish. */
  public static final int STEPS = ProjectPhase.values().length + 1;

  /** Why a build cut short ended, once sail-api cleaned its template. */
  public static final String CUT_SHORT =
      "The build stopped before it finished: the runner restarted, or its process ended.";

  /**
   * Why a build cut short ended, when the strip of its template or the search for its builder's
   * tokens failed: the next build starts from the template's last version, never from it.
   */
  public static final String UNPROVEN =
      "The build stopped before it finished, and its template could not be cleaned of its"
          + " builder's credentials; the next build starts it again from its last version.";

  /**
   * Why a build cut short ended, when the strip of its template and the search for its builder's
   * tokens passed but the template could not be stopped: marked all the same, so the next build
   * starts from the template's last version.
   */
  public static final String UNSTOPPED =
      "The build stopped before it finished; its template was cleaned of its builder's credentials"
          + " but could not be stopped, so the next build starts it again from its last version.";

  /**
   * What a read says of a build cut short that sail-api has not ended yet: it ends each one as it
   * starts and then once a minute ({@link #CLEANUP_EVERY}), so this is true at every moment.
   */
  public static final String CUT_PENDING =
      "The build stopped before it finished; its template is being cleaned, or is cleaned within a"
          + " minute.";

  /** How often sail-api looks for builds cut short to end, beside once as it starts. */
  public static final Duration CLEANUP_EVERY = Duration.ofMinutes(1);

  /** What a build cut short or failed is told to do: build again, from where it stopped. */
  public static final Remedy RESUMES =
      Remedy.of("Build it again: it picks up at the step that did not finish.");

  /** Provisions the template into {@code into} for a door, as that door builds its provisioner. */
  @FunctionalInterface
  public interface Provision {
    void into(
        ProjectProvisioner.Instance into,
        ProvisionTracker<ProjectPhase> tracker,
        ProvisionListener listener)
        throws Exception;
  }

  private final Path locks;
  private final Path projectsDir;
  private final Supplier<Instant> clock;

  /**
   * @param locks the box's lock directory, where the template's lock is
   * @param projectsDir where each project keeps its state, its record among it
   */
  public TemplateBuild(Path locks, Path projectsDir, Supplier<Instant> clock) {
    this.locks = Objects.requireNonNull(locks, "locks");
    this.projectsDir = Objects.requireNonNull(projectsDir, "projectsDir");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * {@code config}'s template source for a build by {@code by} with {@code tokens} (whose they are
   * is the door's to choose): a build the lane runs from it keeps the project's record at every
   * step, from the moment it holds the template's lock, which {@code started} is told, to how it
   * ended, telling {@code console} each step as the door shows it. A failure ends it with words
   * written for a person ({@link Remedied}) alone: any other, a tool's output say, is the door's to
   * print or log, and the record says the runner could not finish. A dry run keeps no record.
   */
  public WorkspaceTemplate.Source source(
      ProjectDefinition config,
      String basePool,
      Predicate<String> held,
      Collection<String> tokens,
      Path progress,
      String by,
      ProvisionListener console,
      Provision provision,
      Runnable started,
      boolean dryRun) {
    var inputs = ProjectProvisioner.inputs(config, Optional.empty());
    if (dryRun) {
      return new WorkspaceTemplate.Source(
          basePool,
          inputs,
          held,
          (into, fresh) -> provision.into(into, tracker(progress, inputs, fresh, true), console),
          tokens);
    }
    var file = TemplateRecord.fileOf(projectsDir, config.name());
    var record = new AtomicReference<TemplateRecord>();
    var listener = recording(console, record, file);
    return new WorkspaceTemplate.Source(
        basePool,
        inputs,
        held,
        (into, fresh) -> {
          var tracker = tracker(progress, inputs, fresh, false);
          record.set(record.get().keeping(done(tracker)));
          provision.into(into, tracker, listener);
          step(record, file, STEPS);
        },
        tokens,
        new WorkspaceTemplate.Recorder() {
          @Override
          public void started() {
            record.set(TemplateRecord.started(by, clock.get(), STEPS));
            record.get().write(file);
            started.run();
          }

          @Override
          public void built(WorkspaceTemplate.Template template) {
            record.get().ended(TemplateRecord.Outcome.BUILT, clock.get(), null, null).write(file);
          }

          @Override
          public void failed(Exception failure) {
            var said = failure instanceof Remedied;
            record
                .get()
                .ended(
                    TemplateRecord.Outcome.FAILED,
                    clock.get(),
                    said ? Remedied.reasonOf(failure).strip() : PtyAdmission.Unavailable.UNFINISHED,
                    said ? Remedied.remedyOf(failure) : PtyAdmission.Unavailable.REMEDY)
                .write(file);
          }
        });
  }

  /**
   * Ends a build cut short ({@code record} has no outcome, and the template's lock is free, its
   * builder gone): under the lock, the template is stripped of every credential, searched for
   * {@code tokens} (its builder's, as stored now) and stopped, as a failed build is; only then is
   * its record ended {@code interrupted}. A strip or search that fails, or a token found, leaves
   * the template stopped and marked {@link WorkspaceTemplate#UNPROVEN_KEY}, so the next build rolls
   * it back to its last version; workspaces clone that version's snapshot, never the template as it
   * stands. Empty when there was nothing to end, or another holds the lock.
   */
  public Optional<TemplateRecord> endCutShort(
      String project, ShellExec shell, Collection<String> tokens) throws Exception {
    var template = WorkspaceTemplate.nameOf(project);
    var lock = FileMutex.acquireUntil(ContainerConfig.lockOf(locks, template), 0, () -> 0);
    if (lock.isEmpty()) {
      return Optional.empty();
    }
    try (var held = lock.get()) {
      var file = TemplateRecord.fileOf(projectsDir, project);
      var record = TemplateRecord.read(file).filter(TemplateRecord::running);
      if (record.isEmpty()) {
        return Optional.empty();
      }
      Exception failed = null;
      try {
        clean(shell, template, tokens);
      } catch (Exception e) {
        failed = e;
      }
      var proven = failed == null;
      if (!proven) {
        var unmarked = WorkspaceTemplate.markUnproven(shell, locks, template);
        if (failed == null && unmarked.isPresent()) {
          failed = unmarked.get();
        }
      }
      var ended =
          record
              .get()
              .ended(
                  TemplateRecord.Outcome.INTERRUPTED,
                  clock.get(),
                  proven ? CUT_SHORT : failed instanceof NotStopped ? UNSTOPPED : UNPROVEN,
                  RESUMES);
      ended.write(file);
      if (failed != null) {
        throw failed;
      }
      return Optional.of(ended);
    }
  }

  /**
   * Strips, searches and stops {@code template}, when there is one: a stop that fails after a strip
   * and search that passed is thrown as {@link NotStopped}, and after one that failed, beside it.
   */
  private static void clean(ShellExec shell, String template, Collection<String> tokens)
      throws Exception {
    var found = new BoxInstances(shell).named(template);
    if (found.isEmpty()) {
      return;
    }
    var containers = new ContainerManager(shell);
    Exception unclean = null;
    try {
      if (!(found.get().state() instanceof ContainerState.Running)) {
        containers.start(template);
      }
      WorkspaceCredentials.clean(shell, template, tokens);
    } catch (Exception e) {
      unclean = e;
    }
    try {
      containers.stop(template);
    } catch (Exception e) {
      if (unclean == null) {
        throw new NotStopped(template, e);
      }
      unclean.addSuppressed(e);
    }
    if (unclean != null) {
      throw unclean;
    }
  }

  /** A template cleaned of every credential that could not be stopped. */
  private static final class NotStopped extends Exception {
    NotStopped(String template, Exception cause) {
      super(template + " was cleaned but could not be stopped: " + cause.getMessage(), cause);
    }
  }

  /** Whether {@code project}'s template is being built now: its lock is held. */
  public boolean building(String project) throws IOException, InterruptedException {
    var lock =
        FileMutex.acquireUntil(
            ContainerConfig.lockOf(locks, WorkspaceTemplate.nameOf(project)), 0, () -> 0);
    lock.ifPresent(FileMutex::close);
    return lock.isEmpty();
  }

  /** {@code project}'s record, when one was kept. */
  public Optional<TemplateRecord> record(String project) {
    return TemplateRecord.read(TemplateRecord.fileOf(projectsDir, project));
  }

  /**
   * {@code config}, refused when it sets no resources, before anything is built: a template is
   * launched with the project's own CPU, memory and disk.
   */
  public static ProjectDefinition requireResources(ProjectDefinition config) {
    if (config.resources() != null) {
      return config;
    }
    throw Remedied.state(
        config.name()
            + "'s definition sets no CPU, memory or disk, so its template cannot be built.",
        Remedy.of(
            "Set them first: ",
            Remedy.sail("project config set", config.name(), "resources", RESOURCES),
            "."));
  }

  /** This box's {@code host.yaml} at {@code file}, refused when the box was never set up. */
  public static HostYaml hostOf(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw Remedied.state(
          "This runner is not set up, so no template can be built on it.",
          Remedy.of("Set it up first: ", Remedy.sail("host init"), "."));
    }
    return HostYaml.fromMap(YamlUtil.parseFile(file));
  }

  private static final String RESOURCES = "{\"cpu\": 2, \"memory\": \"8GB\", \"disk\": \"50GB\"}";

  /** The pool a new template goes on: the base profile's, as {@code host} names it. */
  public static String poolOf(HostYaml host) {
    return Objects.requireNonNullElse(host.pool(), HostYaml.DEFAULT_POOL);
  }

  /**
   * As {@link #provision(ShellExec, HostYaml, ProjectDefinition, CheckedTokens, ProjectSkills,
   * OperatorCommand)} for a door that cannot see its reader, sail-api's: what the provisioner says
   * in its own words reaches only the log, since the record keeps words written for a person alone.
   */
  public static Provision provision(
      ShellExec shell,
      HostYaml host,
      ProjectDefinition config,
      CheckedTokens tokens,
      ProjectSkills skills) {
    return provision(shell, host, config, tokens, skills, OperatorCommand.PLAIN);
  }

  /**
   * The project's own provisioner, as a template build runs it: on {@code shell}, with {@code
   * tokens} for its clone and no git identity and no trusted key of anyone's.
   */
  public static Provision provision(
      ShellExec shell,
      HostYaml host,
      ProjectDefinition config,
      CheckedTokens tokens,
      ProjectSkills skills,
      OperatorCommand operator) {
    return (into, tracker, listener) ->
        new ProjectProvisioner(shell, tracker, listener, skills, Optional.empty(), operator)
            .provision(into, config, host, tokens, null, SailPaths.projectFilesDir(config.name()));
  }

  /**
   * The progress a build resumes: what {@code progress} records for {@code inputs}, unless the
   * template is {@code fresh} or the record is of a finished build, when every step runs again.
   */
  static ProvisionTracker<ProjectPhase> tracker(
      Path progress, String inputs, boolean fresh, boolean dryRun) throws IOException {
    var tracker = new ProvisionTracker<>(ProjectPhase.class, progress, dryRun).load(inputs);
    if (fresh || tracker.isComplete()) {
      tracker.reset();
    }
    return tracker;
  }

  private static int done(ProvisionTracker<ProjectPhase> tracker) {
    return tracker.resumePoint().map(Enum::ordinal).orElse(0);
  }

  private ProvisionListener recording(
      ProvisionListener console, AtomicReference<TemplateRecord> record, Path file) {
    return new ProvisionListener() {
      @Override
      public void onStep(int step, int total, String description) {
        step(record, file, step);
        console.onStep(step, total, description);
      }

      @Override
      public void onStepDone(int step, int total, String detail) {
        console.onStepDone(step, total, detail);
      }

      @Override
      public void onStepSkipped(int step, int total, String detail) {
        console.onStepSkipped(step, total, detail);
      }

      @Override
      public void onStepProgress(String detail) {
        console.onStepProgress(detail);
      }

      @Override
      public void onStepFailed() {
        console.onStepFailed();
      }
    };
  }

  private void step(AtomicReference<TemplateRecord> record, Path file, int step) {
    record.set(record.get().at(step, clock.get()));
    record.get().write(file);
  }
}
