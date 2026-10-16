/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class InitCommandTest {

  @TempDir Path tempDir;

  private final List<String> ran = new ArrayList<>();
  private String failing;
  private String refusal;
  private boolean root = true;
  private String sudoUser = "dev";
  private Path hostConfig;

  @BeforeEach
  void freshBox() {
    hostConfig = tempDir.resolve("host.yaml");
  }

  private int init(String... args) {
    refusal = null;
    var command =
        new CommandLine(
            new InitCommand(
                () ->
                    new InitCommand.Box(
                        root, sudoUser, hostConfig, tempDir::resolve, this::runStep)));
    command.setExecutionExceptionHandler(
        (failure, line, parsed) -> {
          refusal = failure.getMessage();
          return 1;
        });
    return command.execute(args);
  }

  private void runStep(Object command, String... args) {
    var step = (command.getClass().getSimpleName() + " " + String.join(" ", args)).strip();
    ran.add(step);
    if (step.equals(failing)) {
      throw new IllegalStateException("Setup stopped — the step above failed.");
    }
    if (step.startsWith("HostInitCommand")) {
      writeHostConfig();
    }
  }

  private void writeHostConfig() {
    try {
      Files.writeString(hostConfig, "storage_backend: dir\n");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void installUserService(String user) throws Exception {
    var unit = tempDir.resolve(user).resolve(".sail/services/sail-api.service");
    var link = tempDir.resolve(user).resolve(".config/systemd/user/sail-api.service");
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
    assertEquals(0, init("--as-main"));

    assertEquals(
        List.of(
            "HostInitCommand --yes",
            "HostServiceInstallCommand",
            "HostSshIdentityCommand",
            "HostSyncCommand --as-main"),
        ran);
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
  void withoutRootInitRefusesBeforeAnyStepEvenWithSudoUserSet() {
    root = false;

    assertEquals(1, init("--as-main"));

    assertEquals("Root privileges required. Re-run with: sudo sail init --as-main", refusal);
    assertEquals(List.of(), ran);
  }

  @Test
  void aNodeWithoutRootIsToldToJoinWithSudo() {
    root = false;

    assertEquals(1, init("--main", "maindevbox"));

    assertEquals(
        "Root privileges required. Re-run with: sudo sail init --main maindevbox", refusal);
  }

  @Test
  void aSudoUserWhoAlreadyRunsAUserServiceIsRefusedBeforeAnythingIsWritten() throws Exception {
    installUserService("dev");

    assertEquals(1, init("--as-main"));

    assertEquals(
        "dev already runs sail-api as a user service ("
            + tempDir.resolve("dev/.sail/services/sail-api.service")
            + "), so this box is set up for dev: run sail as dev, without sudo. To set the box up"
            + " as root instead, first run 'sail host service uninstall' as dev.",
        refusal);
    assertEquals(List.of(), ran);
    assertEquals(false, Files.exists(hostConfig));
  }

  @Test
  void anotherAccountsUserServiceIsNotTheSudoUsers() throws Exception {
    installUserService("mady");

    assertEquals(0, init("--as-main"));
  }

  @Test
  void rootWithoutSudoNeverLooksForAUserService() throws Exception {
    sudoUser = "root";
    installUserService("root");

    assertEquals(0, init("--as-main"));
    sudoUser = null;
    assertEquals(0, init("--as-main"));
  }

  @Test
  void reRunningOnAConvergedMainRunsOnlyItsIdempotentSteps() {
    assertEquals(0, init("--as-main"));
    ran.clear();

    assertEquals(0, init("--as-main"));

    assertEquals(
        List.of("HostServiceInstallCommand", "HostSshIdentityCommand", "HostSyncCommand --as-main"),
        ran);
  }

  @Test
  void reRunningAfterAFailedJoinResumesAndFinishes() {
    failing = "JoinCommand maindevbox";

    assertEquals(1, init("--main", "maindevbox"));
    assertEquals("Setup stopped — the step above failed.", refusal);
    failing = null;
    ran.clear();

    assertEquals(0, init("--main", "maindevbox"));

    assertEquals(List.of("HostServiceInstallCommand", "JoinCommand maindevbox"), ran);
  }
}
