/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Main;
import ai.singlr.sail.engine.LocalIdentity;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectDefinitions;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

class InitCommandTest {

  @TempDir Path tempDir;

  private final List<String> ran = new ArrayList<>();
  private String failing;
  private String refusal;
  private boolean root = true;
  private String sudoUser = "dev";
  private OperatorCommand operator = OperatorCommand.PLAIN;
  private Path hostConfig;

  @BeforeEach
  void freshBox() {
    hostConfig = tempDir.resolve("host.yaml");
    gitConfig.put("user.name", "Root Person");
    gitConfig.put("user.email", "root@box");
  }

  private final Map<String, String> gitConfig = new HashMap<>();
  private final Map<String, String> answers = new HashMap<>();
  private final LocalIdentity identity =
      new LocalIdentity(new GitConfig(), Path.of("no-workstation-key.pub"), OperatorCommand.PLAIN);

  private int init(String... args) {
    refusal = null;
    var command =
        new CommandLine(
            new InitCommand(
                () ->
                    new InitCommand.Box(
                        root,
                        sudoUser,
                        hostConfig,
                        this::homeOf,
                        identity,
                        answers::get,
                        operator,
                        this::runStep)));
    command.setExecutionExceptionHandler(
        (failure, line, parsed) -> {
          refusal = failure.getMessage();
          return 1;
        });
    return command.execute(args);
  }

  private Path homeOf(String user) {
    var passwd =
        new ScriptedShellExecutor()
            .onOk(
                "getent passwd -- " + user,
                user + ":x:1001:1001::" + tempDir.resolve("srv").resolve(user) + ":/bin/bash\n");
    return SailPaths.accountHome(passwd, user);
  }

  private boolean runStep(Object command, String... args) {
    if (command instanceof HostInitCommand host) {
      assertEquals(null, host.failureHint(), "host init inside init offers no --dry-run");
    }
    var step = (command.getClass().getSimpleName() + " " + String.join(" ", args)).strip();
    ran.add(step);
    if (step.equals(failing)) {
      return false;
    }
    if (step.startsWith("HostInitCommand")) {
      writeHostConfig("storage_backend: dir\n");
    }
    if (command instanceof HostServiceInstallCommand) {
      writeFile(systemUnit(), "[Unit]\n");
    }
    if (command instanceof HostSyncCommand) {
      writeHostConfig("storage_backend: dir\nsync:\n  role: main\n  handle: " + args[2] + "\n");
    }
    return true;
  }

  private void writeHostConfig(String yaml) {
    writeFile(hostConfig, yaml);
  }

