/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.Main;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.PlaceholderResolver;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.LocalIdentity;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Consumer;
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

  @Spec private CommandSpec spec;

  @Option(
      names = "--as-main",
      description = "Make this box the org's main devbox (the source of truth).")
  private boolean asMain;

  @Option(
      names = "--main",
      paramLabel = "TARGET",
      description = "Join an existing main, e.g. maindevbox or sail@maindevbox.")
  private String main;

  @Option(
      names = "--handle",
      description =
          "Your FDE handle: main's operator, added there as admin, or the one a node's main"
              + " authorises (else prompted).")
  private String handle;

  @Option(
      names = "--name",
      description =
          "Your full name: this box's git user.name when it has none, and your roster name"
              + " (else prompted).")
  private String fullName;

  @Option(
      names = "--email",
      description =
          "Your email: this box's git user.email when it has none, and your roster email"
              + " (else prompted).")
  private String email;

  private final Supplier<Box> box;

  public InitCommand() {
    this(Box::here);
  }

  InitCommand(Supplier<Box> box) {
    this.box = box;
  }

  /**
   * What init reads from this box and how it runs a step: whether it runs as root, the account that
   * invoked {@code sudo}, the host config, where an account's home is, the git identity a project
   * applied here takes on, how it asks the operator a question, how it names a command to type as
   * the box stands when it is asked (init's own steps install the system service that decides it),
   * and the granular command each step runs. A test scripts the steps and puts the homes under a
   * temporary directory.
   */
  record Box(
      boolean root,
      String sudoUser,
      Path hostConfig,
      Function<String, Path> homeOf,
      LocalIdentity identity,
      Prompt prompt,
      OperatorCommand operator,
      Steps steps) {

    static Box here() {
      var operator = OperatorCommand.here();
      return new Box(
          SailPaths.isRoot(),
          System.getenv("SUDO_USER"),
          SailPaths.hostConfigPath(),
          SailPaths::accountHome,
          LocalIdentity.detect(operator),
          InitCommand::ask,
          operator,
          InitCommand::runCommand);
    }
  }

  /** Asks the operator for {@code label}, answering {@code null} when there is no terminal. */
  @FunctionalInterface
  interface Prompt {
    String ask(String label);
  }

  /** Runs one granular command with its arguments, failing when it fails. */
  @FunctionalInterface
  interface Steps {
    void run(Object command, String... args);
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var intent = InitIntent.resolve(asMain, main);
    var box = this.box.get();
    requireRoot(box.root());
    requireNoUserService(box.sudoUser(), box.homeOf());
    var provisioned = Files.exists(box.hostConfig());
    var fde = intent instanceof InitIntent.Main ? mainHandle(box, provisioned) : handle;
    var name = gitChoice(box, PlaceholderResolver.GIT_NAME, fullName, "Your full name", "--name");
    var mail = gitChoice(box, PlaceholderResolver.GIT_EMAIL, email, "Your email", "--email");
    var roster = roster(intent, box.identity(), name, mail);

    System.out.println(Ansi.AUTO.string("  @|bold Setting up this box...|@"));
    if (provisioned) {
      System.out.println(Ansi.AUTO.string("  @|faint Host already provisioned — skipping.|@"));
    }
    for (var step : InitPlan.plan(intent, provisioned)) {
      runStep(step, box.steps(), fde, roster.name(), roster.email());
    }
    name.adopt(box.identity());
    mail.adopt(box.identity());
    if (intent instanceof InitIntent.Main) {
      printMainNextSteps(box.operator());
    }
  }

  /**
   * Main's operator, the FDE dispatch picks this box's specs for: {@code --handle}, else the handle
   * the box already acts as, else asked for. Settled before the first step, so a box acting as
   * another FDE is refused untouched ({@link HostSyncCommand#requireHandle}).
   */
  private String mainHandle(Box box, boolean provisioned) throws Exception {
    var held =
        provisioned ? HostYaml.fromMap(YamlUtil.parseFile(box.hostConfig())).sync().handle() : null;
    var chosen =
        Strings.isNotBlank(handle)
            ? handle
            : Strings.isNotBlank(held) ? held : box.prompt().ask("Your FDE handle");
    if (Strings.isBlank(chosen)) {
      throw new IllegalStateException(
          "Main's operator is an FDE, the one dispatch picks this box's specs for. Re-run with"
              + " --handle <handle>.");
    }
    var fde = chosen.strip();
    NameValidator.requireValidFdeHandle(fde);
    HostSyncCommand.requireHandle(held, fde, box.operator());
    return fde;
  }

  /**
   * The operator's {@code placeholder} value: {@code flag}, or else asked for when this box's git
   * identity holds none. Asked before the first step, so a long setup waits on no question, and
   * adopted after the last, once the box is provisioned. A box left with no value is refused before
   * any step: every project applied here by name would be refused for it.
   */
  private static GitChoice gitChoice(
      Box box, String placeholder, String flag, String label, String option) {
    if (box.identity().gitValue(placeholder).isPresent()) {
      return new GitChoice(placeholder, flag, false);
    }
    var chosen = Strings.isNotBlank(flag) ? flag : box.prompt().ask(label);
    if (Strings.isBlank(chosen)) {
      throw new IllegalStateException(
          "This box has no git "
              + LocalIdentity.gitKey(placeholder)
              + " set, so a project here can't take on your identity. Re-run with "
              + option
              + " \"...\".");
    }
    return new GitChoice(placeholder, chosen.strip(), true);
  }

  /**
   * A git identity value init carries: {@code value} is what a node's join is given, and {@code
   * unset} says the box's git identity has none, so init gives it this one; an identity the box
   * already holds is never overwritten.
   */
  private record GitChoice(String placeholder, String value, boolean unset) {
    void adopt(LocalIdentity identity) throws Exception {
      if (unset) {
        identity.adoptGit(placeholder, value);
      }
    }

    /** {@code value}, stripped, or what {@code identity} holds when none was given. */
    String orHeld(LocalIdentity identity) {
      return Strings.isNotBlank(value)
          ? value.strip()
          : identity.gitValue(placeholder).orElse(null);
    }
  }

  /** What the operator's roster entry is given. */
  private record Roster(String name, String email) {}

  /**
   * Main's roster entry takes the flags, else the git identity the box has, refused before any step
   * when {@code host sync} would refuse them; a node passes its join what was given or asked, as
   * before.
   */
  private static Roster roster(
      InitIntent intent, LocalIdentity identity, GitChoice name, GitChoice mail) {
    if (!(intent instanceof InitIntent.Main)) {
      return new Roster(name.value(), mail.value());
    }
    var rosterName = name.orHeld(identity);
    var rosterMail = mail.orHeld(identity);
    requireValid(rosterName, NameValidator::requireValidDisplayName, "--name");
    requireValid(rosterMail, NameValidator::requireValidEmail, "--email");
    return new Roster(rosterName, rosterMail);
  }

  /**
   * Refuses, before any step, a roster value main's {@code host sync} would refuse ({@code rule}),
   * naming {@code option}.
   */
  private static void requireValid(String value, Consumer<String> rule, String option) {
    try {
      rule.accept(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          e.getMessage() + " Re-run with " + option + " \"...\".", e);
    }
  }

  private static String ask(String label) {
    if (!ConsoleHelper.hasConsole()) {
      return null;
    }
    System.out.print(Ansi.AUTO.string("  " + label + "? "));
    return ConsoleHelper.readLine();
  }

  private void runStep(InitPlan.Step step, Steps steps, String fde, String name, String mail) {
    switch (step) {
      case PROVISION -> steps.run(HostInitCommand.initStep(), "--yes");
      case INSTALL_API_SYSTEM -> steps.run(new HostServiceInstallCommand());
      case SSH_IDENTITY -> steps.run(new HostSshIdentityCommand());
      case SYNC_AS_MAIN -> steps.run(new HostSyncCommand(), withFde("--as-main", fde, name, mail));
      case JOIN_MAIN -> steps.run(new JoinCommand(), withFde(main, fde, name, mail));
    }
  }

  private void requireRoot(boolean root) {
    if (!root) {
      throw RootRequired.refusal(spec);
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
              + ", without sudo. To set the box up as root instead, first run '"
              + OperatorCommand.UNPRIVILEGED.sail("host service uninstall")
              + "' as "
              + sudoUser
              + ".");
    }
  }

  private static String[] withFde(String first, String fde, String name, String mail) {
    var args = new ArrayList<String>();
    args.add(first);
    if (Strings.isNotBlank(fde)) {
      args.add("--handle");
      args.add(fde);
    }
    if (Strings.isNotBlank(name)) {
      args.add("--name");
      args.add(name);
    }
    if (Strings.isNotBlank(mail)) {
      args.add("--email");
      args.add(mail);
    }
    return args.toArray(String[]::new);
  }

  private static void printMainNextSteps(OperatorCommand operator) {
    var out = System.out;
    out.println();
    out.println(
        Ansi.AUTO.string("  @|bold,green ✓|@ This box is the org's @|bold main|@ and ready."));
    out.println(
        "  Add each engineer once they send you the line their '"
            + OperatorCommand.SUDO.sail("init --main")
            + "' printed, e.g.:");
    out.println(
        Ansi.AUTO.string(
            "    @|cyan "
                + operator.sail("fde add mady --role member --key '…' --main-host-key SHA256:…")
                + "|@"));
  }

  /**
   * Runs a step as {@code sail} runs a command, its failure reported by {@link Main#report} once
   * and without a trace, then stops the setup.
   */
  static void runCommand(Object command, String... args) {
    var line = new CommandLine(command);
    line.setExecutionExceptionHandler((failure, failed, parsed) -> Main.report(failure, failed));
    if (line.execute(args) != 0) {
      throw new IllegalStateException("Setup stopped — the step above failed.");
    }
  }
}
