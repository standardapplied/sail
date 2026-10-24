/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HostSshIdentityCommandTest {

  @Test
  void theDatabaseMoveEndsWithBothServicesRestartedOntoTheSharedDataDir() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    HostSshIdentityCommand.execute(
        SshIdentityProvisioner.plan(Path.of("/root/.sail"), "/var/lib/sail"), shell);

    var commands = shell.arguments();
    var move =
        IntStream.range(0, commands.size())
            .filter(i -> String.join(" ", commands.get(i)).contains("mv \"/root/.sail/$f\""))
            .findFirst()
            .orElseThrow();
    var content = "[Service]\nEnvironment=SAIL_DATA_DIR=/var/lib/sail\nUMask=0007\n";
    assertEquals(
        List.of(
            List.of(content, SshIdentityProvisioner.DROP_IN),
            List.of(content, SshIdentityProvisioner.PTY_HOST_DROP_IN),
            List.of("systemctl", "daemon-reload"),
            List.of("systemctl", "start", "sail-api"),
            List.of("systemctl", "restart", "sail-pty-host.service")),
        commands.subList(commands.size() - 5, commands.size()).stream()
            .map(command -> command.getFirst().equals("bash") ? command.subList(4, 6) : command)
            .toList());
    assertTrue(move >= 0 && move < commands.size() - 5, commands.toString());
  }
}
