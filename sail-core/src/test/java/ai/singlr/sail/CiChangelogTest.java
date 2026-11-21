/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ci/changelog}, the release's fold of {@code changes/} into {@code CHANGELOG.md}, and
 * {@code ci/check changelog}, which keeps a pull request's entry out of {@code CHANGELOG.md}, over
 * a real repository holding both scripts.
 */
class CiChangelogTest {

  private static final Path CI = Path.of("..", "ci").toAbsolutePath();
  private static final String RELEASED = "# Changelog\n\n## 0.74.0\n\n- **Old.** Released.\n";
  private static final String README = "One file per change.\n";

  @TempDir Path repo;
  @TempDir Path output;
  private String base;

  private record Ran(int exit, String out, String err) {}

  @BeforeEach
  void aRepositoryWithAReleasedChangelog() throws Exception {
    git(Map.of(), "init", "-q");
    for (var script : List.of("changelog", "check")) {
      var copy = repo.resolve("ci").resolve(script);
      Files.createDirectories(copy.getParent());
      Files.copy(CI.resolve(script), copy);
      copy.toFile().setExecutable(true);
    }
    write("CHANGELOG.md", RELEASED);
    write("changes/README.md", README);
    base = commit("2026-11-01T00:00:00Z");
  }

  @Test
  void aReleaseFoldsEveryEntryUnderItsHeadingOldestFirstAndRemovesTheFiles() throws Exception {
    write("changes/second.md", "- **Second.** Added later.\n\n");
    write("changes/first.md", "- **First.** Added first.\n- **First, again.** Same change.\n");
    commitOnly("changes/first.md", "2026-11-02T00:00:00Z");
    write("changes/also.md", "- **Also.** Added with second.\n");
    commit("2026-11-03T00:00:00Z");
    write("changes/uncommitted.md", "- **Uncommitted.** Not yet in a commit.\n");

    var ran = sh("ci/changelog", "0.75.0");

    assertEquals(0, ran.exit(), ran.err());
    assertEquals(
        "# Changelog\n\n## 0.75.0\n\n"
            + "- **First.** Added first.\n- **First, again.** Same change.\n"
            + "- **Also.** Added with second.\n"
            + "- **Second.** Added later.\n"
            + "- **Uncommitted.** Not yet in a commit.\n"
            + "\n## 0.74.0\n\n- **Old.** Released.\n",
        Files.readString(repo.resolve("CHANGELOG.md")));
    try (var left = Files.list(repo.resolve("changes"))) {
      assertEquals(List.of(repo.resolve("changes/README.md")), left.toList());
    }
    assertEquals(
        "ci/changelog: folded 4 files of changes/ into '## 0.75.0' in CHANGELOG.md and removed"
            + " them.\n",
        ran.out());
  }

  @Test
  void aReleaseWithNoEntryIsRefusedAndChangesNothing() throws Exception {
    var ran = sh("ci/changelog", "0.75.0");

    assertEquals(1, ran.exit());
    assertEquals(
        "ci/changelog: changes/ holds no entry to fold into 0.75.0: each change adds"
            + " changes/<short-slug>.md.\n",
        ran.err());
    assertEquals(RELEASED, Files.readString(repo.resolve("CHANGELOG.md")));
  }

  @Test
  void aVersionAlreadyInTheChangelogIsRefusedAndChangesNothing() throws Exception {
    write("changes/late.md", "- **Late.** After the cut.\n");

    var ran = sh("ci/changelog", "0.74.0");

    assertEquals(1, ran.exit());
    assertEquals(
        "ci/changelog: CHANGELOG.md already has '## 0.74.0'; a version is folded once.\n",
        ran.err());
    assertEquals(RELEASED, Files.readString(repo.resolve("CHANGELOG.md")));
    assertTrue(Files.exists(repo.resolve("changes/late.md")));
  }

  @Test
  void aFileThatIsNoEntryIsRefusedNamingItBeforeAnythingChanges() throws Exception {
    write("changes/good.md", "- **Good.** An entry.\n");
    write("changes/prose.md", "Some notes, not a bullet.\n");

    var ran = sh("ci/changelog", "0.75.0");

    assertEquals(1, ran.exit());
    assertEquals(
        "ci/changelog: changes/prose.md is no entry: its first line must start a bullet,"
            + " '- **…** …'.\n",
        ran.err());
    assertEquals(RELEASED, Files.readString(repo.resolve("CHANGELOG.md")));
    assertTrue(Files.exists(repo.resolve("changes/good.md")));
  }

