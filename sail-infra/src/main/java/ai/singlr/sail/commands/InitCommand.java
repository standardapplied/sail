/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
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
 * step sequence is decided by {@link InitPlan}; this class only runs each step. The box it sets up
 * is root's: sail-api is always the system service, whoever invoked {@code sudo}, and no step runs
 * as another account.
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

  private final Supplier<Box> box;

  public InitCommand() {
    this(Box::here);
  }

  InitCommand(Supplier<Box> box) {
    this.box = box;
  }

  /**
   * What init reads from this box and how it runs a step: whether it runs as root, the account that
   * invoked {@code sudo}, the host config, where an account's home is, and the granular command
   * each step runs. A test scripts the steps and puts the homes under a temporary directory.
   */
  record Box(
      boolean root, String sudoUser, Path hostConfig, Function<String, Path> homeOf, Steps steps) {

    static Box here() {
      return new Box(
          SailPaths.isRoot(),
          System.getenv("SUDO_USER"),
          SailPaths.hostConfigPath(),
          user -> Path.of("/home", user),
          InitCommand::runCommand);
    }
  }

  /** Runs one granular command with its arguments, failing when it fails. */
  @FunctionalInterface
  interface Steps {
    void run(Object command, String... args);
  }

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() {
    var intent = InitIntent.resolve(asMain, main);
    var box = this.box.get();
    requireRoot(box.root());
    requireNoUserService(box.sudoUser(), box.homeOf());

    var provisioned = Files.exists(box.hostConfig());
    System.out.println(Ansi.AUTO.string("  @|bold Setting up this box...|@"));
    if (provisioned) {
      System.out.println(Ansi.AUTO.string("  @|faint Host already provisioned — skipping.|@"));
    }
    for (var step : InitPlan.plan(intent, provisioned)) {
      runStep(step, box.steps());
    }
    if (intent instanceof InitIntent.Main) {
      printMainNextSteps();
    }
  }

  private void runStep(InitPlan.Step step, Steps steps) {
    switch (step) {
      case PROVISION -> steps.run(new HostInitCommand(), "--yes");
      case INSTALL_API_SYSTEM -> steps.run(new HostServiceInstallCommand());
      case SSH_IDENTITY -> steps.run(new HostSshIdentityCommand());
      case SYNC_AS_MAIN -> steps.run(new HostSyncCommand(), "--as-main");
      case JOIN_MAIN -> steps.run(new JoinCommand(), joinArgs());
    }
  }

  private void requireRoot(boolean root) {
    if (!root) {
      throw new IllegalStateException(
          "Root privileges required. Re-run with: sudo sail init "
              + (asMain ? "--as-main" : "--main " + main));
    }
  }

  /**
   * Refuses a box whose {@code sudo} user already runs sail-api as a user service: the system
   * service init installs would be a second control plane on the same port.
   */
  static void requireNoUserService(String sudoUser, Function<String, Path> homeOf) {
    if (Strings.isBlank(sudoUser) || "root".equals(sudoUser)) {
      return;
    }
    var unit =
        HostServiceInstallers.create(
            new ShellExecutor(false),
            SystemdServiceInstaller.Mode.USER,
            homeOf.apply(sudoUser),
            SailPaths::installedBinary);
    if (unit.isInstalled()) {
      throw new IllegalStateException(
          sudoUser
              + " already runs sail-api as a user service ("
              + unit.serviceFilePath()
              + "), so this box is set up for "
              + sudoUser
              + ": run sail as "
              + sudoUser
              + ", without sudo. To set the box up as root instead, first run 'sail host service"
              + " uninstall' as "
              + sudoUser
              + ".");
    }
  }

  private String[] joinArgs() {
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
    return args.toArray(String[]::new);
  }

  private void printMainNextSteps() {
    var out = System.out;
    out.println();
    out.println(
        Ansi.AUTO.string("  @|bold,green ✓|@ This box is the org's @|bold main|@ and ready."));
    out.println(
        "  Add each engineer once they send you the line their 'sudo sail init --main' printed,"
            + " e.g.:");
    out.println(
        Ansi.AUTO.string(
            "    @|cyan sudo sail fde add mady --role member --key \"…\" --main-host-key"
                + " SHA256:…|@"));
  }

  private static void runCommand(Object command, String... args) {
    var code = new CommandLine(command).execute(args);
    if (code != 0) {
      throw new IllegalStateException(
          Banner.errorLine("Setup stopped — the step above failed.", Ansi.OFF).strip());
    }
  }
}
