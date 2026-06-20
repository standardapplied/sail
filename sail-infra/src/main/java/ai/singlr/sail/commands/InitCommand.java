/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * One command to bring a box fully online and connect it to the org. It converges from whatever
 * state the box is in — fresh, or already partly set up — doing only what is missing, so it is safe
 * to re-run. Every box is a full working dev box: it provisions the container host, installs and
 * starts {@code sail-api} (so the engineer can dispatch agents locally), and then takes on its
 * identity — {@code --as-main} makes it the org's source of truth, {@code --main <target>} joins it
 * to an existing one.
 *
 * <p>It orchestrates the granular commands ({@code host init}, {@code host service install}, {@code
 * host ssh-identity}, {@code host sync}, {@code join}), which remain available on their own. The
 * step sequence is decided by {@link InitPlan}; this class only runs each step. The sail-api
 * install adapts to the box: a system service when run as root directly, a per-user service for the
 * {@code sudo} user otherwise.
 */
@Command(
    name = "init",
    description = "Set up this box and connect it to the org in one step.",
    mixinStandardHelpOptions = true)
public final class InitCommand implements Runnable {

  @Option(
      names = "--as-main",
      description = "Make this box the org's main devbox (the source of truth).")
  private boolean asMain;

  @Option(
      names = "--main",
      paramLabel = "TARGET",
      description = "Join an existing main, e.g. maindevbox or sail@maindevbox.")
  private String main;

  @Option(names = "--handle", description = "Your FDE handle on main (node only; else prompted).")
  private String handle;

  @Option(names = "--name", description = "Your full name for the roster (node only).")
  private String fullName;

  @Option(names = "--email", description = "Your email for the roster (node only).")
  private String email;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() {
    var intent = InitIntent.resolve(asMain, main);
    requireRoot();

    var sudoUser = System.getenv("SUDO_USER");
    var perUserService = Strings.isNotBlank(sudoUser) && !"root".equals(sudoUser);
    var plan = InitPlan.plan(intent, Files.exists(SailPaths.hostConfigPath()), perUserService);

    System.out.println(Ansi.AUTO.string("  @|bold Setting up this box...|@"));
    if (Files.exists(SailPaths.hostConfigPath())) {
      System.out.println(Ansi.AUTO.string("  @|faint Host already provisioned — skipping.|@"));
    }
    for (var step : plan) {
      runStep(step, sudoUser);
    }
    if (intent instanceof InitIntent.Main) {
      printMainNextSteps();
    }
  }

  private void runStep(InitPlan.Step step, String sudoUser) {
    switch (step) {
      case PROVISION -> runCommand(new HostInitCommand(), "--yes");
      case INSTALL_API_SYSTEM -> runCommand(new HostServiceInstallCommand());
      case INSTALL_API_USER -> installApiForUser(sudoUser);
      case SSH_IDENTITY -> runCommand(new HostSshIdentityCommand());
      case SYNC_AS_MAIN -> runCommand(new HostSyncCommand(), "--as-main");
      case JOIN_MAIN -> joinMain();
    }
  }

  private void requireRoot() {
    if (ConsoleHelper.isRoot() || Strings.isNotBlank(System.getenv("SUDO_USER"))) {
      return;
    }
    throw new IllegalStateException(
        "Root privileges required. Re-run with: sudo sail init "
            + (asMain ? "--as-main" : "--main " + main));
  }

  /**
   * Installs sail-api as a per-user service for the invoking {@code sudo} user, which needs linger
   * to survive logout and must be installed as that user.
   */
  private void installApiForUser(String sudoUser) {
    var shell = new ShellExecutor(false);
    try {
      shell.exec(List.of("loginctl", "enable-linger", sudoUser));
      var result =
          shell.exec(
              List.of("su", "-", sudoUser, "-c", SailPaths.binaryPath() + " host service install"));
      if (!result.ok()) {
        throw new IllegalStateException(result.stderr().strip());
      }
      System.out.print(result.stdout());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Could not install sail-api for '"
              + sudoUser
              + "': "
              + e.getMessage()
              + "\n  Install it manually as that user (no sudo): sail host service install");
    }
  }

  private void joinMain() {
    var args = new ArrayList<String>();
    args.add(main);
    if (Strings.isNotBlank(handle)) {
      args.add("--handle");
      args.add(handle);
    }
    if (Strings.isNotBlank(fullName)) {
      args.add("--name");
      args.add(fullName);
    }
    if (Strings.isNotBlank(email)) {
      args.add("--email");
      args.add(email);
    }
    runCommand(new JoinCommand(), args.toArray(String[]::new));
  }

  private void printMainNextSteps() {
    var out = System.out;
    out.println();
    out.println(
        Ansi.AUTO.string("  @|bold,green ✓|@ This box is the org's @|bold main|@ and ready."));
    out.println(
        "  Add each engineer once they send you the line their 'sail init --main' printed, e.g.:");
    out.println(Ansi.AUTO.string("    @|cyan sail fde add mady --role member --key \"…\"|@"));
  }

  private static void runCommand(Object command, String... args) {
    var code = new CommandLine(command).execute(args);
    if (code != 0) {
      throw new IllegalStateException(
          Banner.errorLine("Setup stopped — the step above failed.", Ansi.OFF).strip());
    }
  }
}
