/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerAuthorizedKeysTest {

  private static final SshPublicKey LAPTOP =
      SshPublicKey.parse(TestSshKeys.ed25519("laptop", "ada@laptop"));

  private static final String BOX =
      """
      [{"name": "acme", "status": "Running", "type": "container"},
       {"name": "acme-w", "status": "Running", "type": "container",
        "config": {"user.sail.project": "acme", "user.sail.workspace": "w",
                   "user.sail.ready": "1", "user.sail.owner": "uday"}},
       {"name": "acme-b", "status": "Stopped", "type": "container",
        "config": {"user.sail.project": "acme", "user.sail.workspace": "b",
                   "user.sail.ready": "1", "user.sail.owner": "ana"}},
       {"name": "acme-half", "status": "Running", "type": "container",
        "config": {"user.sail.project": "acme", "user.sail.workspace": "half",
                   "user.sail.owner": "uday"}},
       {"name": "acme--tmpl", "status": "Stopped", "type": "container",
        "config": {"user.sail.template.of": "acme"}},
       {"name": "bravo-w", "status": "Running", "type": "container",
        "config": {"user.sail.project": "bravo", "user.sail.workspace": "w",
                   "user.sail.ready": "1", "user.sail.owner": "uday"}},
       {"name": "bravo-a", "status": "Running", "type": "container",
        "config": {"user.sail.project": "bravo", "user.sail.workspace": "a",
                   "user.sail.ready": "1", "user.sail.owner": "ana"}}]
      """;

  private static ScriptedShellExecutor box() {
    return new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
        .onOk("incus list --format json", BOX);
  }

  /** The instances whose sail-api-sock device was asked of, in order: those an addition reached. */
  private static List<String> reached(ScriptedShellExecutor shell) {
    return shell.arguments().stream()
        .filter(
            argv ->
                argv.size() > 4
                    && argv.subList(0, 4).equals(List.of("incus", "config", "device", "get")))
        .map(argv -> argv.get(4))
        .distinct()
        .toList();
  }

  /**
   * Scenario 9: the box's workstation key reaches the project's container and its person's whole
   * workspaces, never another person's workspace, a half-made one or a template.
   */
  @Test
  void theWorkstationKeyReachesTheContainerAndTheBoxPersonsWorkspacesAlone() throws Exception {
    var shell = box();

    new ContainerAuthorizedKeys(shell).add(LAPTOP, List.of("acme"), "uday");

    assertEquals(List.of("acme", "acme-w"), reached(shell));
  }

  /**
   * A key a person registers reaches each whole workspace of theirs, of any project, and no more.
   */
  @Test
  void aPersonsKeyReachesTheirWholeWorkspacesAloneAndNoContainer() throws Exception {
    var shell = box();

    new ContainerAuthorizedKeys(shell).addToWorkspacesOf(LAPTOP, "ana");

    assertEquals(List.of("acme-b", "bravo-a"), reached(shell));
  }
}
