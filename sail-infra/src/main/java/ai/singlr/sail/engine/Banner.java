/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import java.io.PrintStream;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import picocli.CommandLine.Help.Ansi;

/**
 * Renders styled ASCII banners for CLI output. Uses picocli's {@link Ansi#AUTO} for automatic TTY
 * detection — colors when interactive, plain text when piped.
 */
public final class Banner {

  private static final int W = 56;
  private static final int LABEL_COL = 13;

  private Banner() {}

  private static final String BRAND = "\033[1;38;2;252;73;38m";
  private static final String CYAN_BOLD = "\033[1;36m";
  private static final String DIM = "\033[2m";
  private static final String RESET = "\033[0m";
  private static final int ANIMATION_WIDTH = 64;

  /**
   * Replaces picocli's bold-cyan ANSI escape with the SAIL brand color for consistent borders.
   * Picocli only supports named colors, so we post-process its output.
   */
  static String amber(Ansi ansi, String markup) {
    var result = ansi.string(markup);
    if (ansi != Ansi.OFF) {
      result = result.replace(CYAN_BOLD, BRAND);
    }
    return result;
  }

  /** Prints the SAIL ASCII wordmark and version. */
  public static void printBranding(PrintStream out, Ansi ansi) {
    printIntroAnimation(out, ansi);
    var useColor = ansi != Ansi.OFF;
    var a = useColor ? BRAND : "";
    var d = useColor ? DIM : "";
    var r = useColor ? RESET : "";
    out.println();
    var mark =
        List.of("██████████", " ╚███╗    ", "   ╚███╗  ", "   ███╔╝  ", " ███╔╝    ", "██████████");
    var wordmark =
        List.of(
            "███████╗  █████╗  ██╗ ██╗",
            "██╔════╝ ██╔══██╗ ██║ ██║",
            "███████╗ ███████║ ██║ ██║",
            "╚════██║ ██╔══██║ ██║ ██║",
            "███████║ ██║  ██║ ██║ ███████╗",
            "╚══════╝ ╚═╝  ╚═╝ ╚═╝ ╚══════╝");
    for (var i = 0; i < mark.size(); i++) {
      out.println("  " + a + mark.get(i) + "  " + wordmark.get(i) + r);
    }
    out.println();
    out.println(
        "  " + d + "Isolated dev environments for AI agents  ·  v" + SailVersion.version() + r);
    out.println("  " + d + "https://github.com/standardapplied/sail" + r);
  }

  static boolean shouldAnimateBranding(
      Ansi ansi, Map<String, String> env, BooleanSupplier hasConsole) {
    return ansi != Ansi.OFF
        && hasConsole.getAsBoolean()
        && !"1".equals(env.get("SAIL_NO_ANIMATION"))
        && !"true".equalsIgnoreCase(env.get("CI"))
        && !env.containsKey("NO_COLOR")
        && !"dumb".equalsIgnoreCase(env.get("TERM"));
  }

  private static void printIntroAnimation(PrintStream out, Ansi ansi) {
    if (!shouldAnimateBranding(ansi, System.getenv(), () -> System.console() != null)) {
      return;
    }

    var frames =
        List.of(
            "      /|   setting course",
            "     /_|   raising sail",
            "    /__|   catching wind",
            "   /___|   SAIL ready");
    var useColor = ansi != Ansi.OFF;
    var color = useColor ? BRAND : "";
    var reset = useColor ? RESET : "";
    for (var frame : frames) {
      out.print("\r  " + color + frame + reset);
      out.flush();
      sleepAnimationFrame();
    }
    out.print("\r" + " ".repeat(ANIMATION_WIDTH) + "\r");
    out.flush();
  }

