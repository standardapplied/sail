/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.ShellExec;
import java.util.List;

/**
 * Runs a review agent inside the project container and returns its raw output — the production
 * {@link ReviewAgentRunner} the control plane wires into the pipeline.
 *
 * <p>The reviewer is launched <strong>clean</strong>: no {@code SAIL_SPEC_ID} (so {@code
 * sail-event.sh} self-gates and emits nothing) and no {@code --settings} hooks for Claude Code, so
 * the reviewer's own completion never re-enters the pipeline — otherwise reviews would recurse
 * forever. The prompt is staged to a file to avoid shell injection, and the agent runs in the
 * workspace where it can read the diff for itself (the prompt names the branch and repo).
 */
final class ContainerReviewAgentRunner implements ReviewAgentRunner {

  private static final String PROMPT_PATH = ContainerExec.DEV_HOME + "/.sail/review-prompt.txt";
  private static final String WORKSPACE = ContainerExec.DEV_HOME + "/workspace";

  private final ShellExec shell;

  ContainerReviewAgentRunner(ShellExec shell) {
    this.shell = shell;
  }

  @Override
  public String run(String project, String agent, String prompt) throws Exception {
    ContainerFilePush.push(shell, project, PROMPT_PATH, prompt, List.of("--mode", "0600"));
    var command = AgentCli.fromYamlName(agent).headlessCommand(PROMPT_PATH, true, null, null, null);
    var result =
        shell.exec(
            ContainerExec.asDevUser(
                project, List.of("bash", "-lc", "cd " + WORKSPACE + " && " + command)));
    if (!result.ok()) {
      throw new IllegalStateException(
          "Review agent '" + agent + "' failed in '" + project + "': " + result.stderr());
    }
    return result.stdout();
  }
}
