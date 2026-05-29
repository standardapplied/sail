/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import java.nio.file.Files;
import java.util.List;
import picocli.CommandLine.Help.Ansi;

/**
 * Starts the background guardrail watcher ({@code sail agent watch}) for a project when its config
 * declares guardrails. Shared by the CLI dispatch/run/launch commands, which all spawn the watcher
 * the same way; failures are reported but never fatal to the launch.
 */
public final class GuardrailWatcher {

  private GuardrailWatcher() {}

  /** No-op when the project declares no guardrails; otherwise spawns a detached watcher process. */
  public static void launchIfConfigured(String project, String file, SailYaml config) {
    if (config == null || config.agent() == null || config.agent().guardrails() == null) {
      return;
    }
    try {
      var sailBinary = SailPaths.binaryPath().toString();
      var sailYamlPath = SailPaths.resolveSailYaml(project, file);
      var cmd =
          List.of(
              "nohup",
              sailBinary,
              "agent",
              "watch",
              project,
              "-f",
              sailYamlPath.toAbsolutePath().toString());

      var watchLog = SailPaths.projectDir(project).resolve("watch.log");
      Files.createDirectories(watchLog.getParent());

      var pb = new ProcessBuilder(cmd);
      pb.redirectOutput(ProcessBuilder.Redirect.to(watchLog.toFile()));
      pb.redirectErrorStream(true);
      pb.start();

      System.out.println(
          Ansi.AUTO.string("  @|green ✓|@ Guardrail watcher started (log: " + watchLog + ")"));
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine(
              "Failed to start guardrail watcher: "
                  + e.getMessage()
                  + ". Run manually: sail agent watch "
                  + project,
              Ansi.AUTO));
    }
  }
}
