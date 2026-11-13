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

  @Test
  void aKeyForAProjectReachesItsContainerAndItsWholeWorkspacesAndNoTemplate() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk(
                "incus list --format json",
                """
                [{"name": "acme", "status": "Running", "type": "container"},
                 {"name": "acme-w", "status": "Running", "type": "container",
                  "config": {"user.sail.project": "acme", "user.sail.workspace": "w",
                             "user.sail.ready": "1"}},
                 {"name": "acme-half", "status": "Running", "type": "container",
                  "config": {"user.sail.project": "acme", "user.sail.workspace": "half"}},
                 {"name": "acme--tmpl", "status": "Stopped", "type": "container",
                  "config": {"user.sail.template.of": "acme"}},
                 {"name": "bravo-w", "status": "Running", "type": "container",
                  "config": {"user.sail.project": "bravo", "user.sail.workspace": "w",
                             "user.sail.ready": "1"}}]
                """);

    new ContainerAuthorizedKeys(shell).add(LAPTOP, List.of("acme"));

    assertEquals(
        List.of("acme", "acme-w"),
        shell.arguments().stream()
            .filter(
                argv ->
                    argv.size() > 4
                        && argv.subList(0, 4).equals(List.of("incus", "config", "device", "get")))
            .map(argv -> argv.get(4))
            .distinct()
            .toList());
  }
}
