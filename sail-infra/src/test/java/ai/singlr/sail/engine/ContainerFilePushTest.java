/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerFilePushTest {

  private static Path stagedTempFile(String invocation) {
    return Arrays.stream(invocation.split(" "))
        .filter(token -> token.contains("sail-push-"))
        .map(Path::of)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void buildsIncusPushWithFlagsAndDestination() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus file push");
    ContainerFilePush.push(
        shell, "acme", "/etc/sail/project.yaml", "hi", List.of("--mode", "0600"));

    var push =
        shell.invocations().stream()
            .filter(c -> c.startsWith("incus file push"))
            .findFirst()
            .orElseThrow();
    assertTrue(push.contains("--mode 0600"), push);
    assertTrue(push.endsWith("acme/etc/sail/project.yaml"), push);
  }

  @Test
  void throwsWhenPushFailsIncludingPathAndStderr() {
    var shell = new ScriptedShellExecutor().onFail("incus file push", "boom");
    var ex =
        assertThrows(
            IOException.class,
            () -> ContainerFilePush.push(shell, "acme", "/tmp/x", "data", List.of()));
    assertTrue(ex.getMessage().contains("/tmp/x"));
    assertTrue(ex.getMessage().contains("boom"));
  }

  @Test
  void deletesStagedTempFileOnSuccess() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus file push");
    ContainerFilePush.push(shell, "acme", "/tmp/x", "data", List.of());

    var staged = stagedTempFile(shell.invocations().getFirst());
    assertFalse(Files.exists(staged), "Temp file should be deleted after a successful push");
  }

  @Test
  void deletesStagedTempFileOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("incus file push", "boom");
    assertThrows(
        IOException.class,
        () -> ContainerFilePush.push(shell, "acme", "/tmp/x", "data", List.of()));

    var staged = stagedTempFile(shell.invocations().getFirst());
    assertFalse(Files.exists(staged), "Temp file should be deleted even when the push fails");
  }
}
