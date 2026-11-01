/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.auth.Passkeys;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import java.io.PrintStream;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import picocli.CommandLine.Help.Ansi;

/**
 * Renders styled ASCII banners for CLI output. Uses picocli's {@link Ansi#AUTO} for automatic TTY
 * detection — colors when interactive, plain text when piped.
 */
public final class Banner {

  private static final int W = 56;
  private static final int LABEL_COL = 13;

  /** The columns a box fits when sail prints to no terminal, or to one it cannot measure. */
  static final int DEFAULT_COLUMNS = 100;

  /**
   * The columns a box prints to when they are known ahead of measuring, as a test's terminal is;
   * unbound, {@link #columns()} measures the terminal.
   */
  static final ScopedValue<Integer> COLUMNS = ScopedValue.newInstance();

  /** What a row's status indicator takes at its end: a space, the indicator and three spaces. */
  private static final int INDICATOR_COLUMNS = 5;

  /** What a box's line holds beyond its inner width: its indent and its two borders. */
  private static final int BOX_EDGES = 4;

  /** The terminal's columns once measured; 0 until then. */
  private static volatile int measured;

  private Banner() {}

  private static final String BRAND = "\033[1;38;2;252;73;38m";
  private static final String CYAN_BOLD = "\033[1;36m";
  private static final String DIM = "\033[2m";
  private static final String RESET = "\033[0m";
  private static final List<String> WORDMARK =
      List.of(
          "     /\\      ███████╗  █████╗  ██╗ ██╗",
          "    /##\\     ██╔════╝ ██╔══██╗ ██║ ██║",
          "   /####\\    ███████╗ ███████║ ██║ ██║",
          "  /##/\\##\\   ╚════██║ ██╔══██║ ██║ ██║",
          " /##/  \\##\\  ███████║ ██║  ██║ ██║ ███████╗",
          "/__/    \\__\\ ╚══════╝ ╚═╝  ╚═╝ ╚═╝ ╚══════╝");

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

  /** Prints the SAIL ASCII wordmark and version, each line cut where it passes the terminal. */
  public static void printBranding(PrintStream out, Ansi ansi) {
    var width = columns();
    printIntroAnimation(out, ansi, width);
    var useColor = ansi != Ansi.OFF;
    var a = useColor ? BRAND : "";
    var d = useColor ? DIM : "";
    var r = useColor ? RESET : "";
    out.println();
    for (var art : WORDMARK) {
      out.println(Strings.fit("  " + a + art + r, width));
    }
    out.println();
    out.println(
        Strings.fit(
            "  " + d + "Isolated dev environments for AI agents  ·  v" + SailVersion.version() + r,
            width));
    out.println(Strings.fit("  " + d + "https://github.com/standardapplied/sail" + r, width));
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

  private static void printIntroAnimation(PrintStream out, Ansi ansi, int width) {
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
      out.print("\r" + Strings.fit("  " + color + frame + reset, width - 1));
      out.flush();
      sleepAnimationFrame();
    }
    out.print("\r" + " ".repeat(Math.max(0, width - 1)) + "\r");
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
    w = Math.max(w, rowWidth("OS:", os) + INDICATOR_COLUMNS);
    w = Math.max(w, rowWidth("CPU:", cpu));
    w = Math.max(w, rowWidth("RAM:", mem));
    w = Math.max(w, 2 + supportText.length());
    w = boxWidth(w);

    out.println();

    topBorder(out, ansi, w, " Host Detection ");

    emptyRow(out, ansi, w);
    labelRow(out, ansi, w, "Hostname:", host, null, null);
    labelRow(out, ansi, w, "OS:", os, check, checkStyle);
    labelRow(out, ansi, w, "CPU:", cpu, null, null);
    labelRow(out, ansi, w, "RAM:", mem, null, null);
    emptyRow(out, ansi, w);

    var support = Strings.fit(supportText, w - 2);
    boxLine(out, ansi, w, "  @|faint " + support + "|@", 2 + Strings.columns(support));

    emptyRow(out, ansi, w);

    bottomBorder(out, ansi, w);
    out.println();
  }