  @Test
  void aVersionThatIsNotThreeNumbersIsRefused() throws Exception {
    write("changes/good.md", "- **Good.** An entry.\n");

    var ran = sh("ci/changelog", "v0.75");

    assertEquals(2, ran.exit());
    assertEquals("ci/changelog: 'v0.75' is no version: give X.Y.Z, e.g. 0.75.0.\n", ran.err());
    assertEquals(RELEASED, Files.readString(repo.resolve("CHANGELOG.md")));
  }

  @Test
  void aChangeThatAddsToTheChangelogIsRefusedNamingTheFileToAddInstead() throws Exception {
    write(
        "CHANGELOG.md",
        RELEASED.replace("## 0.74.0", "## 0.75.0\n\n- **Mine.** New.\n\n## 0.74.0"));
    commit("2026-11-02T00:00:00Z");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(1, ran.exit());
    assertEquals(
        "ci/check: this change adds to CHANGELOG.md. Put its entry in a file of its own instead,"
            + " changes/<short-slug>.md, holding its bullets as they would read under the version"
            + " heading (changes/README.md); the release folds them in with ci/changelog.\n",
        ran.err());
  }

  @Test
  void aChangeThatAddsAnEntryFilePasses() throws Exception {
    write("changes/mine.md", "- **Mine.** New.\n");
    commit("2026-11-02T00:00:00Z");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(0, ran.exit(), ran.err());
    assertFalse(ran.err().contains("CHANGELOG.md"), ran.err());
  }

  @Test
  void aChangeThatOnlyRemovesLinesFromTheChangelogPassesAsMovingEntriesOutDoes() throws Exception {
    write("CHANGELOG.md", RELEASED.replace("- **Old.** Released.\n", ""));
    commit("2026-11-02T00:00:00Z");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(0, ran.exit(), ran.err());
  }

  @Test
  void aChangeThatRewordsAReleasedEntryAddsALineAndIsRefused() throws Exception {
    write("CHANGELOG.md", RELEASED.replace("Released.", "Released, reworded."));
    commit("2026-11-02T00:00:00Z");

    assertEquals(1, sh("ci/check", "changelog", base).exit());
  }

  @Test
  void anUncommittedAdditionToTheChangelogIsRefusedToo() throws Exception {
    write("CHANGELOG.md", RELEASED + "- **Mine.** Tucked in.\n");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(1, ran.exit());
  }

  private String commit(String date) throws Exception {
    git(Map.of(), "add", "-A");
    return commitStaged(date);
  }

  private void commitOnly(String path, String date) throws Exception {
    git(Map.of(), "add", path);
    commitStaged(date);
  }

  private String commitStaged(String date) throws Exception {
    git(
        Map.of("GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date),
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        "change");
    return git(Map.of(), "rev-parse", "HEAD").out().strip();
  }

  private void write(String path, String content) throws IOException {
    var file = repo.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private Ran git(Map<String, String> env, String... args) throws Exception {
    var command =
        new ArrayList<>(List.of("git", "-c", "user.name=CI", "-c", "user.email=ci@example.com"));
    command.addAll(Arrays.asList(args));
    var ran = run(command, env);
    assertEquals(0, ran.exit(), String.join(" ", command) + " failed: " + ran.err());
    return ran;
  }

  private Ran sh(String... args) throws Exception {
    var command = new ArrayList<>(List.of("bash"));
    command.addAll(Arrays.asList(args));
    return run(command, Map.of());
  }

  private Ran run(List<String> command, Map<String, String> env) throws Exception {
    var out = output.resolve("out");
    var err = output.resolve("err");
    var builder =
        new ProcessBuilder(command)
            .directory(repo.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile());
    builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    builder.environment().putAll(env);
    var process = builder.start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError(String.join(" ", command) + " did not end within 30 s");
    }
    return new Ran(process.exitValue(), Files.readString(out), Files.readString(err));
  }
}
