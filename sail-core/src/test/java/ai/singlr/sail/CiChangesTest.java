/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code ci/changes}, the one decision of which lanes a change runs, over a real repository: a base
 * commit holding a file in each place a lane reads or does not, and a change on top of it.
 */
class CiChangesTest {

  private static final Path SCRIPT = Path.of("..", "ci", "changes").toAbsolutePath();
  private static final List<String> FILES =
      List.of(
          "sail-core/src/main/java/A.java",
          "sail-core/src/test/java/ATest.java",
          "sail-core/pom.xml",
          "pom.xml",
          "clients/SailApp/App.swift",
          "clients/SailKit/Kit.swift",
          "CHANGELOG.md",
          "ARCHITECTURE.md",
          "README.md",
          ".github/workflows/ci.yml",
          ".github/workflows/clients.yml",
          "ci/changes");
  private static final List<String> EVERY_LANE =
      List.of("clients=true", "ci=true", "terminal=true");

  @TempDir Path repo;
  @TempDir Path output;
  private String base;

  @BeforeEach
  void aBaseHoldingEveryKindOfPath() throws Exception {
    git("init", "-q");
    for (var file : FILES) {
      write(file, "base\n");
    }
    base = commit();
  }

  @ParameterizedTest(name = "{0} changed in a {1}: ci={2}, terminal={3}, clients={4}")
  @CsvSource({
    "clients/SailApp/App.swift, pull_request, false, false, true",
    "clients/SailApp/App.swift CHANGELOG.md, pull_request, false, false, true",
    "ARCHITECTURE.md CHANGELOG.md, pull_request, false, false, false",
    ".github/workflows/clients.yml, pull_request, false, false, true",
    "clients/SailKit/Kit.swift, pull_request, false, true, true",
    "clients/SailKit/Kit.swift clients/SailApp/App.swift, pull_request, false, true, true",
    "clients/SailKit/Tests/Goldens/http/a.json, pull_request, true, true, true",
    "sail-core/src/main/java/A.java, pull_request, true, true, true",
    "sail-core/pom.xml, pull_request, true, true, true",
    "pom.xml, pull_request, true, true, true",
    "sail-core/src/test/java/ATest.java, pull_request, true, true, false",
    ".github/workflows/ci.yml, pull_request, true, true, false",
    "README.md, pull_request, true, true, false",
    "clients/SailApp/App.swift README.md, pull_request, true, true, true",
    "ci/changes, pull_request, true, true, true",
    "clients/SailApp/App.swift, push, true, true, true",
    "sail-core/src/main/java/A.java, push, true, true, true",
    "sail-core/src/test/java/ATest.java, push, true, true, false",
    "sail-core/src/main/java/A.java, workflow_dispatch, true, true, true"
  })
  void aChangeRunsTheLanesThatReadWhatItTouched(
      String paths, String event, String ci, String terminal, String clients) throws Exception {
    for (var path : paths.split(" ")) {
      write(path, "changed\n");
    }
    commit();

    assertEquals(
        List.of("clients=" + clients, "ci=" + ci, "terminal=" + terminal), lanes(event, base));
  }

  @Test
  void aFileMovedFromAJavaModuleIntoTheAppRunsCi() throws Exception {
    git("mv", "sail-core/src/main/java/A.java", "clients/SailApp/A.java");
    commit();

    assertEquals(EVERY_LANE, lanes("pull_request", base));
  }

  @ParameterizedTest(name = "{0} deleted: ci={1}, terminal={2}, clients={3}")
  @CsvSource({
    "sail-core/src/main/java/A.java, true, true, true",
    "clients/SailKit/Kit.swift, false, true, true",
    "clients/SailApp/App.swift, false, false, true"
  })
  void aDeletedFileRunsTheLanesThatReadIt(String path, String ci, String terminal, String clients)
      throws Exception {
    git("rm", "-q", path);
    commit();

    assertEquals(
        List.of("clients=" + clients, "ci=" + ci, "terminal=" + terminal),
        lanes("pull_request", base));
  }

  @Test
  void anEmptyChangeRunsEveryLane() throws Exception {
    commit();

    assertEquals(EVERY_LANE, lanes("pull_request", base));
  }

  @Test
  void aBaseThatCannotBeDiffedRunsEveryLane() throws Exception {
    write("clients/SailApp/App.swift", "changed\n");
    commit();

    assertEquals(EVERY_LANE, lanes("pull_request", "0000000000000000000000000000000000000000"));
    assertEquals(EVERY_LANE, lanes("pull_request", ""));
  }

  private List<String> lanes(String event, String from) throws Exception {
    return run(List.of("bash", SCRIPT.toString(), event, from)).lines().toList();
  }

  private String commit() throws Exception {
    git("add", "-A");
    git("commit", "-q", "--allow-empty", "-m", "change");
    return git("rev-parse", "HEAD").strip();
  }

  private void write(String path, String content) throws IOException {
    var file = repo.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private String git(String... args) throws Exception {
    var command =
        new ArrayList<>(List.of("git", "-c", "user.name=CI", "-c", "user.email=ci@example.com"));
    command.addAll(Arrays.asList(args));
    return run(command);
  }

  private String run(List<String> command) throws Exception {
    var out = output.resolve("out");
    var builder =
        new ProcessBuilder(command)
            .directory(repo.toFile())
            .redirectOutput(out.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    var process = builder.start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError(String.join(" ", command) + " did not end within 30 s");
    }
    assertEquals(0, process.exitValue(), String.join(" ", command) + " failed");
    return Files.readString(out);
  }
}