  /** Returns a formatted provisioning step progress line. */
  public static String stepLine(int step, int total, String description, Ansi ansi) {
    return amber(ansi, "  @|bold [" + step + "/" + total + "]|@ " + description);
  }

  /** Returns a formatted provisioning step completion line. */
  public static String stepDoneLine(int step, int total, String detail, Ansi ansi) {
    return amber(ansi, "  @|bold [" + step + "/" + total + "]|@ @|green \u2713|@ " + detail);
  }

  /**
   * A step that failed, named without its trailing ellipsis: its reason follows on its own line.
   */
  public static String stepFailedLine(int step, int total, String description, Ansi ansi) {
    var name =
        description.endsWith("...")
            ? description.substring(0, description.length() - 3)
            : description;
    return amber(ansi, "  @|bold [" + step + "/" + total + "]|@ @|bold,red \u2717|@ " + name);
  }

  /** Returns a formatted error line. */
  public static String errorLine(String message, Ansi ansi) {
    return amber(ansi, "  @|bold,red \u2717|@ " + message);
  }

  /** Returns a formatted warning line: the command carries on, the engineer should still act. */
  public static String warnLine(String message, Ansi ansi) {
    return amber(ansi, "  @|yellow \u26a0|@ " + message);
  }

  /** Prints the unsupported OS message. */
  public static void printUnsupported(HostDetector.HostInfo info, PrintStream out, Ansi ansi) {
    printLine(
        out, ansi, "  @|bold,red \u2717 Unsupported operating system:|@ " + info.osPrettyName());
    printLine(out, ansi, "    @|faint SAIL currently supports:|@ @|bold Ubuntu 24.04+|@");
  }

