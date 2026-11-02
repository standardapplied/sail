/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.harness.Harnesses;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every operations-layer message that names a {@code sail} command for its operator to type,
 * rendered for the operator the layer is given: the caller's own when the CLI runs it in-process,
 * and {@link OperatorCommand#unknownReader} when sail-api answers a client it cannot see.
 */
class ApiOperatorHintsTest {

  private static final OperatorCommand SUDO = OperatorCommand.SUDO;
  private static final OperatorCommand PLAIN = OperatorCommand.PLAIN;

  private static Spec spec(String assignee) {
    return new Spec(
        "auth",
        "gamma",
        "Add auth",
        SpecStatus.PENDING,
        assignee,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null);
  }

  private static List<String> hints(OperatorCommand operator) {
    return List.of(
        operator.render(ProjectLoader.stopped("gamma").remedy()),
        operator.render(ProjectReader.absent("gamma").remedy()),
        operator.render(DispatchPolicy.runsOnOtherNode(spec(null), "ada").fix()),
        operator.render(DispatchPolicy.runsOnOtherNode(spec("bob"), "ada").fix()),
        operator.render(LaunchAdmission.HANDLE_HINT),
        operator.render(
            LaunchAdmission.notInstalled(Harnesses.of("claude-code"), "gamma").remedy()),
        operator.render((Remedied) SailOperations.storeUnavailable("Run store")));
  }

  @Test
  void aSudoCallerOnARootBoxIsToldEveryCommandWithSudo() {
    assertEquals(
        List.of(
            "Start it with sudo sail project start gamma.",
            "Create it with 'sudo sail project create gamma', or sync it from main with 'sudo sail"
                + " sync'.",
            "Claim it first: sudo sail spec update auth --assignee ada",
            "Reassign it to dispatch here: sudo sail spec update auth --assignee ada",
            "Set it with: sudo sail host config set sync-handle <handle>",
            "Add claude-code to the agent's install list with 'sudo sail project edit gamma --section"
                + " agent', then run 'sudo sail project apply gamma'.",
            "Run store not available. Start the server with 'sudo sail server start'."),
        hints(SUDO));
  }

  @Test
  void rootOrABoxsOwnAccountIsToldEveryCommandAsToday() {
    assertEquals(
        List.of(
            "Start it with sail project start gamma.",
            "Create it with 'sail project create gamma', or sync it from main with 'sail sync'.",
            "Claim it first: sail spec update auth --assignee ada",
            "Reassign it to dispatch here: sail spec update auth --assignee ada",
            "Set it with: sail host config set sync-handle <handle>",
            "Add claude-code to the agent's install list with 'sail project edit gamma --section"
                + " agent', then run 'sail project apply gamma'.",
            "Run store not available. Start the server with 'sail server start'."),
        hints(PLAIN));
  }
}
