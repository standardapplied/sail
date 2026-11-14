/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.engine.WorkstationIdentity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A box's workspaces as a test's dispatches meet them: one ZFS pool, {@code tank}, and for each
 * project named a current template {@code v1} built from its definition as {@code definitions}
 * reads it, so a spec's run makes its workspace and goes on in it. Every change Incus is asked for
 * succeeds; {@link #incus} is the scripted Incus, whose commands a test may read.
 */
public final class TestWorkspaces {

  /** The pool every template and workspace of these boxes is on. */
  public static final String POOL =
      """
      [{"name": "tank", "driver": "zfs", "config": {"source": "/var/lib/incus/disks/tank.img",
        "zfs.pool_name": "tank"}}]
      """;

  private TestWorkspaces() {}

  /** The workspaces of a box holding a current template of each of {@code projects}. */
  public static Workspaces ready(ProjectReader definitions, String... projects) {
    return over(incus(definitions, projects));
  }

  /** The workspaces of a box whose Incus is {@code incus}. */
  public static Workspaces over(ShellExec incus) {
    try {
      return new Workspaces(
          incus, incus, System::nanoTime, Files.createTempDirectory("sail-test-locks"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * What a test's own stub shell answers, by the fragment of a command it matches, to be a box
   * holding a current template of {@code project} that makes a spec's workspace when asked: the
   * pool, the template and its snapshot, and every change a create makes, done.
   */
  public static Map<String, ShellExec.Result> answers(String project) {
    var answers = new LinkedHashMap<String, ShellExec.Result>();
    answers.put("incus storage list --format json", new ShellExec.Result(0, POOL, ""));
    answers.put(
        "incus list --format json", new ShellExec.Result(0, "[" + template(project, "") + "]", ""));
    answers.put(
        "incus snapshot list " + project + "--tmpl --format json",
        new ShellExec.Result(
            0, "[{\"name\": \"v1\", \"created_at\": \"2026-11-12T08:00:00Z\"}]", ""));
    for (var change : List.of("incus copy", "incus start", "incus config set", "sail-workspace")) {
      answers.put(change, new ShellExec.Result(0, "", ""));
    }
    return answers;
  }

  /** The scripted Incus of {@link #ready}: a ZFS pool and a current template of each project. */
  public static ScriptedShellExecutor incus(ProjectReader definitions, String... projects) {
    var templates = new ArrayList<String>();
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    for (var project : Arrays.asList(projects)) {
      templates.add(
          template(
              project,
              ProjectProvisioner.inputs(
                  definitions.require(project), WorkstationIdentity.registered())));
      shell.onOk(
          "incus snapshot list " + project + "--tmpl --format json",
          "[{\"name\": \"v1\", \"created_at\": \"2026-11-12T08:00:00Z\"}]");
    }
    return shell
        .onOk("incus storage list --format json", POOL)
        .onOk("incus list --format json", "[" + String.join(",", templates) + "]");
  }

  /**
   * {@code project}'s template as Incus lists it, current at {@code v1} and built from {@code
   * inputs}.
   */
  public static String template(String project, String inputs) {
    return """
        {"name": "%s--tmpl", "status": "Stopped", "type": "container",
         "config": {"user.sail.template.of": "%s", "user.sail.template.current": "v1",
                    "user.sail.template.inputs": "%s"},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}"""
        .formatted(project, project, inputs);
  }
}