  /** Prints prerequisite check results — ✓ for present, ✗ for missing. */
  public static void printPrerequisites(
      PrerequisiteChecker.CheckResult result, PrintStream out, Ansi ansi) {
    out.println();
    printLine(out, ansi, "  @|bold Checking prerequisites...|@");
    for (var p : result.present()) {
      printLine(
          out,
          ansi,
          "    @|green \u2713|@ "
              + String.format("%-12s", p.command())
              + "@|faint "
              + p.reason()
              + "|@");
    }
    for (var p : result.missing()) {
      printLine(
          out,
          ansi,
          "    @|bold,red \u2717|@ "
              + String.format("%-12s", p.command())
              + "@|faint "
              + p.reason()
              + "|@");
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

    printLine(out, ansi, "  @|bold,red Missing commands:|@ " + commands);
    if (!packages.isEmpty()) {
      printLine(
          out, ansi, "  @|faint Install with:|@ @|bold apt-get install -y " + packages + "|@");
    }
    out.println();
  }

  /** Returns a formatted provisioning step skipped line. */
  public static String stepSkippedLine(int step, int total, String detail, Ansi ansi) {
    return amber(ansi, "  @|bold [" + step + "/" + total + "]|@ @|faint \u2192 " + detail + "|@");
  }

  /** Prints a bordered project summary box showing key configuration values. */
  public static void printProjectSummary(ProjectDefinition config, PrintStream out, Ansi ansi) {
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
    if (config.repos() != null && !config.repos().isEmpty()) {
      w = Math.max(w, rowWidth("Repos:", config.repos().size() + " repositories"));
    }
    w = boxWidth(w);

    out.println();
    topBorder(out, ansi, w, " Project ");

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
    if (config.repos() != null && !config.repos().isEmpty()) {
      labelRow(out, ansi, w, "Repos:", config.repos().size() + " repositories", null, null);
    }
    emptyRow(out, ansi, w);

    bottomBorder(out, ansi, w);
    out.println();
  }

  /** Prints a detailed project configuration view with live container status. */
  public static void printProjectConfig(
      ProjectDefinition config, ContainerState state, PrintStream out, Ansi ansi) {
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
    w = boxWidth(w);

    out.println();
    topBorder(out, ansi, w, " Project: " + config.name() + " ");

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

    bottomBorder(out, ansi, w);
    out.println();
  }

  /** Prints the project creation success message with the connect hint. */
  public static void printProjectCreated(
      String name, OperatorCommand operator, PrintStream out, Ansi ansi) {
    printLine(out, ansi, "  @|bold,green \u2713 Project " + name + " created.|@");
    printConnect(name, operator, out, ansi);
  }

  /**
   * Prints the SSH config snippet that the user should add to {@code ~/.ssh/config} on their Mac.
   * This is the same output as {@code sail project connect} but printed inline after project
   * creation. The jump host is aliased {@code boxHost}, this box's hostname.
   */
  public static void printSshConfig(
      String name,
      String boxHost,
      String serverIp,
      String serverUser,
      String containerIp,
      String identityFile,
      OperatorCommand operator,
      PrintStream out,
      Ansi ansi) {
    var alias = projectSshAlias(name, boxHost);
    out.println();
    printLine(out, ansi, "    @|faint # Add to ~/.ssh/config on your Mac:|@");
    out.println();
    printLine(out, ansi, "    @|faint Host " + boxHost + "|@");
    printLine(out, ansi, "    @|faint     HostName " + serverIp + "|@");
    printLine(out, ansi, "    @|faint     User " + serverUser + "|@");
    printLine(out, ansi, "    @|faint     IdentityFile " + identityFile + "|@");
    out.println();
    printLine(out, ansi, "    @|faint Host " + alias + "|@");
    printLine(out, ansi, "    @|faint     HostName " + containerIp + "|@");
    printLine(out, ansi, "    @|faint     User dev|@");
    printLine(out, ansi, "    @|faint     ProxyJump " + boxHost + "|@");
    printLine(out, ansi, "    @|faint     IdentityFile " + identityFile + "|@");
    out.println();
    printLine(out, ansi, "    @|bold \u2192 SSH:|@     ssh " + alias);
    printLine(out, ansi, "    @|bold \u2192 Shell:|@   " + operator.sail("project shell " + name));
  }

  /**
   * The alias a project's container is reached by: its name, unless that is the box's own alias,
   * which ssh would read as one host and jump the container through itself.
   */
  public static String projectSshAlias(String name, String boxHost) {
    return name.equalsIgnoreCase(boxHost) ? name + ".project" : name;
  }

  /** Prints resume information when a prior incomplete provisioning run is detected. */
  public static void printResumeInfo(ProvisionState state, PrintStream out, Ansi ansi) {
    out.println();
    printLine(out, ansi, "  @|yellow Resuming incomplete provisioning|@");
    if (state.completedPhase() != null) {
      printLine(out, ansi, "    @|faint Last completed:|@ " + state.completedPhase());
    }
    if (state.error() != null) {
      printLine(
          out,
          ansi,
          "    @|faint Prior failure:|@  "
              + state.error().failedPhase()
              + " \u2014 "
              + state.error().message());
    }
    out.println();
  }

  /** Prints the connect and shell hints after a container starts. */
  public static void printConnect(
      String name, OperatorCommand operator, PrintStream out, Ansi ansi) {
    printLine(
        out, ansi, "    @|bold \u2192 Connect:|@ " + operator.sail("project connect " + name));
    printLine(out, ansi, "    @|bold \u2192 Shell:|@   " + operator.sail("project shell " + name));
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
    printLine(
        out,
        ansi,
        "    @|bold \u2192 Ports:|@   ssh "
            + tunnelArgs
            + " "
            + user
            + "@"
            + projectSshAlias(name, HostInfo.hostname())
            + " -N");
  }

  /** Prints the agent auth tunnel hint: forwarding {@code port} for subscription-based login. */
  public static void printAgentAuthTunnel(String name, int port, PrintStream out, Ansi ansi) {
    printLine(
        out,
        ansi,
        "    @|bold \u2192 Agent auth:|@ ssh -N -L "
            + port
            + ":localhost:"
            + port
            + " "
            + projectSshAlias(name, HostInfo.hostname()));
  }

  /** Prints a container status line — green ✓ for running, faint ■ for stopped. */
  public static void printContainerStatus(
      String name, ContainerState state, PrintStream out, Ansi ansi) {
    switch (state) {
      case ContainerState.Running r -> {
        var ip = r.ipv4() != null ? " (" + r.ipv4() + ")" : "";
        printLine(out, ansi, "  @|green \u2713|@ " + name + " is running" + ip);
      }
      case ContainerState.Stopped ignored ->
          printLine(out, ansi, "  @|faint \u25a0 " + name + " is stopped|@");
      case ContainerState.NotCreated ignored ->
          printLine(out, ansi, "  @|faint \u25a0 " + name + " does not exist|@");
      case ContainerState.Error e ->
          printLine(out, ansi, "  @|bold,red \u2717|@ " + name + ": " + e.message());
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

  /** Prints a bordered FDE list table, {@code -} for a scope this box does not hold. */
  public static void printFdeTable(
      List<FdeStore.Fde> fdes,
      Function<String, Optional<ProjectScope>> scopeOf,
      PrintStream out,
      Ansi ansi) {
    var table =
        new TableFormatter(" FDEs ", List.of("HANDLE", "NAME", "EMAIL", "ROLE", "STATUS", "SCOPE"));
    for (var fde : fdes) {
      table.addRow(
          fde.handle(),
          orDash(fde.displayName()),
          orDash(fde.email()),
          fde.role(),
          fde.status(),
          scopeOf.apply(fde.handle()).map(Banner::scopeLabel).orElse("-"));
    }
    table.render(out, ansi);
  }

  /** {@code all}, or how many projects a scoped FDE is granted and their names. */
  static String scopeLabel(ProjectScope scope) {
    if (scope.unrestricted()) {
      return "all";
    }
    var projects = scope.projects();
    return projects.isEmpty() ? "none" : projects.size() + ": " + String.join(", ", projects);
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

  /** Prints a bordered table of an FDE's registered passkeys. */
  public static void printFdePasskeyTable(
      List<WebauthnCredentialStore.Credential> credentials,
      String handle,
      PrintStream out,
      Ansi ansi) {
    var table =
        new TableFormatter(
            " Passkeys: " + handle + " ",
            List.of("ID", "LABEL", "AUTHENTICATOR", "CREATED", "LAST USED", "SYNCED"));
    for (var credential : credentials) {
      table.addRow(
          Passkeys.shortId(credential.credentialId()),
          Passkeys.displayLabel(credential),
          Passkeys.authenticatorName(credential.aaguid()),
          datePart(DateTimeUtils.format(credential.createdAt())),
          datePart(DateTimeUtils.format(credential.lastUsedAt())),
          credential.backupState() ? "yes" : "no");
    }
    table.render(out, ansi);
  }

  private static String datePart(String instant) {
    return instant == null || instant.length() < 10 ? "-" : instant.substring(0, 10);
  }

  /** Prints a bordered table of a project's shared files. */
  public static void printProjectFilesTable(
      List<FileStore.FileRow> rows, String project, PrintStream out, Ansi ansi) {
    var table = new TableFormatter(" Files: " + project + " ", List.of("PATH", "SIZE"));
    for (var row : rows) {
      table.addRow(row.path(), row.size() + " B");
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
    printLine(out, ansi, "  @|bold,green \u2713 Project " + name + " destroyed.|@");
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
    printLine(out, ansi, "  @|bold,green \u2713 Snapshot created:|@ " + label);
  }

  /** Prints a success line for snapshot restore. */
  public static void printSnapshotRestored(
      String containerName, String label, PrintStream out, Ansi ansi) {
    printLine(
        out, ansi, "  @|bold,green \u2713 Restored|@ " + containerName + " to snapshot: " + label);
  }

  /**
   * Prints a bordered host status box showing server info, pool usage, and container summary. The
   * Incus version is the one installed now, not the one {@code host.yaml} recorded at init; {@code
   * incusUpgrade}, the command that moves it, has a row of its own when there is one, so neither is
   * cut to fit.
   */
  public static void printHostStatus(
      HostYaml hostYaml,
      String incusVersion,
      String incusUpgrade,
      HostDetector.HostInfo hostInfo,
      ZfsQuery.PoolUsage poolUsage,
      DirQuery.FsUsage fsUsage,
      String containers,
      PrintStream out,
      Ansi ansi) {
    var hostname = Objects.requireNonNullElse(hostInfo.hostname(), "");
    var osName = Objects.requireNonNullElse(hostInfo.osPrettyName(), "");
    var cpu =
        hostInfo.cores() == hostInfo.threads()
            ? hostInfo.threads() + " cores"
            : hostInfo.cores() + " cores / " + hostInfo.threads() + " threads";
    var mem = NumberFormat.getNumberInstance(Locale.US).format(hostInfo.memoryMb()) + " MB";

    var w = W;
    w = Math.max(w, rowWidth("Hostname:", hostname));
    w = Math.max(w, rowWidth("OS:", osName));
    w = Math.max(w, rowWidth("CPU:", cpu));
    w = Math.max(w, rowWidth("RAM:", mem));
    w = Math.max(w, rowWidth("Containers:", containers));
    w = Math.max(w, rowWidth("Incus:", incusVersion));
    if (incusUpgrade != null) {
      w = Math.max(w, rowWidth("Upgrade:", incusUpgrade));
    }
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
    w = boxWidth(w);

    out.println();
    topBorder(out, ansi, w, " Host Status ");

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

    labelRow(out, ansi, w, "Incus:", incusVersion, null, null);
    if (incusUpgrade != null) {
      labelRow(out, ansi, w, "Upgrade:", incusUpgrade, null, null);
    }
    labelRow(out, ansi, w, "Initialized:", formatTimestamp(hostYaml.initializedAt()), null, null);
    emptyRow(out, ansi, w);

    labelRow(out, ansi, w, "Containers:", containers, null, null);
    emptyRow(out, ansi, w);

    bottomBorder(out, ansi, w);
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
      ResourceChecker.OvercommitStatus status,
      OperatorCommand operator,
      PrintStream out,
      Ansi ansi) {
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
    printLine(out, ansi, sb.toString());
    printLine(
        out,
        ansi,
        "    @|faint Use '"
            + operator.sail("project stop <project>")
            + "' to stop projects and free resources.|@");
  }

  /** Prints agent session status with optional git activity and task progress. */
  public static void printAgentStatus(
      String name,
      AgentSession.SessionInfo info,
      int commitCount,
      long lastCommitMinutesAgo,
      Map<String, Integer> taskCounts,
      OperatorCommand operator,
      PrintStream out,
      Ansi ansi) {
    var statusStr = info.running() ? "Running" : "Stopped";
    var statusStyle = info.running() ? "green" : "faint";
    printLine(
        out,
        ansi,
        "  @|bold Agent session for " + name + ":|@ @|" + statusStyle + " " + statusStr + "|@");
    printLine(out, ansi, "    @|bold PID:|@        " + info.pid());
    if (!info.task().isBlank()) {
      printLine(out, ansi, "    @|bold Task:|@       " + prettifyTask(info.task()));
    }
    if (!info.startedAt().isBlank()) {
      printLine(out, ansi, "    @|bold Started:|@    " + formatTimestamp(info.startedAt()));
      var elapsed = formatElapsed(info.startedAt(), DateTimeUtils.now());
      if (!elapsed.isEmpty()) {
        printLine(out, ansi, "    @|bold Elapsed:|@    " + elapsed);
      }
    }
    if (!info.branch().isBlank()) {
      printLine(out, ansi, "    @|bold Branch:|@     " + info.branch());
    }
    if (commitCount > 0) {
      printLine(out, ansi, "    @|bold Commits:|@    " + commitCount + " since launch");
    }
    if (taskCounts != null) {
      var total = taskCounts.values().stream().mapToInt(Integer::intValue).sum();
      var done = taskCounts.getOrDefault("done", 0);
      var inProgress = taskCounts.getOrDefault("in_progress", 0);
      var pending = taskCounts.getOrDefault("pending", 0);
      printLine(
          out,
          ansi,
          "    @|bold Tasks:|@      "
              + done
              + "/"
              + total
              + " done, "
              + inProgress
              + " in_progress, "
              + pending
              + " pending");
    }
    printLine(out, ansi, "    @|bold Log:|@        " + info.logPath());
    if (info.running()) {
      printLine(
          out,
          ansi,
          "    @|faint Tail output: " + operator.sail("agent log " + name + " --follow") + "|@");
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

  /** Prints a work branch cut, or reused, in {@code repoPath}, as a dispatch and a run say it. */
  public static void printBranchReady(
      String branch, String repoPath, boolean reused, PrintStream out, Ansi ansi) {
    var verb = reused ? "Reusing branch:" : "Creating branch:";
    printLine(out, ansi, "  @|bold " + verb + "|@ " + branch + " in " + repoPath);
    printLine(out, ansi, "  @|green \u2713|@ Branch " + branch + " in " + repoPath);
    out.println();
  }

  /** Prints that no repo of the project could take {@code branch}. */
  public static void printBranchUnavailable(String branch, PrintStream out, Ansi ansi) {
    printLine(out, ansi, "  @|faint Branch:|@ " + branch + " (create manually in repo)");
    out.println();
  }

  /** Prints confirmation after a background agent launch. */
  public static void printAgentLaunched(
      String name,
      String task,
      String branch,
      OperatorCommand operator,
      PrintStream out,
      Ansi ansi) {
    printLine(out, ansi, "  @|green \u2713|@ Agent launched in background");
    if (task != null) {
      printLine(out, ansi, "    @|bold Task:|@    " + prettifyTask(task));
    }
    if (Strings.isNotBlank(branch)) {
      printLine(out, ansi, "    @|bold Branch:|@  " + branch);
    }
    printLine(
        out, ansi, "    @|bold Log:|@     " + operator.sail("agent log " + name + " --follow"));
    printLine(out, ansi, "    @|bold Stop:|@    " + operator.sail("agent stop " + name));
  }

  /** Prints a list of other running projects (informational). */
  public static void printAlsoRunning(
      List<ContainerManager.ContainerInfo> others, PrintStream out, Ansi ansi) {
    if (others.isEmpty()) {
      return;
    }
    out.println();
    printLine(out, ansi, "  @|faint Also running:|@");
    for (var c : others) {
      var ip = c.state() instanceof ContainerState.Running r && r.ipv4() != null ? r.ipv4() : "";
      var res = "";
      if (c.limits() != null) {
        var cpu = c.limits().cpu() != null ? c.limits().cpu() + " CPU" : "";
        var mem = c.limits().memory() != null ? c.limits().memory() : "";
        res = !cpu.isEmpty() && !mem.isEmpty() ? " (" + cpu + ", " + mem + ")" : "";
      }
      printLine(out, ansi, "    @|faint \u2022 " + c.name() + " " + ip + res + "|@");
    }
  }

  /** Prints a comprehensive morning-after agent report. */
  public static void printAgentReport(
      String name,
      AgentReporter.Report report,
      OperatorCommand operator,
      PrintStream out,
      Ansi ansi) {
    out.println();
    printLine(out, ansi, "  @|bold Agent Report \u2014 " + name + "|@");
    printLine(out, ansi, "  @|faint " + "\u2500".repeat(Math.max(20, 16 + name.length())) + "|@");
    out.println();

    var statusStyle =
        switch (report.sessionStatus()) {
          case "Running", "Completed" -> "green";
          case "Killed by guardrail" -> "yellow";
          case "Rolled back" -> "red";
          default -> "faint";
        };
    printLine(
        out, ansi, "  @|bold Session:|@    @|" + statusStyle + " " + report.sessionStatus() + "|@");
    if (report.duration() != null) {
      printLine(out, ansi, "  @|bold Duration:|@   " + report.duration());
    }
    if (report.branch() != null && !report.branch().isBlank()) {
      printLine(out, ansi, "  @|bold Branch:|@     " + report.branch());
    }
    out.println();

    if (!report.specs().isEmpty()) {
      printLine(out, ansi, "  @|bold Specs:|@");
      for (var spec : report.specs()) {
        var icon =
            switch (spec.status()) {
              case DONE -> "@|green \u2713|@";
              case IN_PROGRESS -> "@|yellow \u25cb|@";
              case REVIEW -> "@|cyan \u25cb|@";
              case AWAITING_MERGE -> "@|magenta \u25cb|@";
              case CANCELLED -> "@|red \u2717|@";
              default -> "@|faint \u25cb|@";
            };
        var idPad = String.format("%-16s", spec.id());
        printLine(out, ansi, "    " + icon + " " + idPad + spec.title());
        if (!spec.dependsOn().isEmpty() && spec.status() == SpecStatus.PENDING) {
          var doneIds =
              report.specs().stream()
                  .filter(s -> s.status() == SpecStatus.DONE)
                  .map(Spec::id)
                  .collect(Collectors.toSet());
          var blocked = spec.dependsOn().stream().filter(d -> !doneIds.contains(d)).toList();
          if (!blocked.isEmpty()) {
            printLine(
                out,
                ansi,
                "    @|faint "
                    + " ".repeat(18)
                    + "(blocked by: "
                    + String.join(", ", blocked)
                    + ")|@");
          }
        }
      }
      out.println();
    }

    printLine(out, ansi, "  @|bold Git Activity:|@");
    if (report.commitCount() > 0) {
      var commitStr = "    " + report.commitCount() + " commits since launch";
      if (report.lastCommitMinutesAgo() >= 0) {
        commitStr += ", last commit " + report.lastCommitMinutesAgo() + "m ago";
      }
      printLine(out, ansi, commitStr);
    } else {
      printLine(out, ansi, "    @|faint No commits found.|@");
    }
    out.println();

    if (report.guardrailTriggered()) {
      printLine(
          out, ansi, "  @|bold,yellow Guardrails:|@  Triggered (" + report.guardrailReason() + ")");
      printLine(out, ansi, "    @|faint Action: " + report.guardrailAction() + "|@");
    } else {
      printLine(out, ansi, "  @|bold Guardrails:|@  @|green Not triggered|@");
    }

    if (report.rolledBack()) {
      printLine(
          out, ansi, "  @|bold,red Rollback:|@   Snapshot restored: " + report.rollbackSnapshot());
    }
    out.println();

    printLine(out, ansi, "  @|bold Next Steps:|@");
    printLine(
        out,
        ansi,
        "    "
            + operator.sail("agent log " + name + " --tail 50")
            + "    @|faint # see agent output|@");
    var remaining =
        report.specs().stream()
            .filter(s -> s.status() == SpecStatus.PENDING || s.status() == SpecStatus.IN_PROGRESS)
            .count();
    if (remaining > 0) {
      printLine(
          out,
          ansi,
          "    "
              + operator.sail("spec dispatch --project " + name)
              + "   @|faint # continue "
              + remaining
              + " remaining specs|@");
    }
    out.println();
  }

  /**
   * Prints a label-value row with optional status indicator. All padding is computed on the columns
   * the text spans so borders always align regardless of ANSI markup, and a label or value that
   * would pass the border is cut with "…".
   *
   * <p>Layout: │ Label: value ✓ │
   */
  private static void labelRow(
      PrintStream out,
      Ansi ansi,
      int width,
      String label,
      String value,
      String indicator,
      String style) {
    var marked = indicator != null && width - 2 >= INDICATOR_COLUMNS;
    var room = width - 2 - (marked ? INDICATOR_COLUMNS : 0);
    var head = Strings.fit(label, room);
    var gap = " ".repeat(Math.max(1, LABEL_COL - label.length()));
    var tail = Strings.fit(gap + value, room - Strings.columns(head));
    var pad = " ".repeat(Math.max(0, room - Strings.columns(head) - Strings.columns(tail)));
    var mark = marked ? " @|" + style + " " + indicator + "|@   " : "";
    boxLine(out, ansi, width, "  @|bold " + head + "|@" + tail + pad + mark, width);
  }

  static void emptyRow(PrintStream out, Ansi ansi, int width) {
    boxLine(out, ansi, width, "", 0);
  }

  /** A box's line: {@code markup}, which spans {@code shown} columns, padded to {@code width}. */
  static void boxLine(PrintStream out, Ansi ansi, int width, String markup, int shown) {
    printBoxed(
        out,
        ansi,
        width,
        "  @|bold,cyan \u2502|@"
            + markup
            + " ".repeat(Math.max(0, width - shown))
            + "@|bold,cyan \u2502|@");
  }

  /** A box's top border, {@code width} wide inside, naming {@code title} or as much as fits. */
  static void topBorder(PrintStream out, Ansi ansi, int width, String title) {
    var name = Strings.fit(title, width - 2);
    printBoxed(
        out,
        ansi,
        width,
        "  @|bold,cyan \u256d\u2500"
            + name
            + "\u2500".repeat(Math.max(0, width - 1 - Strings.columns(name)))
            + "\u256e|@");
  }

  static void bottomBorder(PrintStream out, Ansi ansi, int width) {
    printBoxed(
        out,
        ansi,
        width,
        "  @|bold,cyan \u2570" + "\u2500".repeat(Math.max(0, width)) + "\u256f|@");
  }

  /**
   * Prints a line outside any box as one line, whole. It carries a command, a path or a branch the
   * operator copies, which a cut would leave unrunnable; a terminal narrower than it wraps it, and
   * no border falls apart.
   */
  private static void printLine(PrintStream out, Ansi ansi, String markup) {
    out.println(Strings.oneLine(amber(ansi, markup)));
  }

  /**
   * Prints a line of a box {@code width} wide inside, cut where it passes the box's edge: a box
   * narrowed below what its indent, borders and text need, in a terminal only a few columns wide,
   * prints as much of each line as the terminal holds.
   */
  private static void printBoxed(PrintStream out, Ansi ansi, int width, String markup) {
    out.println(Strings.fit(amber(ansi, markup), width + BOX_EDGES));
  }

  /**
   * The inner width of a box whose widest row needs {@code natural}: that, unless the terminal is
   * narrower, when the box spans the terminal and its rows cut what overflows.
   */
  static int boxWidth(int natural) {
    return Math.min(natural, columns() - BOX_EDGES);
  }

  /**
   * The columns of the terminal sail prints to: {@link #COLUMNS} where it is bound, else the
   * terminal stdout reaches, measured through {@code /dev/tty}, or {@link #DEFAULT_COLUMNS} when
   * stdout is a pipe or a file. Stdout is the test, not stdin, so a command fed a heredoc or {@code
   * </dev/null} still fits the terminal it prints to. Measured once per process, two shell-outs, so
   * a report of twenty lines asks once.
   */
  static int columns() {
    if (COLUMNS.isBound()) {
      return COLUMNS.get();
    }
    if (measured == 0) {
      measured = columns(Stty.printsToTerminal(), () -> Stty.size(new int[] {0, 0})[1]);
    }
    return measured;
  }

  /**
   * {@link #columns()} given whether stdout reaches a {@code terminal} and that terminal's width.
   */
  static int columns(boolean terminal, IntSupplier ttyColumns) {
    var measured = terminal ? ttyColumns.getAsInt() : 0;
    return measured > 0 ? measured : DEFAULT_COLUMNS;
  }

  /** Returns the width needed for a label-value pair (2 leading spaces + label + gap + value). */
  private static int rowWidth(String label, String value) {
    var gap = LABEL_COL - label.length();
    if (gap < 1) gap = 1;
    return 2 + label.length() + gap + Strings.columns(value);
  }

  private static String serviceValue(Map.Entry<String, ProjectDefinition.Service> entry) {
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
