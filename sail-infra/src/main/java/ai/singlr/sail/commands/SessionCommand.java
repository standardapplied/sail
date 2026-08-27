/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.Stty;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Host-owned terminal sessions: create, list, attach, kill. Sessions live in the container's pty
 * session host and survive every client — quit, reconnect, reattach. Attach enters raw mode and
 * detaches on {@code Ctrl-]}, leaving the session running.
 */
@Command(
    name = "session",
    description = "Host-owned terminal sessions that survive their clients.",
    subcommands = {
      SessionCommand.New.class,
      SessionCommand.Ls.class,
      SessionCommand.Attach.class,
      SessionCommand.KillSession.class
    })
public final class SessionCommand {

  static java.nio.file.Path socketOrDefault(java.nio.file.Path socket) {
    return socket != null ? socket : SailPaths.ptySocketPath();
  }

  @Command(
      name = "new",
      description = "Create a session (default: a shell in --project's container).")
  static final class New implements Callable<Integer> {
    @Option(names = "--socket", hidden = true, description = "Host socket override.")
    private java.nio.file.Path socket;

    @Parameters(index = "0", description = "Session name.")
    private String name;

    @Parameters(index = "1..*", description = "Command to run (default: bash -l).")
    private List<String> command = List.of();

    @Option(names = "--project", description = "Run the session inside this project's container.")
    private String project;

    @Override
    public Integer call() throws Exception {
      var size = Stty.size(new int[] {24, 80});
      try (var client = SessionClient.connect(socketOrDefault(socket))) {
        client.create(
            name,
            command,
            System.getProperty("user.home", "/home/dev"),
            project == null ? "" : project,
            size[1],
            size[0]);
      }
      System.out.println(
          "Session '" + name + "' started. Attach with: sail session attach " + name);
      return 0;
    }
  }

  @Command(name = "ls", description = "List sessions.")
  static final class Ls implements Callable<Integer> {
    @Option(names = "--socket", hidden = true, description = "Host socket override.")
    private java.nio.file.Path socket;

    @Override
    public Integer call() throws Exception {
      try (var client = SessionClient.connect(socketOrDefault(socket))) {
        var sessions = client.list();
        if (sessions.isEmpty()) {
          System.out.println("No sessions. Start one with: sail session new <name>");
          return 0;
        }
        for (var session : sessions) {
          System.out.printf(
              "%-24s %-8s %d attached%n",
              session.name(), session.live() ? "live" : "ended", session.attached());
        }
      }
      return 0;
    }
  }

  @Command(name = "attach", description = "Attach to a session (Ctrl-] detaches).")
  static final class Attach implements Callable<Integer> {
    @Option(names = "--socket", hidden = true, description = "Host socket override.")
    private java.nio.file.Path socket;

    @Parameters(index = "0", description = "Session name.")
    private String name;

    @Option(names = "--observe", description = "Read-only: watch without the write token.")
    private boolean observe;

    @Override
    public Integer call() throws Exception {
      try (var client = SessionClient.connect(socketOrDefault(socket))) {
        var channel = client.attach(name, !observe);
        var saved = Stty.saved().orElse(null);
        if (saved == null) {
          System.err.println("session attach needs an interactive terminal.");
          return 1;
        }
        Stty.set("raw -echo");
        String reason;
        try {
          reason = AttachLoop.run(channel, System.in, System.out, terminalResizes());
        } finally {
          Stty.set(saved);
        }
        System.out.println();
        System.out.println(
            reason == null ? "Detached; the session lives on." : "Session ended: " + reason);
      }
      return 0;
    }
  }

  /**
   * The controlling terminal's live geometry as {@code {cols, rows}} resize events: the current
   * size up front (so the remote pty matches this terminal, not the size it was created at), then
   * one on every {@code SIGWINCH}. Degrades to no resizes when signals are unavailable (native
   * image edge, no controlling tty) — the pty simply keeps its last known size.
   */
  static AttachLoop.Resizes terminalResizes() {
    var fallback = new int[] {24, 80};
    var changes = new java.util.concurrent.LinkedBlockingQueue<int[]>();
    try {
      sun.misc.Signal.handle(
          new sun.misc.Signal("WINCH"),
          signal -> {
            var size = Stty.size(fallback);
            changes.offer(new int[] {size[1], size[0]});
          });
    } catch (IllegalArgumentException | IllegalStateException e) {
      return AttachLoop.Resizes.NONE;
    }
    var initial = Stty.size(fallback);
    changes.offer(new int[] {initial[1], initial[0]});
    return changes::take;
  }

  @Command(name = "kill", description = "End a session and its process.")
  static final class KillSession implements Callable<Integer> {
    @Option(names = "--socket", hidden = true, description = "Host socket override.")
    private java.nio.file.Path socket;

    @Parameters(index = "0", description = "Session name.")
    private String name;

    @Override
    public Integer call() throws Exception {
      try (var client = SessionClient.connect(socketOrDefault(socket))) {
        client.kill(name);
      }
      System.out.println("Session '" + name + "' ended.");
      return 0;
    }
  }
}
