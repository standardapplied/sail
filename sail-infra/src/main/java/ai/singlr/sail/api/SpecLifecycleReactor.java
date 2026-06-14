/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SpecAuditEvent;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SpecAudit;
import ai.singlr.sail.engine.SpecWorkspace;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Bus subscriber that turns agent-emitted lifecycle events into durable per-spec state changes:
 * appends a row to the spec's {@code audit.jsonl} inside its container and, on {@code
 * agent_session_stopped}, transitions {@code spec.yaml} from {@code in_progress} to {@code review}.
 * The orchestrator's transition to {@code in_progress} at dispatch time stays where it is; this
 * reactor handles the back-half of the lifecycle once the agent reports completion.
 *
 * <p>Failures are logged and swallowed — a single broken transition cannot take the bus down. The
 * file-truth model means even if the reactor misses an event, {@code sail spec show} reflects the
 * real state from {@code spec.yaml} + {@code audit.jsonl} regardless.
 */
public final class SpecLifecycleReactor implements EventSubscriber {

  private static final Set<String> HANDLED_TYPES =
      Set.of(
          Event.WellKnownTypes.AGENT_SESSION_STARTED,
          Event.WellKnownTypes.AGENT_SESSION_STOPPED,
          Event.WellKnownTypes.AGENT_SESSION_COMPLETED);

  private final ShellExec shell;
  private final Function<String, String> specsDirForProject;

  /**
   * Default reactor: real {@link ShellExecutor}, project specs-dir resolved by reading {@code
   * ~/.sail/projects/&lt;name&gt;/sail.yaml} on each event.
   */
  public static SpecLifecycleReactor withDefaults() {
    return new SpecLifecycleReactor(new ShellExecutor(false), SpecLifecycleReactor::lookupSpecsDir);
  }

  /** Test seam: returns the default specs-dir resolver used by {@link #withDefaults()}. */
  static Function<String, String> defaultSpecsDirLookup() {
    return SpecLifecycleReactor::lookupSpecsDir;
  }

  public SpecLifecycleReactor(ShellExec shell, Function<String, String> specsDirForProject) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.specsDirForProject = Objects.requireNonNull(specsDirForProject, "specsDirForProject");
  }

  @Override
  public String name() {
    return "spec-lifecycle";
  }

  @Override
  public Predicate<Event> filter() {
    return e -> HANDLED_TYPES.contains(e.type()) && e.spec() != null && !e.spec().isBlank();
  }

  @Override
  public void onEvent(Event event) {
    var specsDir = specsDirForProject.apply(event.project());
    if (specsDir == null || specsDir.isBlank()) {
      return;
    }
    var workspace = new SpecWorkspace(shell, event.project(), specsDir);
    var audit = new SpecAudit(shell, event.project(), specsDir);
    try {
      switch (event.type()) {
        case Event.WellKnownTypes.AGENT_SESSION_STARTED ->
            audit.append(event.spec(), startedEvent(event));
        case Event.WellKnownTypes.AGENT_SESSION_STOPPED -> handleStopped(workspace, audit, event);
        case Event.WellKnownTypes.AGENT_SESSION_COMPLETED ->
            audit.append(event.spec(), completedEvent(event));
        default -> {}
      }
    } catch (Exception e) {
      System.err.println(
          "  [spec-lifecycle] Warning: failed to handle "
              + event.type()
              + " for "
              + event.project()
              + "/"
              + event.spec()
              + ": "
              + e.getMessage());
    }
  }

  private void handleStopped(SpecWorkspace workspace, SpecAudit audit, Event event)
      throws Exception {
    var current = workspace.readSpec(event.spec());
    if (current != null && current.status() == SpecStatus.IN_PROGRESS) {
      workspace.updateStatus(event.spec(), "review");
    }
    audit.append(event.spec(), stoppedEvent(event));
  }

  private static SpecAuditEvent startedEvent(Event event) {
    return new SpecAuditEvent(
        DateTimeUtils.now(), "started", event.agent(), pidOf(event), event.host(), null);
  }

  private static SpecAuditEvent stoppedEvent(Event event) {
    return new SpecAuditEvent(
        DateTimeUtils.now(), "stopped", event.agent(), pidOf(event), event.host(), noteOf(event));
  }

  private static SpecAuditEvent completedEvent(Event event) {
    return new SpecAuditEvent(
        DateTimeUtils.now(), "completed", event.agent(), null, event.host(), noteOf(event));
  }

  private static Integer pidOf(Event event) {
    Map<String, Object> data = event.data();
    var raw = data == null ? null : data.get("pid");
    return switch (raw) {
      case null -> null;
      case Integer i when i > 0 -> i;
      case Long l when l > 0 -> Math.toIntExact(l);
      case Number n when n.longValue() > 0 -> n.intValue();
      case String s when !s.isBlank() -> tryParsePid(s);
      default -> null;
    };
  }

  private static Integer tryParsePid(String s) {
    try {
      var pid = Integer.parseInt(s.strip());
      return pid > 0 ? pid : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String noteOf(Event event) {
    var data = event.data();
    var raw = data == null ? null : data.get("note");
    if (raw == null) {
      return null;
    }
    var note = raw.toString();
    return note.isBlank() ? null : note;
  }

  static String lookupSpecsDir(String project) {
    return lookupSpecsDirAt(
        project, SailPaths.resolveSailYaml(project, SailPaths.PROJECT_DESCRIPTOR));
  }

  /**
   * Pure resolver: given a project name and a path to its {@code sail.yaml}, returns the specs-dir
   * the reactor should write into, or {@code null} if the file is missing, the agent block is
   * absent, or the YAML cannot be parsed. Extracted so tests can point at a {@code @TempDir}
   * without touching the real user-home location.
   */
  static String lookupSpecsDirAt(String project, java.nio.file.Path path) {
    if (path == null || !Files.exists(path)) {
      return null;
    }
    try {
      var config = SailYaml.fromMap(YamlUtil.parseFile(path));
      if (config.agent() == null || config.agent().specsDir() == null) {
        return null;
      }
      return "/home/" + config.sshUser() + "/workspace/" + config.agent().specsDir();
    } catch (Exception e) {
      System.err.println(
          "  [spec-lifecycle] Warning: could not load specs_dir for '"
              + project
              + "' from "
              + path
              + ": "
              + e.getMessage());
      return null;
    }
  }
}
