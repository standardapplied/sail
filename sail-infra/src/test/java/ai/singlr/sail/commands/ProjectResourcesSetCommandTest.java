/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExec;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ProjectResourcesSetCommandTest {

  private static final String PROJECT_NAME = "resource-test-project";

  private static final String RUNNING_INFO_JSON =
      """
      [
        {
          "name": "resource-test-project",
          "status": "Running",
          "config": {
            "limits.cpu": "2",
            "limits.memory": "8GB"
          },
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "netmask": "24", "scope": "global"}
                ]
              }
            }
          }
        }
      ]
      """;

  private static final String STOPPED_INFO_JSON =
      """
      [
        {
          "name": "resource-test-project",
          "status": "Stopped",
          "config": {
            "limits.cpu": "2",
            "limits.memory": "8GB"
          },
          "state": {}
        }
      ]
      """;

  @TempDir Path tempDir;

  @Test
  void helpTextIncludesResourceOptions() {
    var cmd = new CommandLine(new ProjectResourcesSetCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("--cpu"));
    assertTrue(usage.contains("--memory"));
    assertTrue(usage.contains("--disk"));
    assertTrue(usage.contains("--dry-run"));
    assertTrue(usage.contains("--json"));
  }

  @Test
  void setUpdatesDescriptorAndRestartsRunningProject() throws Exception {
    var shell =
        new TestShell()
            .onOk("incus list ^resource-test-project$", RUNNING_INFO_JSON)
            .onOk("incus config set")
            .onOk("incus restart");
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    var yamlPath = writeProjectYaml();

    var exitCode = execute(shell, stdout, stderr, yamlPath, "--memory", "16GB");

    assertEquals(0, exitCode);
    var updated = loadProjectYaml(yamlPath);
    assertEquals("16GB", updated.resources().memory());
    assertTrue(shell.invocations().stream().anyMatch(cmd -> cmd.contains("limits.memory=16GB")));
    assertTrue(
        shell.invocations().stream().anyMatch(cmd -> cmd.equals("incus restart " + PROJECT_NAME)));
  }

  @Test
  void setUpdatesStoppedProjectWithoutRestart() throws Exception {
    var shell =
        new TestShell()
            .onOk("incus list ^resource-test-project$", STOPPED_INFO_JSON)
            .onOk("incus config set");
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    var yamlPath = writeProjectYaml();

    var exitCode = execute(shell, stdout, stderr, yamlPath, "--cpu", "4");

    assertEquals(0, exitCode);
    var updated = loadProjectYaml(yamlPath);
    assertEquals(4, updated.resources().cpu());
    assertTrue(shell.invocations().stream().anyMatch(cmd -> cmd.contains("limits.cpu=4")));
    assertFalse(shell.invocations().stream().anyMatch(cmd -> cmd.contains("incus restart")));
  }

  @Test
  void setRequiresAtLeastOneResourceFlag() throws Exception {
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    var yamlPath = writeProjectYaml();

    var command =
        new ProjectResourcesSetCommand(
            new TestShell().onOk("incus list ^resource-test-project$", STOPPED_INFO_JSON),
            () -> true,
            new PrintStream(stdout),
            new PrintStream(stderr));
    var cli = new CommandLine(command);
    cli.setExecutionExceptionHandler((ex, cl, parseResult) -> 1);
    var exitCode = cli.execute(PROJECT_NAME, "--file", yamlPath.toString());

    assertEquals(1, exitCode);
  }

  private int execute(
      TestShell shell,
      ByteArrayOutputStream stdout,
      ByteArrayOutputStream stderr,
      Path yamlPath,
      String... args) {
    var command =
        new ProjectResourcesSetCommand(
            shell, () -> true, new PrintStream(stdout), new PrintStream(stderr));
    var cli = new CommandLine(command);
    var fullArgs = new String[args.length + 3];
    fullArgs[0] = PROJECT_NAME;
    fullArgs[1] = "--file";
    fullArgs[2] = yamlPath.toString();
    System.arraycopy(args, 0, fullArgs, 3, args.length);
    return cli.execute(fullArgs);
  }

  private Path writeProjectYaml() throws Exception {
    var yamlPath = tempDir.resolve(PROJECT_NAME).resolve("sail.yaml");
    Files.createDirectories(yamlPath.getParent());
    Files.writeString(
        yamlPath,
        """
        name: resource-test-project
        resources:
          cpu: 2
          memory: 8GB
          disk: 50GB
        ssh:
          user: dev
        """);
    return yamlPath;
  }

  private SailYaml loadProjectYaml(Path yamlPath) throws Exception {
    return SailYaml.fromMap(YamlUtil.parseFile(yamlPath));
  }

  private static final class TestShell implements ShellExec {

    private final Map<String, Result> scripts = new LinkedHashMap<>();
    private final List<String> invocations = new ArrayList<>();

    TestShell onOk(String pattern, String stdout) {
      scripts.put(pattern, new Result(0, stdout, ""));
      return this;
    }

    TestShell onOk(String pattern) {
      scripts.put(pattern, new Result(0, "", ""));
      return this;
    }

    List<String> invocations() {
      return invocations;
    }

    @Override
    public Result exec(List<String> command) {
      return exec(command, null, Duration.ZERO);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      var joined = String.join(" ", command);
      invocations.add(joined);
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return new Result(1, "", "command not scripted");
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
