/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.ProjectApplier;
import ai.singlr.sail.engine.ProjectServer;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * A project's stack, its services and processes, as every door brings it up and reads it: {@code
 * sail project serve}, {@code POST /v1/projects/{p}/serve} and the container's {@code portal}. A
 * project outside the caller's scope is one the catalog does not hold, and bringing a stack up or
 * stopping it is a write, refused to a read-only caller.
 */
public final class StackOperations {

  /** The longest a caller may ask {@code serve} to look for. */
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

  private final ShellExec shell;
  private final ProjectLoader projects;
  private final ProjectServer.Pause pause;
  private final LongSupplier nanos;

  StackOperations(
      ShellExec shell, ProjectLoader projects, ProjectServer.Pause pause, LongSupplier nanos) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.projects = Objects.requireNonNull(projects, "projects");
    this.pause = Objects.requireNonNull(pause, "pause");
    this.nanos = Objects.requireNonNull(nanos, "nanos");
  }

  /**
   * Every declared port of a project and where it stands.
   *
   * @param allListening whether every declared port listens
   */
  public record Ports(
      String project, List<ProjectServer.Port> ports, List<String> restarted, boolean allListening)
      implements Mappable {

    static Ports of(String project, ProjectServer.Report report) {
      return new Ports(project, report.ports(), report.restarted(), report.allListening());
    }

    @Override
    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("project", project);
      map.put(
          "ports",
          ports.stream()
              .map(
                  port -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("name", port.name());
                    row.put("port", port.port());
                    row.put("kind", port.kind().name().toLowerCase(Locale.ROOT));
                    row.put("state", port.state().word());
                    row.put("exit_code", port.exitCode());
                    row.put("detail", port.detail().isEmpty() ? null : port.detail());
                    return (Object) row;
                  })
              .toList());
      map.put("restarted", restarted);
      map.put("all_listening", allListening);
      return map;
    }
  }

  /**
   * Brings {@code project}'s stack up and looks until every declared port listens or its process
   * exits, for at most {@code timeout}.
   */
  public Ports serve(String project, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    requireReadable(project);
    Refusals.requireWriter("bring a project's stack up");
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "A serve's timeout is 1 to "
              + MAX_TIMEOUT.toSeconds()
              + " seconds, got: "
              + timeout.toSeconds());
    }
    var config = projects.loadRunning(project).config();
    return Ports.of(project, server().serve(project, config, timeout));
  }

  /** Where each declared port of {@code project} stands now, starting nothing. */
  public Ports ports(String project) throws IOException, InterruptedException, TimeoutException {
    requireReadable(project);
    var config = projects.loadRunning(project).config();
    return Ports.of(project, server().ports(project, config));
  }

  /** Stops {@code project}'s processes; its services keep running. */
  public Ports stop(String project) throws IOException, InterruptedException, TimeoutException {
    requireReadable(project);
    Refusals.requireWriter("stop a project's processes");
    var config = projects.loadRunning(project).config();
    var server = server();
    server.stop(project);
    return Ports.of(project, server.ports(project, config));
  }

  private ProjectServer server() {
    return new ProjectServer(
        shell,
        new ProjectApplier(
            shell, new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)),
        pause,
        nanos);
  }

  private static void requireReadable(String project) {
    if (!Actor.reader().scope().admits(project)) {
      throw ProjectReader.absent(project);
    }
  }
}