  private static void sleepAnimationFrame() {
    try {
      Thread.sleep(90);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Prints the host detection banner to stdout with auto ANSI detection. */
  public static void printHostDetection(HostDetector.HostInfo info) {
    printHostDetection(info, System.out, Ansi.AUTO);
  }

  /** Prints the host detection banner to the given stream with the given ANSI mode. */
  public static void printHostDetection(HostDetector.HostInfo info, PrintStream out, Ansi ansi) {
    var mem = NumberFormat.getNumberInstance(Locale.US).format(info.memoryMb()) + " MB";
    var host = Objects.requireNonNullElse(info.hostname(), "");
    var os = Objects.requireNonNullElse(info.osPrettyName(), "");
    var cpu =
        info.cores() == info.threads()
            ? info.threads() + " cores"
            : info.cores() + " cores / " + info.threads() + " threads";
    var check = info.supported() ? "\u2713" : "\u2717";
    var checkStyle = info.supported() ? "bold,green" : "bold,red";

    var supportText = "Supported:   Ubuntu 24.04+";
    var w = W;
    w = Math.max(w, rowWidth("Hostname:", host));
    w = Math.max(w, rowWidth("OS:", os) + 4);
    w = Math.max(w, rowWidth("CPU:", cpu));
    w = Math.max(w, rowWidth("RAM:", mem));
    w = Math.max(w, 2 + supportText.length());

    out.println();

    var title = " Host Detection ";
    out.println(
        amber(
            ansi,
            "  @|bold,cyan \u256d\u2500"
                + title
                + "\u2500".repeat(Math.max(1, w - title.length() - 1))
                + "\u256e|@"));

    emptyRow(out, ansi, w);
    labelRow(out, ansi, w, "Hostname:", host, null, null);
    labelRow(out, ansi, w, "OS:", os, check, checkStyle);
    labelRow(out, ansi, w, "CPU:", cpu, null, null);
    labelRow(out, ansi, w, "RAM:", mem, null, null);
    emptyRow(out, ansi, w);

    out.println(
        amber(
            ansi,
            "  @|bold,cyan \u2502|@  @|faint "
                + supportText
                + "|@"
                + " ".repeat(w - 2 - supportText.length())
                + "@|bold,cyan \u2502|@"));

    emptyRow(out, ansi, w);

    out.println(amber(ansi, "  @|bold,cyan \u2570" + "\u2500".repeat(w) + "\u256f|@"));
    out.println();
  }

  /** Prints the root required message. */
  public static void printRootRequired(PrintStream out, Ansi ansi) {
    out.println(
        amber(
            ansi,
            "  @|bold,red \u2717 Root privileges required.|@"
                + " Run with: @|bold sudo sail host init|@"));
  }

  /** Returns a formatted provisioning step progress line. */
  public static String stepLine(int step, int total, String description, Ansi ansi) {
    return amber(ansi, "  @|bold [" + step + "/" + total + "]|@ " + description);
  }

  /** Returns a formatted provisioning step completion line. */
  public static String stepDoneLine(int step, int total, String detail, Ansi ansi) {
    return amber(ansi, "  @|bold [" + step + "/" + total + "]|@ @|green \u2713|@ " + detail);
  }

  /** Returns a formatted error line. */
  public static String errorLine(String message, Ansi ansi) {
    return amber(ansi, "  @|bold,red \u2717|@ " + message);
  }

  /** Prints the unsupported OS message. */
  public static void printUnsupported(HostDetector.HostInfo info, PrintStream out, Ansi ansi) {
    out.println(
        amber(ansi, "  @|bold,red \u2717 Unsupported operating system:|@ " + info.osPrettyName()));
    out.println(amber(ansi, "    @|faint SAIL currently supports:|@ @|bold Ubuntu 24.04+|@"));
  }

  /** Prints prerequisite check results — ✓ for present, ✗ for missing. */
  public static void printPrerequisites(
      PrerequisiteChecker.CheckResult result, PrintStream out, Ansi ansi) {
    out.println();
    out.println(amber(ansi, "  @|bold Checking prerequisites...|@"));
    for (var p : result.present()) {
      out.println(
          amber(
              ansi,
              "    @|green \u2713|@ "
                  + String.format("%-12s", p.command())
                  + "@|faint "
                  + p.reason()
                  + "|@"));
    }
    for (var p : result.missing()) {
      out.println(
          amber(
              ansi,
              "    @|bold,red \u2717|@ "
                  + String.format("%-12s", p.command())
                  + "@|faint "
                  + p.reason()
                  + "|@"));
    }
    out.println();
  }

  /** Prints the missing prerequisites summary with the install command. */
  public static void printMissingPrerequisites(
      List<PrerequisiteChecker.Prerequisite> missing, PrintStream out, Ansi ansi) {
    var commands =
        missing.stream()
            .map(PrerequisiteChecker.Prerequisite::command)
            .collect(Collectors.joining(", "));
    var packages =
        missing.stream()
            .filter(p -> p.pkg() != null)
            .map(PrerequisiteChecker.Prerequisite::pkg)
            .collect(Collectors.joining(" "));

    out.println(amber(ansi, "  @|bold,red Missing commands:|@ " + commands));
    if (!packages.isEmpty()) {
      out.println(
          amber(ansi, "  @|faint Install with:|@ @|bold apt-get install -y " + packages + "|@"));
    }
    out.println();
  }

  /** Returns a formatted provisioning step skipped line. */
  public static String stepSkippedLine(int step, int total, String detail, Ansi ansi) {
    return amber(ansi, "  @|bold [" + step + "/" + total + "]|@ @|faint \u2192 " + detail + "|@");
  }

  /** Prints a bordered project summary box showing key configuration values. */
  public static void printProjectSummary(SailYaml config, PrintStream out, Ansi ansi) {
    var pkgCount = config.packages() != null ? config.packages().size() : 0;

    var w = W;
    w = Math.max(w, rowWidth("Name:", config.name()));
    w = Math.max(w, rowWidth("Image:", Objects.requireNonNullElse(config.image(), "default")));
    if (config.resources() != null) {
      w = Math.max(w, rowWidth("CPU:", config.resources().cpu() + " cores"));
      w = Math.max(w, rowWidth("Memory:", config.resources().memory()));
      w = Math.max(w, rowWidth("Disk:", config.resources().disk()));
    }
    w = Math.max(w, rowWidth("Packages:", pkgCount + " user + 5 baseline"));
    if (config.ssh() != null) {
      w = Math.max(w, rowWidth("SSH user:", config.ssh().user()));
    }
    if (config.git() != null) {
      w = Math.max(w, rowWidth("Git:", config.git().name() + " <" + config.git().email() + ">"));
    }
    if (config.repos() != null && !config.repos().isEmpty()) {
      w = Math.max(w, rowWidth("Repos:", config.repos().size() + " repositories"));
    }

    out.println();
    var title = " Project ";
    out.println(
        amber(
            ansi,
            "  @|bold,cyan \u256d\u2500"
                + title
                + "\u2500".repeat(Math.max(1, w - title.length() - 1))
                + "\u256e|@"));

    emptyRow(out, ansi, w);
    labelRow(out, ansi, w, "Name:", config.name(), null, null);
    labelRow(
        out, ansi, w, "Image:", Objects.requireNonNullElse(config.image(), "default"), null, null);
    if (config.resources() != null) {
      labelRow(out, ansi, w, "CPU:", config.resources().cpu() + " cores", null, null);
      labelRow(out, ansi, w, "Memory:", config.resources().memory(), null, null);
      labelRow(out, ansi, w, "Disk:", config.resources().disk(), null, null);
    }
    labelRow(out, ansi, w, "Packages:", pkgCount + " user + 5 baseline", null, null);
    if (config.ssh() != null) {
      labelRow(out, ansi, w, "SSH user:", config.ssh().user(), null, null);
    }
    if (config.git() != null) {
      labelRow(
          out,
          ansi,
          w,
          "Git:",
          config.git().name() + " <" + config.git().email() + ">",
          null,
          null);
    }
    if (config.repos() != null && !config.repos().isEmpty()) {
      labelRow(out, ansi, w, "Repos:", config.repos().size() + " repositories", null, null);
    }
    emptyRow(out, ansi, w);

    out.println(amber(ansi, "  @|bold,cyan \u2570" + "\u2500".repeat(w) + "\u256f|@"));
    out.println();
  }

  /** Prints a detailed project configuration view with live container status. */
  public static void printProjectConfig(
      SailYaml config, ContainerState state, PrintStream out, Ansi ansi) {
    var statusText =
        switch (state) {
          case ContainerState.Running r ->
              r.ipv4() != null ? "Running (" + r.ipv4() + ")" : "Running";
          case ContainerState.Stopped ignored -> "Stopped";
          case ContainerState.NotCreated ignored -> "Not created";
          case ContainerState.Error e -> "Error: " + e.message();
        };

    var w = W;
    w = Math.max(w, rowWidth("Name:", config.name()));
    if (config.description() != null) {
      w = Math.max(w, rowWidth("Description:", config.description()));
    }
    w = Math.max(w, rowWidth("Image:", Objects.requireNonNullElse(config.image(), "default")));
    w = Math.max(w, rowWidth("Status:", statusText));
    if (config.resources() != null) {
      w = Math.max(w, rowWidth("CPU:", config.resources().cpu() + " cores"));
      w = Math.max(w, rowWidth("Memory:", config.resources().memory()));
      w = Math.max(w, rowWidth("Disk:", config.resources().disk()));
    }
    if (config.runtimes() != null) {
      if (config.runtimes().jdk() > 0) {
        w = Math.max(w, rowWidth("JDK:", String.valueOf(config.runtimes().jdk())));
      }
      if (config.runtimes().node() != null) {
        w = Math.max(w, rowWidth("Node:", config.runtimes().node()));
      }
      if (config.runtimes().maven() != null) {
        w = Math.max(w, rowWidth("Maven:", config.runtimes().maven()));
      }
    }
    if (config.git() != null) {
      w = Math.max(w, rowWidth("Git:", config.git().name() + " <" + config.git().email() + ">"));
      w = Math.max(w, rowWidth("Git auth:", config.git().auth()));
    }
    if (config.repos() != null) {
      for (var repo : config.repos()) {
        var detail = repo.path();
        if (repo.branch() != null) detail += " (" + repo.branch() + ")";
        w = Math.max(w, rowWidth("Repo:", detail));
      }
    }
    if (config.services() != null) {
      for (var entry : config.services().entrySet()) {
        w = Math.max(w, rowWidth("Service:", serviceValue(entry)));
      }
    }
    if (config.agent() != null) {
      w = Math.max(w, rowWidth("Agent:", Objects.requireNonNullElse(config.agent().type(), "-")));
      w = Math.max(w, rowWidth("Auto-snap:", String.valueOf(config.agent().autoSnapshot())));
      w = Math.max(w, rowWidth("Auto-branch:", String.valueOf(config.agent().autoBranch())));
    }
    if (config.ssh() != null) {
      w = Math.max(w, rowWidth("SSH user:", config.ssh().user()));
    }

    out.println();
    var title = " Project: " + config.name() + " ";
    out.println(
        amber(
            ansi,
            "  @|bold,cyan \u256d\u2500"
                + title
                + "\u2500".repeat(Math.max(1, w - title.length() - 1))
                + "\u256e|@"));

    emptyRow(out, ansi, w);
    labelRow(out, ansi, w, "Name:", config.name(), null, null);
    if (config.description() != null) {
      labelRow(out, ansi, w, "Description:", config.description(), null, null);
    }
    labelRow(
        out, ansi, w, "Image:", Objects.requireNonNullElse(config.image(), "default"), null, null);
    emptyRow(out, ansi, w);

    if (config.resources() != null) {
      labelRow(out, ansi, w, "CPU:", config.resources().cpu() + " cores", null, null);
      labelRow(out, ansi, w, "Memory:", config.resources().memory(), null, null);
      labelRow(out, ansi, w, "Disk:", config.resources().disk(), null, null);
      emptyRow(out, ansi, w);
    }

    labelRow(out, ansi, w, "Status:", statusText, null, null);
    emptyRow(out, ansi, w);

    if (config.runtimes() != null
        && (config.runtimes().jdk() > 0
            || config.runtimes().node() != null
            || config.runtimes().maven() != null)) {
      if (config.runtimes().jdk() > 0) {
        labelRow(out, ansi, w, "JDK:", String.valueOf(config.runtimes().jdk()), null, null);
      }
      if (config.runtimes().node() != null) {
        labelRow(out, ansi, w, "Node:", config.runtimes().node(), null, null);
      }
      if (config.runtimes().maven() != null) {
        labelRow(out, ansi, w, "Maven:", config.runtimes().maven(), null, null);
      }
      emptyRow(out, ansi, w);
    }

    if (config.git() != null) {
      labelRow(
          out,
          ansi,
          w,
          "Git:",
          config.git().name() + " <" + config.git().email() + ">",
          null,
          null);
      labelRow(out, ansi, w, "Git auth:", config.git().auth(), null, null);
      emptyRow(out, ansi, w);
    }

    if (config.repos() != null && !config.repos().isEmpty()) {
      for (var repo : config.repos()) {
        var detail = repo.path();
        if (repo.branch() != null) {
          detail += " (" + repo.branch() + ")";
        }
        labelRow(out, ansi, w, "Repo:", detail, null, null);
      }
      emptyRow(out, ansi, w);
    }

    if (config.services() != null && !config.services().isEmpty()) {
      for (var entry : config.services().entrySet()) {
        labelRow(out, ansi, w, "Service:", serviceValue(entry), null, null);
      }
      emptyRow(out, ansi, w);
    }

    if (config.agent() != null) {
      labelRow(
          out,
          ansi,
          w,
          "Agent:",
          Objects.requireNonNullElse(config.agent().type(), "-"),
          null,
          null);
      labelRow(
          out, ansi, w, "Auto-snap:", String.valueOf(config.agent().autoSnapshot()), null, null);
      labelRow(
          out, ansi, w, "Auto-branch:", String.valueOf(config.agent().autoBranch()), null, null);
      emptyRow(out, ansi, w);
    }

    if (config.ssh() != null) {
      labelRow(out, ansi, w, "SSH user:", config.ssh().user(), null, null);
      emptyRow(out, ansi, w);
    }

    out.println(amber(ansi, "  @|bold,cyan \u2570" + "\u2500".repeat(w) + "\u256f|@"));
    out.println();
  }

  /** Prints the project creation success message with Zed connect hint. */
  public static void printProjectCreated(String name, String sshUser, PrintStream out, Ansi ansi) {
    var user = Objects.requireNonNullElse(sshUser, "dev");
    out.println(amber(ansi, "  @|bold,green \u2713 Project " + name + " created.|@"));
    printZedConnect(name, user, out, ansi);
  }

  /**
   * Prints the SSH config snippet that the user should add to {@code ~/.ssh/config} on their Mac.
   * This is the same output as {@code sail project connect} but printed inline after project
   * creation.
   */
  public static void printSshConfig(
      String name,
      String serverIp,
      String serverUser,
      String containerIp,
      String identityFile,
      PrintStream out,
      Ansi ansi) {
    out.println();
    out.println(amber(ansi, "    @|faint # Add to ~/.ssh/config on your Mac:|@"));
    out.println();
    out.println(amber(ansi, "    @|faint Host singular-server|@"));
    out.println(amber(ansi, "    @|faint     HostName " + serverIp + "|@"));
    out.println(amber(ansi, "    @|faint     User " + serverUser + "|@"));
    out.println(amber(ansi, "    @|faint     IdentityFile " + identityFile + "|@"));
    out.println();
    out.println(amber(ansi, "    @|faint Host " + name + "|@"));
    out.println(amber(ansi, "    @|faint     HostName " + containerIp + "|@"));
    out.println(amber(ansi, "    @|faint     User dev|@"));
    out.println(amber(ansi, "    @|faint     ProxyJump singular-server|@"));
    out.println(amber(ansi, "    @|faint     IdentityFile " + identityFile + "|@"));
    out.println();
    out.println(
        amber(ansi, "    @|bold \u2192 Zed:|@     zed ssh://dev@" + name + "/home/dev/workspace"));
    out.println(amber(ansi, "    @|bold \u2192 SSH:|@     ssh " + name));
    out.println(amber(ansi, "    @|bold \u2192 Shell:|@   sudo sail project shell " + name));
  }

  /** Prints resume information when a prior incomplete provisioning run is detected. */
  public static void printResumeInfo(ProvisionState state, PrintStream out, Ansi ansi) {
    out.println();
    out.println(amber(ansi, "  @|yellow Resuming incomplete provisioning|@"));
    if (state.completedPhase() != null) {
      out.println(amber(ansi, "    @|faint Last completed:|@ " + state.completedPhase()));
    }
    if (state.error() != null) {
      out.println(
          amber(
              ansi,
              "    @|faint Prior failure:|@  "
                  + state.error().failedPhase()
                  + " \u2014 "
                  + state.error().message()));
    }
    out.println();
  }

  /** Prints the connect and shell hints after a container starts. */
  public static void printZedConnect(String name, String sshUser, PrintStream out, Ansi ansi) {
    out.println(amber(ansi, "    @|bold \u2192 Connect:|@ sail project connect " + name));
    out.println(amber(ansi, "    @|bold \u2192 Shell:|@   sudo sail project shell " + name));
  }

  /**
   * Prints SSH tunnel hint for forwarding service ports from the container to localhost. Only
   * prints if there are ports to forward.
   *
   * @param name the container name (used as SSH host)
   * @param ports the list of port numbers to forward
   */
  public static void printSshTunnels(
      String name, String sshUser, List<Integer> ports, PrintStream out, Ansi ansi) {
    if (ports == null || ports.isEmpty()) {
      return;
    }
    var user = Objects.requireNonNullElse(sshUser, "dev");
    var tunnelArgs =
        ports.stream()
            .sorted()
            .map(p -> "-L " + p + ":localhost:" + p)
            .collect(Collectors.joining(" "));
    out.println(
        amber(
            ansi,
            "    @|bold \u2192 Ports:|@   ssh " + tunnelArgs + " " + user + "@" + name + " -N"));
  }

  /** Prints agent auth tunnel hint (port 3000 forwarding for subscription-based login). */
  public static void printAgentAuthTunnel(String name, PrintStream out, Ansi ansi) {
    out.println(
        amber(ansi, "    @|bold \u2192 Agent auth:|@ ssh -N -L 3000:localhost:3000 " + name));
  }

  /** Prints a container status line — green ✓ for running, faint ■ for stopped. */
  public static void printContainerStatus(
      String name, ContainerState state, PrintStream out, Ansi ansi) {
    switch (state) {
      case ContainerState.Running r -> {
        var ip = r.ipv4() != null ? " (" + r.ipv4() + ")" : "";
        out.println(amber(ansi, "  @|green \u2713|@ " + name + " is running" + ip));
      }
      case ContainerState.Stopped ignored ->
          out.println(amber(ansi, "  @|faint \u25a0 " + name + " is stopped|@"));
      case ContainerState.NotCreated ignored ->
          out.println(amber(ansi, "  @|faint \u25a0 " + name + " does not exist|@"));
      case ContainerState.Error e ->
          out.println(amber(ansi, "  @|bold,red \u2717|@ " + name + ": " + e.message()));
    }
  }

  /** Prints a bordered project list table. */
  public static void printProjectTable(
      List<ContainerManager.ContainerInfo> containers, PrintStream out, Ansi ansi) {
    var table = new TableFormatter(" Projects ", List.of("NAME", "STATUS", "IP", "CPU", "MEM"));
    for (var c : containers) {
      var status =
          switch (c.state()) {
            case ContainerState.Running ignored -> "Running";
            case ContainerState.Stopped ignored -> "Stopped";
            case ContainerState.NotCreated ignored -> "Not provisioned";
            case ContainerState.Error ignored -> "Error";
          };
      var ip = c.state() instanceof ContainerState.Running r && r.ipv4() != null ? r.ipv4() : "-";
      var cpu = c.limits() != null && c.limits().cpu() != null ? c.limits().cpu() : "-";
      var mem = c.limits() != null && c.limits().memory() != null ? c.limits().memory() : "-";
      table.addRow(c.name(), status, ip, cpu, mem);
    }
    table.render(out, ansi);
  }

  /** Prints a bordered FDE list table. */
  public static void printFdeTable(List<FdeStore.Fde> fdes, PrintStream out, Ansi ansi) {
    var table = new TableFormatter(" FDEs ", List.of("HANDLE", "NAME", "EMAIL", "ROLE", "STATUS"));
    for (var fde : fdes) {
      table.addRow(
          fde.handle(), orDash(fde.displayName()), orDash(fde.email()), fde.role(), fde.status());
    }
    table.render(out, ansi);
  }

  private static String orDash(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  /** Prints a bordered table of an FDE's registered SSH keys. */
  public static void printFdeKeyTable(
      List<FdeSshKeyStore.SshKeyInfo> keys, PrintStream out, Ansi ansi) {
    var table = new TableFormatter(" SSH Keys ", List.of("HANDLE", "COMMENT", "FINGERPRINT"));
    for (var key : keys) {
      table.addRow(key.fdeHandle(), orDash(key.comment()), key.fingerprint());
    }
    table.render(out, ansi);
  }

  /** Prints a bordered table of a project's shared files. */
  public static void printProjectFilesTable(
      List<FileStore.FileRow> rows, String project, PrintStream out, Ansi ansi) {
    var table = new TableFormatter(" Files: " + project + " ", List.of("PATH", "SIZE"));
    for (var row : rows) {
      table.addRow(row.path(), Base64.getDecoder().decode(row.content()).length + " B");
    }
    table.render(out, ansi);
  }

  /** Prints a bordered table of a project's agent sessions. */
  public static void printAgentSessionsTable(
      List<Map<String, Object>> sessions, String project, PrintStream out, Ansi ansi) {
    var table =
        new TableFormatter(
            " Agent Sessions: " + project + " ", List.of("STATUS", "AGENT", "SPEC", "STARTED"));
    for (var session : sessions) {
      table.addRow(
          Objects.toString(session.get("status"), "-"),
          Objects.toString(session.get("agent"), "-"),
          Objects.toString(session.get("spec_id"), "-"),
          Objects.toString(session.get("started_at"), "-"));
    }
    table.render(out, ansi);
  }

  /** Prints a success line for container destruction. */
  public static void printProjectDestroyed(String name, PrintStream out, Ansi ansi) {
    out.println(amber(ansi, "  @|bold,green \u2713 Project " + name + " destroyed.|@"));
  }

  /** Prints a bordered snapshot list table. */
  public static void printSnapshotTable(
      String containerName,
      List<SnapshotManager.SnapshotInfo> snapshots,
      PrintStream out,
      Ansi ansi) {
    var table =
        new TableFormatter(" Snapshots: " + containerName + " ", List.of("LABEL", "CREATED AT"));
    for (var s : snapshots) {
      table.addRow(s.name(), formatTimestamp(s.createdAt()));
    }
    table.render(out, ansi);
  }

  /** Prints a success line for snapshot creation. */
  public static void printSnapshotCreated(
      String containerName, String label, PrintStream out, Ansi ansi) {
    out.println(amber(ansi, "  @|bold,green \u2713 Snapshot created:|@ " + label));
  }

  /** Prints a success line for snapshot restore. */
  public static void printSnapshotRestored(
      String containerName, String label, PrintStream out, Ansi ansi) {
    out.println(
        amber(
            ansi, "  @|bold,green \u2713 Restored|@ " + containerName + " to snapshot: " + label));
  }

  /** Prints a bordered host status box showing server info, pool usage, and container summary. */
  public static void printHostStatus(
      HostYaml hostYaml,
      HostDetector.HostInfo hostInfo,
      ZfsQuery.PoolUsage poolUsage,
      DirQuery.FsUsage fsUsage,
      List<ContainerManager.ContainerInfo> containers,
      PrintStream out,
      Ansi ansi) {
    var hostname = Objects.requireNonNullElse(hostInfo.hostname(), "");
    var osName = Objects.requireNonNullElse(hostInfo.osPrettyName(), "");
    var cpu =
        hostInfo.cores() == hostInfo.threads()
            ? hostInfo.threads() + " cores"
            : hostInfo.cores() + " cores / " + hostInfo.threads() + " threads";
    var mem = NumberFormat.getNumberInstance(Locale.US).format(hostInfo.memoryMb()) + " MB";
    var running =
        containers.stream().filter(c -> c.state() instanceof ContainerState.Running).count();
    var stopped =
        containers.stream().filter(c -> c.state() instanceof ContainerState.Stopped).count();
    var summary = containers.size() + " total (" + running + " running, " + stopped + " stopped)";

    var w = W;
    w = Math.max(w, rowWidth("Hostname:", hostname));
    w = Math.max(w, rowWidth("OS:", osName));
    w = Math.max(w, rowWidth("CPU:", cpu));
    w = Math.max(w, rowWidth("RAM:", mem));
    w = Math.max(w, rowWidth("Containers:", summary));
    w =
        Math.max(
            w, rowWidth("Incus:", Objects.requireNonNullElse(hostYaml.incusVersion(), "unknown")));
    w = Math.max(w, rowWidth("Initialized:", formatTimestamp(hostYaml.initializedAt())));

    if (hostYaml.isZfs()) {
      var poolInfo =
          hostYaml.pool() + " on " + Objects.requireNonNullElse(hostYaml.poolDisk(), "?");
      w = Math.max(w, rowWidth("Pool:", poolInfo));
      if (poolUsage != null) {
        w = Math.max(w, rowWidth("Pool Size:", poolUsage.size()));
        w =
            Math.max(
                w,
                rowWidth(
                    "Pool Used:",
                    poolUsage.allocated() + " (" + poolUsage.capacityPercent() + ")"));
        w = Math.max(w, rowWidth("Pool Free:", poolUsage.free()));
      }
    } else {
      w = Math.max(w, rowWidth("Storage:", hostYaml.pool() + " (dir)"));
      if (fsUsage != null) {
        w = Math.max(w, rowWidth("Disk Size:", fsUsage.size()));
        w = Math.max(w, rowWidth("Disk Used:", fsUsage.used() + " (" + fsUsage.usePercent() + ")"));
        w = Math.max(w, rowWidth("Disk Free:", fsUsage.available()));
      }
      w = Math.max(w, rowWidth("Note:", "Quotas are advisory (not enforced)"));
    }

    out.println();
    var title = " Host Status ";
    out.println(
        amber(
            ansi,
            "  @|bold,cyan \u256d\u2500"
                + title
                + "\u2500".repeat(Math.max(1, w - title.length() - 1))
                + "\u256e|@"));

    emptyRow(out, ansi, w);
    labelRow(out, ansi, w, "Hostname:", hostname, null, null);
    labelRow(out, ansi, w, "OS:", osName, null, null);
    labelRow(out, ansi, w, "CPU:", cpu, null, null);
    labelRow(out, ansi, w, "RAM:", mem, null, null);
    emptyRow(out, ansi, w);

    if (hostYaml.isZfs()) {
      var poolInfo =
          hostYaml.pool() + " on " + Objects.requireNonNullElse(hostYaml.poolDisk(), "?");
      labelRow(out, ansi, w, "Pool:", poolInfo, null, null);
      if (poolUsage != null) {
        labelRow(out, ansi, w, "Pool Size:", poolUsage.size(), null, null);
        var used = poolUsage.allocated() + " (" + poolUsage.capacityPercent() + ")";
        labelRow(out, ansi, w, "Pool Used:", used, null, null);
        labelRow(out, ansi, w, "Pool Free:", poolUsage.free(), null, null);
      }
    } else {
      labelRow(out, ansi, w, "Storage:", hostYaml.pool() + " (dir)", null, null);
      if (fsUsage != null) {
        labelRow(out, ansi, w, "Disk Size:", fsUsage.size(), null, null);
        var used = fsUsage.used() + " (" + fsUsage.usePercent() + ")";
        labelRow(out, ansi, w, "Disk Used:", used, null, null);
        labelRow(out, ansi, w, "Disk Free:", fsUsage.available(), null, null);
      }
      labelRow(out, ansi, w, "Note:", "Quotas are advisory (not enforced)", null, null);
    }
    emptyRow(out, ansi, w);

    labelRow(
        out,
        ansi,
        w,
        "Incus:",
        Objects.requireNonNullElse(hostYaml.incusVersion(), "unknown"),
        null,
        null);
    labelRow(out, ansi, w, "Initialized:", formatTimestamp(hostYaml.initializedAt()), null, null);
    emptyRow(out, ansi, w);

    labelRow(out, ansi, w, "Containers:", summary, null, null);
    emptyRow(out, ansi, w);

    out.println(amber(ansi, "  @|bold,cyan \u2570" + "\u2500".repeat(w) + "\u256f|@"));
    out.println();
  }

  /** Prints a bordered Podman service table for a project container. */
  @SuppressWarnings("unchecked")
  public static void printPodmanTable(
      String containerName, List<Map<String, Object>> services, PrintStream out, Ansi ansi) {
    var table =
        new TableFormatter(" Services: " + containerName + " ", List.of("NAME", "IMAGE", "STATUS"));
    for (var svc : services) {
      var names = svc.get("Names");
      String name;
      if (names instanceof List<?> nameList && !nameList.isEmpty()) {
        name = String.valueOf(nameList.getFirst());
      } else if (names instanceof String s) {
        name = s;
      } else {
        name = Objects.toString(svc.get("Name"), "?");
      }
      var image = Objects.toString(svc.get("Image"), "?");
      if (image.length() > 20) {
        image = image.substring(0, 17) + "...";
      }
      var status = Objects.toString(svc.get("Status"), Objects.toString(svc.get("State"), "?"));
      table.addRow(name, image, status);
    }
    table.render(out, ansi);
  }

  /**
   * Strips the boilerplate prelude that {@code DispatchCommand.buildTaskPrompt} prepends to spec
   * dispatches and returns just the human-readable spec title. For ad-hoc tasks that do not start
   * with the dispatch prelude, the raw text is returned (truncated to 60 chars). Visible for tests.
   */
  static String prettifyTask(String raw) {
    if (raw == null) {
      return "";
    }
    var trimmed = raw.strip();
    if (trimmed.isEmpty()) {
      return "";
    }
    if (trimmed.startsWith("Your current spec: \"")) {
      var openQuote = trimmed.indexOf('"');
      var closeQuote = trimmed.indexOf('"', openQuote + 1);
      if (openQuote >= 0 && closeQuote > openQuote) {
        return trimmed.substring(openQuote + 1, closeQuote);
      }
    }
    return trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed;
  }

  /**
   * Formats the elapsed time between an ISO 8601 timestamp and {@code now} as a short
   * human-readable duration: {@code 12s}, {@code 4m 31s}, {@code 2h 14m}, or {@code 3d 5h}. Returns
   * an empty string when the timestamp is missing or unparseable. Visible for tests.
   */
  static String formatElapsed(String startedAtIso, Instant now) {
    if (Strings.isBlank(startedAtIso) || now == null) {
      return "";
    }
    Instant start;
    try {
      start = Instant.parse(startedAtIso);
    } catch (Exception e) {
      return "";
    }
    var d = Duration.between(start, now);
    if (d.isNegative()) {
      d = Duration.ZERO;
    }
    var total = d.getSeconds();
    if (total < 60) {
      return total + "s";
    }
    if (total < 3600) {
      return (total / 60) + "m " + (total % 60) + "s";
    }
    if (total < 86400) {
      return (total / 3600) + "h " + ((total % 3600) / 60) + "m";
    }
    return (total / 86400) + "d " + ((total % 86400) / 3600) + "h";
  }

  /**
   * Formats an ISO 8601 timestamp to a shorter display form. Extracts the date and time portion,
   * truncating fractional seconds and timezone.
   */
  private static String formatTimestamp(String iso) {
    if (iso == null) {
      return "-";
    }
    var t = iso.indexOf('T');
    if (t < 0) {
      return iso;
    }
    var date = iso.substring(0, t);
    var timeEnd = iso.length();
    for (var i = t + 1; i < iso.length(); i++) {
      var ch = iso.charAt(i);
      if (ch == '.' || ch == 'Z' || ch == '+' || ch == '-') {
        timeEnd = i;
        break;
      }
    }
    return date + " " + iso.substring(t + 1, timeEnd);
  }

  /** Prints a yellow overcommit warning with resource totals. */
  public static void printOvercommitWarning(
      ResourceChecker.OvercommitStatus status, PrintStream out, Ansi ansi) {
    if (!status.isOvercommitted()) {
      return;
    }
    out.println();
    var sb = new StringBuilder("  @|bold,yellow \u26a0 Overcommit warning:|@ ");
    if (status.cpuOvercommitted()) {
      sb.append("CPU ")
          .append(status.allocatedCpu())
          .append("/")
          .append(status.hostThreads())
          .append(" threads");
    }
    if (status.cpuOvercommitted() && status.memoryOvercommitted()) {
      sb.append(", ");
    }
    if (status.memoryOvercommitted()) {
      sb.append("Memory ")
          .append(status.allocatedMemoryMb() / 1024)
          .append("GB/")
          .append(status.hostMemoryMb() / 1024)
          .append("GB");
    }
    out.println(amber(ansi, sb.toString()));
    out.println(
        amber(
            ansi,
            "    @|faint Use 'sail project stop <name>' to stop projects and free resources.|@"));
  }

  /** Prints agent session status with optional git activity and task progress. */
  public static void printAgentStatus(
      String name,
      AgentSession.SessionInfo info,
      int commitCount,
      long lastCommitMinutesAgo,
      Map<String, Integer> taskCounts,
      PrintStream out,
      Ansi ansi) {
    var statusStr = info.running() ? "Running" : "Stopped";
    var statusStyle = info.running() ? "green" : "faint";
    out.println(
        amber(
            ansi,
            "  @|bold Agent session for "
                + name
                + ":|@ @|"
                + statusStyle
                + " "
                + statusStr
                + "|@"));
    out.println(amber(ansi, "    @|bold PID:|@        " + info.pid()));
    if (!info.task().isBlank()) {
      out.println(amber(ansi, "    @|bold Task:|@       " + prettifyTask(info.task())));
    }
    if (!info.startedAt().isBlank()) {
      out.println(amber(ansi, "    @|bold Started:|@    " + formatTimestamp(info.startedAt())));
      var elapsed = formatElapsed(info.startedAt(), DateTimeUtils.now());
      if (!elapsed.isEmpty()) {
        out.println(amber(ansi, "    @|bold Elapsed:|@    " + elapsed));
      }
    }
    if (!info.branch().isBlank()) {
      out.println(amber(ansi, "    @|bold Branch:|@     " + info.branch()));
    }
    if (commitCount > 0) {
      out.println(amber(ansi, "    @|bold Commits:|@    " + commitCount + " since launch"));
    }
    if (taskCounts != null) {
      var total = taskCounts.values().stream().mapToInt(Integer::intValue).sum();
      var done = taskCounts.getOrDefault("done", 0);
      var inProgress = taskCounts.getOrDefault("in_progress", 0);
      var pending = taskCounts.getOrDefault("pending", 0);
      out.println(
          amber(
              ansi,
              "    @|bold Tasks:|@      "
                  + done
                  + "/"
                  + total
                  + " done, "
                  + inProgress
                  + " in_progress, "
                  + pending
                  + " pending"));
    }
    out.println(amber(ansi, "    @|bold Log:|@        " + info.logPath()));
    if (info.running()) {
      out.println(amber(ansi, "    @|faint Tail output: sail agent log " + name + " --follow|@"));
    }
  }

  /** Prints a bordered table showing agent status across all projects. */
  public static void printAgentStatusTable(List<String[]> rows, PrintStream out, Ansi ansi) {
    var table =
        new TableFormatter(
            " Agent Status ", List.of("PROJECT", "STATUS", "ELAPSED", "COMMITS", "DETAIL"));
    for (var row : rows) {
      table.addRow(row);
    }
    table.render(out, ansi);
  }

  /** Prints confirmation after a background agent launch. */
  public static void printAgentLaunched(
      String name, String task, String branch, PrintStream out, Ansi ansi) {
    out.println(amber(ansi, "  @|green \u2713|@ Agent launched in background"));
    if (task != null) {
      out.println(amber(ansi, "    @|bold Task:|@    " + prettifyTask(task)));
    }
    if (Strings.isNotBlank(branch)) {
      out.println(amber(ansi, "    @|bold Branch:|@  " + branch));
    }
    out.println(amber(ansi, "    @|bold Log:|@     sail agent log " + name + " --follow"));
    out.println(amber(ansi, "    @|bold Stop:|@    sail agent stop " + name));
  }

  /** Prints a list of other running projects (informational). */
  public static void printAlsoRunning(
      List<ContainerManager.ContainerInfo> others, PrintStream out, Ansi ansi) {
    if (others.isEmpty()) {
      return;
    }
    out.println();
    out.println(amber(ansi, "  @|faint Also running:|@"));
    for (var c : others) {
      var ip = c.state() instanceof ContainerState.Running r && r.ipv4() != null ? r.ipv4() : "";
      var res = "";
      if (c.limits() != null) {
        var cpu = c.limits().cpu() != null ? c.limits().cpu() + " CPU" : "";
        var mem = c.limits().memory() != null ? c.limits().memory() : "";
        res = !cpu.isEmpty() && !mem.isEmpty() ? " (" + cpu + ", " + mem + ")" : "";
      }
      out.println(amber(ansi, "    @|faint \u2022 " + c.name() + " " + ip + res + "|@"));
    }
  }

  /** Prints a comprehensive morning-after agent report. */
  public static void printAgentReport(
      String name, AgentReporter.Report report, PrintStream out, Ansi ansi) {
    out.println();
    out.println(amber(ansi, "  @|bold Agent Report \u2014 " + name + "|@"));
    out.println(
        amber(ansi, "  @|faint " + "\u2500".repeat(Math.max(20, 16 + name.length())) + "|@"));
    out.println();

    var statusStyle =
        switch (report.sessionStatus()) {
          case "Running", "Completed" -> "green";
          case "Killed by guardrail" -> "yellow";
          case "Rolled back" -> "red";
          default -> "faint";
        };
    out.println(
        amber(
            ansi, "  @|bold Session:|@    @|" + statusStyle + " " + report.sessionStatus() + "|@"));
    if (report.duration() != null) {
      out.println(amber(ansi, "  @|bold Duration:|@   " + report.duration()));
    }
    if (report.branch() != null && !report.branch().isBlank()) {
      out.println(amber(ansi, "  @|bold Branch:|@     " + report.branch()));
    }
    out.println();

    if (!report.specs().isEmpty()) {
      out.println(amber(ansi, "  @|bold Specs:|@"));
      for (var spec : report.specs()) {
        var icon =
            switch (spec.status()) {
              case DONE -> "@|green \u2713|@";
              case IN_PROGRESS -> "@|yellow \u25cb|@";
              case REVIEW -> "@|cyan \u25cb|@";
              default -> "@|faint \u25cb|@";
            };
        var idPad = String.format("%-16s", spec.id());
        out.println(amber(ansi, "    " + icon + " " + idPad + spec.title()));
        if (!spec.dependsOn().isEmpty() && spec.status() == SpecStatus.PENDING) {
          var doneIds =
              report.specs().stream()
                  .filter(s -> s.status() == SpecStatus.DONE)
                  .map(Spec::id)
                  .collect(Collectors.toSet());
          var blocked = spec.dependsOn().stream().filter(d -> !doneIds.contains(d)).toList();
          if (!blocked.isEmpty()) {
            out.println(
                amber(
                    ansi,
                    "    @|faint "
                        + " ".repeat(18)
                        + "(blocked by: "
                        + String.join(", ", blocked)
                        + ")|@"));
          }
        }
      }
      out.println();
    }

    out.println(amber(ansi, "  @|bold Git Activity:|@"));
    if (report.commitCount() > 0) {
      var commitStr = "    " + report.commitCount() + " commits since launch";
      if (report.lastCommitMinutesAgo() >= 0) {
        commitStr += ", last commit " + report.lastCommitMinutesAgo() + "m ago";
      }
      out.println(amber(ansi, commitStr));
    } else {
      out.println(amber(ansi, "    @|faint No commits found.|@"));
    }
    out.println();

    if (report.guardrailTriggered()) {
      out.println(
          amber(
              ansi, "  @|bold,yellow Guardrails:|@  Triggered (" + report.guardrailReason() + ")"));
      out.println(amber(ansi, "    @|faint Action: " + report.guardrailAction() + "|@"));
    } else {
      out.println(amber(ansi, "  @|bold Guardrails:|@  @|green Not triggered|@"));
    }

    if (report.rolledBack()) {
      out.println(
          amber(
              ansi, "  @|bold,red Rollback:|@   Snapshot restored: " + report.rollbackSnapshot()));
    }
    out.println();

    out.println(amber(ansi, "  @|bold Next Steps:|@"));
    out.println(
        amber(ansi, "    sail agent log " + name + " --tail 50    @|faint # see agent output|@"));
    var remaining =
        report.specs().stream()
            .filter(s -> s.status() == SpecStatus.PENDING || s.status() == SpecStatus.IN_PROGRESS)
            .count();
    if (remaining > 0) {
      out.println(
          amber(
              ansi,
              "    sail spec dispatch --project "
                  + name
                  + "   @|faint # continue "
                  + remaining
                  + " remaining specs|@"));
    }
    out.println();
  }

  /**
   * Prints a label-value row with optional status indicator. All padding is computed on visible
   * character widths so borders always align regardless of ANSI markup.
   *
   * <p>Layout: │ Label: value ✓ │
   */
  private static void labelRow(
      PrintStream out, Ansi ansi, String label, String value, String indicator, String style) {
    labelRow(out, ansi, W, label, value, indicator, style);
  }

  private static void labelRow(
      PrintStream out,
      Ansi ansi,
      int width,
      String label,
      String value,
      String indicator,
      String style) {
    var gap = LABEL_COL - label.length();
    if (gap < 1) gap = 1;

    var used = 2 + label.length() + gap + value.length();

    var sb = new StringBuilder();
    sb.append("  @|bold,cyan \u2502|@  @|bold ").append(label).append("|@");
    sb.append(" ".repeat(gap));
    sb.append(value);

    if (indicator != null) {
      var indicatorPos = width - 4;
      var spaceBefore = indicatorPos - used;
      if (spaceBefore < 1) spaceBefore = 1;
      sb.append(" ".repeat(spaceBefore));
      sb.append("@|").append(style).append(" ").append(indicator).append("|@");
      var remaining = width - used - spaceBefore - 1;
      if (remaining > 0) sb.append(" ".repeat(remaining));
    } else {
      var pad = width - used;
      if (pad > 0) sb.append(" ".repeat(pad));
    }

    sb.append("@|bold,cyan \u2502|@");
    out.println(amber(ansi, sb.toString()));
  }

  /** Prints an empty row: │ │ */
  private static void emptyRow(PrintStream out, Ansi ansi) {
    emptyRow(out, ansi, W);
  }

  private static void emptyRow(PrintStream out, Ansi ansi, int width) {
    out.println(amber(ansi, "  @|bold,cyan \u2502|@" + " ".repeat(width) + "@|bold,cyan \u2502|@"));
  }

  /** Returns the width needed for a label-value pair (2 leading spaces + label + gap + value). */
  private static int rowWidth(String label, String value) {
    var gap = LABEL_COL - label.length();
    if (gap < 1) gap = 1;
    return 2 + label.length() + gap + value.length();
  }

  private static String serviceValue(Map.Entry<String, SailYaml.Service> entry) {
    var ports =
        entry.getValue().ports() != null && !entry.getValue().ports().isEmpty()
            ? " ("
                + entry.getValue().ports().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "))
                + ")"
            : "";
    return entry.getKey() + ports;
  }
}
