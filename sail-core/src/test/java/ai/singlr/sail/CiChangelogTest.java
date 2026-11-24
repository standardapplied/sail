/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.Child;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
  private static final String EDITS_CHANGELOG =
      "ci/changelog: this change edits CHANGELOG.md, which only the release writes. Put its"
          + " entry in a file of its own instead, changes/<spec-id>.md, holding its bullets as"
          + " they would read under the version heading (changes/README.md).\n";

  @TempDir Path repo;
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
            + " changes/<spec-id>.md.\n",
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
    assertEquals(EDITS_CHANGELOG, ran.err());
  }

  @Test
  void aChangeThatAddsAnEntryFilePasses() throws Exception {
    write("changes/mine.md", "- **Mine.** New.\n");
    commit("2026-11-02T00:00:00Z");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(0, ran.exit(), ran.err());
    assertEquals("", ran.err());
  }

  @Test
  void aChangeOnASpecsBranchAddsTheEntryNamedForItsSpec() throws Exception {
    git(Map.of(), "checkout", "-q", "-b", "agent/the-spec");
    write("changes/the-spec.md", "- **Mine.** New.\n");
    commit("2026-11-02T00:00:00Z");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(0, ran.exit(), ran.err());
  }

  @Test
  void anEntryNamedOtherwiseThanTheBranchsSpecIsRefusedNamingTheOneToAdd() throws Exception {
    git(Map.of(), "checkout", "-q", "-b", "agent/the-spec");
    write("changes/mine.md", "- **Mine.** New.\n");
    commit("2026-11-02T00:00:00Z");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(1, ran.exit());
    assertEquals(
        "ci/changelog: this change adds changes/mine.md, but its branch agent/the-spec names its"
            + " entry changes/the-spec.md: rename it.\n",
        ran.err());
  }

  @Test
  void aPullRequestsBranchIsReadFromItsRunWhereTheCheckoutHasNone() throws Exception {
    write("changes/mine.md", "- **Mine.** New.\n");
    commit("2026-11-02T00:00:00Z");

    var ran =
        run(List.of("bash", "ci/check", "changelog", base), Map.of("GITHUB_HEAD_REF", "agent/x"));

    assertEquals(1, ran.exit());
    assertEquals(
        "ci/changelog: this change adds changes/mine.md, but its branch agent/x names its entry"
            + " changes/x.md: rename it.\n",
        ran.err());
  }

  @Test
  void aChangeThatAddsNoEntryIsRefusedNamingTheOneToAdd() throws Exception {
    git(Map.of(), "checkout", "-q", "-b", "agent/the-spec");
    write("README.md", "Changed.\n");
    commit("2026-11-02T00:00:00Z");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(1, ran.exit());
    assertEquals(
        "ci/changelog: this change adds no entry: add changes/the-spec.md holding its bullets as"
            + " they would read under the version heading (changes/README.md).\n",
        ran.err());
  }

  @Test
  void aChangeThatAddsTwoEntriesIsRefusedNamingBoth() throws Exception {
    write("changes/one.md", "- **One.** New.\n");
    write("changes/two.md", "- **Two.** New.\n");
    commit("2026-11-02T00:00:00Z");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(1, ran.exit());
    assertEquals(
        "ci/changelog: this change adds 2 entries (changes/one.md, changes/two.md): a pull request"
            + " adds exactly one, changes/<spec-id>.md.\n",
        ran.err());
  }

  @Test
  void anEntryNotYetCommittedCountsAsAdded() throws Exception {
    write("changes/mine.md", "- **Mine.** New.\n");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(0, ran.exit(), ran.err());
  }

  @Test
  void aChangeThatRemovesReleasedEntriesIsRefused() throws Exception {
    write("CHANGELOG.md", RELEASED.replace("- **Old.** Released.\n", ""));
    commit("2026-11-02T00:00:00Z");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(1, ran.exit());
    assertEquals(EDITS_CHANGELOG, ran.err());
  }

  @Test
  void aChangeThatRewordsAReleasedEntryIsRefused() throws Exception {
    write("CHANGELOG.md", RELEASED.replace("Released.", "Released, reworded."));
    commit("2026-11-02T00:00:00Z");

    assertEquals(1, sh("ci/check", "changelog", base).exit());
  }

  @Test
  void anUncommittedEditOfTheChangelogIsRefusedToo() throws Exception {
    write("CHANGELOG.md", RELEASED + "- **Mine.** Tucked in.\n");

    assertEquals(1, sh("ci/check", "changelog", base).exit());
  }

  @Test
  void aBaseWithNoChangesFolderYetLetsTheChangeThatMovesEntriesOutTouchTheChangelog()
      throws Exception {
    git(Map.of(), "rm", "-q", "changes/README.md");
    var before = commit("2026-11-02T00:00:00Z");
    write("changes/README.md", README);
    write("changes/moved.md", "- **Moved.** Out of the section.\n");
    write("CHANGELOG.md", RELEASED.replace("- **Old.** Released.\n", ""));
    commit("2026-11-03T00:00:00Z");

    var ran = sh("ci/check", "changelog", before);

    assertEquals(0, ran.exit(), ran.err());
  }

  @Test
  void aMalformedEntryFailsItsOwnPullRequestNotTheRelease() throws Exception {
    write("changes/prose.md", "Some notes, not a bullet.\n");
    commit("2026-11-02T00:00:00Z");

    var ran = sh("ci/check", "changelog", base);

    assertEquals(1, ran.exit());
    assertEquals(
        "ci/changelog: changes/prose.md is no entry: its first line must start a bullet,"
            + " '- **…** …'.\n",
        ran.err());
  }

  @Test
  void anEntryWithWindowsLineEndsIsRefusedAtThePullRequestAndAtTheFold() throws Exception {
    write("changes/crlf.md", "- **Windows.** Saved with CRLF.\r\n");
    commit("2026-11-02T00:00:00Z");
    var refusal =
        "ci/changelog: changes/crlf.md has Windows line ends (CRLF): save it with LF only.\n";

    var checked = sh("ci/check", "changelog", base);
    var folded = sh("ci/changelog", "0.75.0");

    assertEquals(1, checked.exit());
    assertEquals(refusal, checked.err());
    assertEquals(1, folded.exit());
    assertEquals(refusal, folded.err());
    assertEquals(RELEASED, Files.readString(repo.resolve("CHANGELOG.md")));
  }

  @Test
  void anythingButAnEntryFileDirectlyInChangesIsRefusedNamingIt() throws Exception {
    write("changes/good.md", "- **Good.** An entry.\n");
    write("changes/sub/nested.md", "- **Nested.** One folder down.\n");
    commit("2026-11-02T00:00:00Z");
    var refusal =
        "ci/changelog: changes/sub is not an entry file: changes/ holds only <spec-id>.md"
            + " files, directly in it.\n";

    var checked = sh("ci/check", "changelog", base);
    var folded = sh("ci/changelog", "0.75.0");

    assertEquals(1, checked.exit());
    assertEquals(refusal, checked.err());
    assertEquals(1, folded.exit());
    assertEquals(refusal, folded.err());
    assertTrue(Files.exists(repo.resolve("changes/good.md")));
  }

  @Test
  void aReleaseTagPassesOnlyOnceItsBumpCommitFolded() throws Exception {
    write("changes/mine.md", "- **Mine.** New.\n");
    commit("2026-11-02T00:00:00Z");

    var unfolded = sh("ci/changelog", "released", "0.75.0");
    sh("ci/changelog", "0.75.0");
    var folded = sh("ci/changelog", "released", "0.75.0");

    assertEquals(1, unfolded.exit());
    assertEquals(
        "ci/changelog: changes/ still holds 1 entry, so 0.75.0's bump commit did not fold them:"
            + " run 'ci/changelog 0.75.0' in the version bump's commit, then tag that commit.\n",
        unfolded.err());
    assertEquals(0, folded.exit(), folded.err());
    assertEquals(
        "ci/changelog: CHANGELOG.md's first version is 0.75.0 and changes/ holds no entry.\n",
        folded.out());
  }

  @Test
  void aReleaseTagWhoseChangelogStartsAtAnotherVersionIsRefused() throws Exception {
    var ran = sh("ci/changelog", "released", "0.75.0");

    assertEquals(1, ran.exit());
    assertEquals(
        "ci/changelog: CHANGELOG.md's first version is 0.74.0, not 0.75.0: run 'ci/changelog"
            + " 0.75.0' in the version bump's commit, then tag that commit.\n",
        ran.err());
  }

  @Test
  void theReleaseWorkflowRefusesATagThroughTheGuardBeforeItShipsAnything() throws Exception {
    var workflow = Files.readString(CI.resolveSibling(".github/workflows/release.yml"));

    assertTrue(workflow.contains("run: ci/changelog released \"${GITHUB_REF_NAME#v}\""), workflow);
    assertTrue(
        workflow.contains("    needs: [ci-passed, folded, build]\n    permissions:"), workflow);
    assertTrue(workflow.contains("  release:\n    needs: [ci-passed, folded, build]"), workflow);
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
    var builder = new ProcessBuilder(command).directory(repo.toFile());
    builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    builder.environment().remove("GITHUB_HEAD_REF");
    builder.environment().putAll(env);
    var ran = Child.run(builder);
    return new Ran(ran.exit(), ran.stdout(), ran.stderr());
  }
}