  private void writeFile(Path file, String text) {
    try {
      Files.writeString(file, text);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Path systemUnit() {
    return tempDir.resolve("sail-api.service");
  }

  private void installUserService(String user) throws Exception {
    var unit = tempDir.resolve("srv").resolve(user).resolve(".sail/services/sail-api.service");
    var link =
        tempDir.resolve("srv").resolve(user).resolve(".config/systemd/user/sail-api.service");
    Files.createDirectories(unit.getParent());
    Files.createDirectories(link.getParent());
    Files.writeString(unit, "[Unit]\n");
    Files.createSymbolicLink(link, unit);
  }

  @Test
  void rejectsBothAsMainAndMain() {
    var code = new CommandLine(new InitCommand()).execute("--as-main", "--main", "sail@host");
    assertNotEquals(0, code, "asking to be both main and a node is invalid");
  }

  @Test
  void rejectsNeitherAsMainNorMain() {
    var code = new CommandLine(new InitCommand()).execute();
    assertNotEquals(0, code, "the box must be told whether it is main or a node");
  }

  @Test
  void aSudoUsersMainIsTheSystemServiceAndNoStepRunsAsAnotherAccount() {
    assertEquals(0, init("--as-main", "--handle", "ada"));

    assertEquals(
        List.of(
            "HostInitCommand --yes",
            "HostServiceInstallCommand",
            "HostSshIdentityCommand",
            "HostSyncCommand --as-main --handle ada --name Root Person --email root@box"),
        ran);
  }

  @ParameterizedTest
  @CsvSource({"false, sail", "true, sudo sail"})
  void mainsNextStepsTellAnotherBoxTheReadmesFormAndThisBoxItsCallersOwn(
      boolean sudo, String here) {
    operator = sudo ? OperatorCommand.SUDO : OperatorCommand.PLAIN;
    var printed = new ByteArrayOutputStream();
    var originalOut = System.out;
    System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
    try {
      assertEquals(0, init("--as-main", "--handle", "ada"));
    } finally {
      System.setOut(originalOut);
    }

    var nl = System.lineSeparator();
    assertTrue(
        printed
            .toString(StandardCharsets.UTF_8)
            .endsWith(
                nl
                    + "  ✓ This box is the org's main and ready."
                    + nl
                    + "  Add each engineer once they send you the line their 'sudo sail init"
                    + " --main' printed, e.g.:"
                    + nl
                    + "    "
                    + here
                    + " fde add mady --role member --key '…' --main-host-key SHA256:…"
                    + nl),
        printed.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aFreshSudoMainNamesTheCallersCommandWithSudo() {
    operator = OperatorCommand.of(root, sudoUser);
    var printed = new ByteArrayOutputStream();
    var originalOut = System.out;
    System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
    try {
      assertEquals(0, init("--as-main", "--handle", "ada"));
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(
        printed
            .toString(StandardCharsets.UTF_8)
            .endsWith(
                "    sudo sail fde add mady --role member --key '…' --main-host-key SHA256:…"
                    + System.lineSeparator()),
        printed.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aSudoUsersNodeInstallsTheSystemServiceThenJoins() {
    assertEquals(
        0, init("--main", "maindevbox", "--handle", "dev", "--name", "Dev", "--email", "d@x.dev"));

    assertEquals(
        List.of(
            "HostInitCommand --yes",
            "HostServiceInstallCommand",
            "JoinCommand maindevbox --handle dev --name Dev --email d@x.dev"),
        ran);
  }

  @Test
  void aMainsOperatorIsAddedAsItsFdeWithTheNameAndEmailItWasGiven() {
    assertEquals(
        0, init("--as-main", "--handle", "ada", "--name", "Ada L", "--email", "ada@example.com"));

    assertEquals(
        "HostSyncCommand --as-main --handle ada --name Ada L --email ada@example.com",
        ran.getLast());
  }

  @Test
  void aMainsOperatorIsAskedForWhenNotGiven() {
    answers.put("Your FDE handle", " ada ");

    assertEquals(0, init("--as-main"));

    assertEquals(
        "HostSyncCommand --as-main --handle ada --name Root Person --email root@box",
        ran.getLast());
  }

  @Test
  void aMainsOperatorIsAddedWithTheNameAndEmailTheBoxCommitsAs() {
    gitConfig.put("user.name", "Ada L");
    gitConfig.put("user.email", "ada@example.com");

    assertEquals(0, init("--as-main", "--handle", "ada"));

    assertEquals(
        "HostSyncCommand --as-main --handle ada --name Ada L --email ada@example.com",
        ran.getLast());
  }

  @Test
  void aNodesJoinAsksForItsOwnRosterValuesSoNoneIsPassedOrRefusedHere() {
    gitConfig.put("user.email", "dev");

    assertEquals(0, init("--main", "m", "--handle", "ada"));

    assertEquals("JoinCommand m --handle ada", ran.getLast());
  }

  @Test
  void anInvalidRosterEmailIsRefusedBeforeAnyStep() {
    assertEquals(1, init("--as-main", "--handle", "ada", "--email", "ada"));

    assertEquals(
        "Invalid email: 'ada'. Must be one address, like ada@example.com. Re-run with --email"
            + " \"...\".",
        refusal);
    assertEquals(List.of(), ran);
  }

  @Test
  void aGitNameMainCannotTakeIsRefusedBeforeAnyStepNamingTheFlag() {
    gitConfig.put("user.name", "Ada\tL");

    assertEquals(1, init("--as-main", "--handle", "ada"));

    assertEquals(
        "Invalid name: must be 1 to 128 characters on one line, with no control characters."
            + " Re-run with --name \"...\".",
        refusal);
    assertEquals(List.of(), ran);
  }

  @Test
  void aMainWithNoOperatorIsRefusedBeforeAnyStep() {
    assertEquals(1, init("--as-main"));

    assertEquals(
        "Main's operator is an FDE, the one dispatch picks this box's specs for. Re-run with"
            + " --handle <handle>.",
        refusal);
    assertEquals(List.of(), ran);
  }

  @Test
  void aMainsOperatorWithAnInvalidHandleIsRefusedBeforeAnyStep() {
    assertEquals(1, init("--as-main", "--handle", "ada lovelace"));

    assertEquals(
        "Invalid FDE handle: 'ada lovelace'. Must match [A-Za-z0-9][A-Za-z0-9._-]*, max 63"
            + " characters.",
        refusal);
    assertEquals(List.of(), ran);
  }

  @Test
  void aMainAlreadyActingAsAnotherFdeIsRefusedNamingBothBeforeAnyStep() {
    writeHostConfig("storage_backend: dir\nsync:\n  role: main\n  handle: grace\n");

    assertEquals(1, init("--as-main", "--handle", "ada"));

    assertEquals(
        "This box already acts as 'grace', not 'ada'. Re-run with --handle grace, or change its"
            + " handle first: sail host config set sync-handle ada",
        refusal);
    assertEquals(List.of(), ran);
  }

  @Test
  void aNodeReJoiningUnderAnotherHandleIsRefusedNamingBothBeforeAnyStep() {
    writeHostConfig("storage_backend: dir\nsync:\n  role: node\n  handle: grace\n");

    assertEquals(1, init("--main", "m", "--handle", "ada"));

    assertEquals(
        "This box already acts as 'grace', not 'ada'. Re-run with --handle grace, or change its"
            + " handle first: sail host config set sync-handle ada",
        refusal);
    assertEquals(List.of(), ran);
  }

  @Test
  void aNodeReJoiningWithoutAHandleJoinsAsTheOneItActsAsWithoutAsking() {
    writeHostConfig("storage_backend: dir\nsync:\n  role: node\n  handle: rnode\n");
    answers.put("Your FDE handle", "dev");

    assertEquals(0, init("--main", "10.128.0.22"));

    assertEquals("JoinCommand 10.128.0.22 --handle rnode", ran.getLast());
  }

  @Test
  void aNodesFirstJoinLeavesItsHandleToTheJoin() {
    answers.put("Your FDE handle", "dev");

    assertEquals(0, init("--main", "m"));

    assertEquals("JoinCommand m", ran.getLast());
  }

  @Test
  void withoutRootInitRefusesBeforeAnyStepEvenWithSudoUserSet() {
    root = false;

    assertEquals(1, init("--as-main"));

    assertEquals("Root privileges required. Run with: sudo sail init --as-main", refusal);
    assertEquals(List.of(), ran);
  }

  @Test
  void aNodeWithoutRootIsToldToJoinWithSudo() {
    root = false;

    assertEquals(1, init("--main", "maindevbox"));

    assertEquals("Root privileges required. Run with: sudo sail init --main maindevbox", refusal);
  }

  @Test
  void aSudoUserWhoAlreadyRunsAUserServiceIsRefusedBeforeAnythingIsWritten() throws Exception {
    installUserService("dev");

    assertEquals(1, init("--as-main"));

    assertEquals(
        "dev already runs sail-api as a user service ("
            + tempDir.resolve("srv/dev/.sail/services/sail-api.service")
            + "), so this box is set up for dev: run sail as dev, without sudo. To set the box up"
            + " as root instead, first run 'sail host service uninstall' as dev.",
        refusal);
    assertEquals(List.of(), ran);
    assertEquals(false, Files.exists(hostConfig));
  }

  @Test
  void anotherAccountsUserServiceIsNotTheSudoUsers() throws Exception {
    installUserService("mady");

    assertEquals(0, init("--as-main", "--handle", "ada"));
  }

  @Test
  void rootWithoutSudoNeverLooksForAUserService() throws Exception {
    sudoUser = "root";
    installUserService("root");

    assertEquals(0, init("--as-main", "--handle", "ada"));
    sudoUser = null;
    assertEquals(0, init("--as-main"));
  }

  @Test
  void reRunningOnAConvergedMainRunsOnlyItsIdempotentStepsAsTheOperatorItHas() {
    assertEquals(0, init("--as-main", "--handle", "ada"));
    ran.clear();

    assertEquals(0, init("--as-main"));

    assertEquals(
        List.of(
            "HostServiceInstallCommand",
            "HostSshIdentityCommand",
            "HostSyncCommand --as-main --handle ada --name Root Person --email root@box"),
        ran);
  }

  @ParameterizedTest
  @CsvSource({"PLAIN, sail", "SUDO, sudo sail"})
  void aFailedProvisioningStopsSetupNamingTheCommandAsTyped(String reader, String sail) {
    operator = reader.equals("PLAIN") ? OperatorCommand.PLAIN : OperatorCommand.SUDO;
    failing = "HostInitCommand --yes";

    assertEquals(1, init("--main", "m", "--handle", "h"));

    assertEquals(
        "Setup stopped at the step marked ✗ above. Fix the cause above and re-run: "
            + sail
            + " init --main m --handle h",
        refusal);
    assertEquals(List.of("HostInitCommand --yes"), ran);
  }

  @Test
  void aStoppedSetupNamesEveryWordTypedQuoted() {
    failing = "HostServiceInstallCommand";

    assertEquals(1, init("--as-main", "--handle", "ada", "--name", "Ada L", "--email", "a@b"));

    assertEquals(
        "Setup stopped at the step marked ✗ above. Fix the cause above and re-run: sail init --as-main --handle ada"
            + " --name 'Ada L' --email a@b",
        refusal);
  }

  @Test
  void reRunningAfterAFailedJoinResumesAndFinishes() {
    failing = "JoinCommand maindevbox";

    assertEquals(1, init("--main", "maindevbox"));
    assertEquals(
        "Setup stopped at the step marked ✗ above. Fix the cause above and re-run: sail init --main maindevbox",
        refusal);
    failing = null;
    ran.clear();

    assertEquals(0, init("--main", "maindevbox"));

    assertEquals(List.of("HostServiceInstallCommand", "JoinCommand maindevbox"), ran);
  }

  /** {@code git config --global} of one account: {@code --get} reads it, a key and value set it. */
  private final class GitConfig implements ShellExec {
    @Override
    public Result exec(List<String> command) {
      assertEquals(List.of("git", "config", "--global"), command.subList(0, 3));
      if (command.get(3).equals("--get")) {
        var value = gitConfig.get(command.get(4));
        return value == null ? new Result(1, "", "") : new Result(0, value + "\n", "");
      }
      gitConfig.put(command.get(3), command.get(4));
      return new Result(0, "", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  private static final String BY_NAME =
      """
      name: gamma
      git:
        name: ${GIT_NAME}
        email: ${GIT_EMAIL}
      ssh:
        user: dev
      """;

  @Test
  void aNodesNameAndEmailBecomeRootsGitIdentitySoAByNameApplyResolvesIt() {
    gitConfig.clear();
    assertEquals(0, init("--main", "m", "--name", "A B", "--email", "a@b"));

    assertEquals(Map.of("user.name", "A B", "user.email", "a@b"), gitConfig);
    assertEquals(
        List.of(
            "HostInitCommand --yes",
            "HostServiceInstallCommand",
            "JoinCommand m --name A B --email a@b"),
        ran);
    var git =
        ProjectDefinitions.resolveForProvisioning(
                BY_NAME, identity::valueFor, OperatorCommand.PLAIN)
            .git();
    assertEquals("A B", git.name());
    assertEquals("a@b", git.email());
  }

  @Test
  void aBoxThatAlreadyHasAGitIdentityKeepsIt() {
    gitConfig.put("user.name", "Root Person");
    gitConfig.put("user.email", "root@box");

    assertEquals(0, init("--main", "m", "--name", "A B", "--email", "a@b"));

    assertEquals(Map.of("user.name", "Root Person", "user.email", "root@box"), gitConfig);
    assertEquals("JoinCommand m --name A B --email a@b", ran.getLast());
  }

  @Test
  void aMainTakesItsGitIdentityFromInitSoItsSecondApplyByNameConverges() {
    gitConfig.clear();
    assertEquals(0, init("--as-main", "--handle", "ada", "--name", "A B", "--email", "a@b"));

    assertEquals(Map.of("user.name", "A B", "user.email", "a@b"), gitConfig);
    var git =
        ProjectDefinitions.resolveForProvisioning(
                BY_NAME, identity::valueFor, OperatorCommand.PLAIN)
            .git();
    assertEquals("A B", git.name());
    assertEquals("a@b", git.email());
  }

  @Test
  void aMainsPaddedRosterFlagsAreStrippedAndABlankOneFallsBackToTheGitIdentity() {
    assertEquals(
        0,
        init("--as-main", "--handle", "ada", "--name", " Ada L ", "--email", " ada@example.com "));
    assertEquals(
        "HostSyncCommand --as-main --handle ada --name Ada L --email ada@example.com",
        ran.getLast());

    assertEquals(0, init("--as-main", "--handle", "ada", "--name", ""));
    assertEquals(
        "HostSyncCommand --as-main --handle ada --name Root Person --email root@box",
        ran.getLast());
  }

  @Test
  void aNodeWithNoGitIdentityPassesWhatItAskedForToItsJoin() {
    gitConfig.clear();
    answers.put("Your full name", " A B ");
    answers.put("Your email", "a@b");

    assertEquals(0, init("--main", "m", "--handle", "ada"));

    assertEquals("JoinCommand m --handle ada --name A B --email a@b", ran.getLast());
  }

  @Test
  void aBoxWithNoGitIdentityAsksForIt() {
    gitConfig.clear();
    answers.put("Your full name", " A B ");
    answers.put("Your email", "a@b");

    assertEquals(0, init("--as-main", "--handle", "ada"));

    assertEquals(Map.of("user.name", "A B", "user.email", "a@b"), gitConfig);
  }

  @Test
  void theIdentityIsAskedBeforeTheFirstStepAndGivenOnlyOnceSetupFinished() {
    gitConfig.clear();
    answers.put("Your full name", "A B");
    failing = "HostServiceInstallCommand";

    assertEquals(1, init("--as-main", "--handle", "ada", "--email", "a@b"));

    assertEquals(Map.of(), gitConfig);
    failing = null;
    answers.clear();
    assertEquals(0, init("--as-main", "--handle", "ada", "--name", "A B", "--email", "a@b"));
    assertEquals(Map.of("user.name", "A B", "user.email", "a@b"), gitConfig);
  }

  @Test
  void aBoxLeftWithNoGitIdentityIsRefusedBeforeAnyStep() {
    gitConfig.clear();

    assertEquals(1, init("--main", "m"));

    assertEquals(
        "This box has no git user.name set, so a project here can't take on your identity."
            + " Re-run with --name \"...\".",
        refusal);
    assertEquals(List.of(), ran);
    assertEquals(Map.of(), gitConfig);
  }

  @Test
  void aBlankAnswerIsNoIdentity() {
    gitConfig.remove("user.email");
    answers.put("Your email", "  ");

    assertEquals(1, init("--as-main", "--handle", "ada"));

    assertEquals(
        "This box has no git user.email set, so a project here can't take on your identity."
            + " Re-run with --email \"...\".",
        refusal);
    assertEquals(List.of(), ran);
  }

  @Command(name = "refusing")
  static final class Refusing implements Runnable {
    @Spec CommandSpec spec;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            throw new IllegalStateException("main's SSH host key changed.");
          });
    }
  }

  @Test
  void aStepsRefusalIsPrintedOnceWithNoTraceAndSetupStoppedOnce() {
    var originalErr = System.err;
    var err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      var init =
          new CommandLine(
              new InitCommand(
                  () ->
                      new InitCommand.Box(
                          root,
                          sudoUser,
                          hostConfig,
                          this::homeOf,
                          identity,
                          answers::get,
                          OperatorCommand.PLAIN,
                          (command, args) -> InitCommand.runCommand(new Refusing()))));
      init.setExecutionExceptionHandler((failure, line, parsed) -> Main.report(failure, line));

      assertEquals(1, init.execute("--main", "m"));
    } finally {
      System.setErr(originalErr);
    }

    assertEquals(
        "  ✗ main's SSH host key changed.\n  ✗ Setup stopped at the step marked ✗ above. Fix the cause above and"
            + " re-run: sail init --main m\n",
        err.toString(StandardCharsets.UTF_8));
  }
}
