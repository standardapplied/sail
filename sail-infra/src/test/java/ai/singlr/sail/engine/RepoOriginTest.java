/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepoOriginTest {

  private static final List<String> ONE_REPOSITORY =
      List.of(
          "https://github.com/org/repo.git",
          "https://github.com/org/repo/",
          "git@github.com:org/repo.git",
          "ssh://git@GitHub.com/org/repo",
          "ssh://git@github.com:22/org/repo.git",
          "https://x-access-token:secret@github.com/org/repo");

  @Test
  void everySpellingOfOneRepositoryIsTheSameRepository() {
    for (var one : ONE_REPOSITORY) {
      for (var other : ONE_REPOSITORY) {
        assertTrue(RepoOrigin.sameRepository(one, other), one + " vs " + other);
      }
    }
  }

  @Test
  void anotherPathOrHostIsAnotherRepository() {
    assertFalse(
        RepoOrigin.sameRepository("https://github.com/org/repo.git", "https://github.com/org/old"));
    assertFalse(
        RepoOrigin.sameRepository("git@github.com:org/repo.git", "git@gitlab.com:org/repo.git"));
    assertFalse(
        RepoOrigin.sameRepository("https://github.com/org/repo", "https://github.com/org/Repo"));
    assertFalse(RepoOrigin.sameRepository("https://github.com/org/repo", "org/repo"));
  }

  @Test
  void aLocalPathIsTheRepositoryAtThatPath() {
    assertTrue(RepoOrigin.sameRepository("/srv/git/repo.git", "file:///srv/git/repo"));
    assertTrue(RepoOrigin.sameRepository("ssh://[::1]:2222/org/repo", "ssh://[::1]/org/repo"));
  }

  @Test
  void aDryRunObservesNoCheckoutAndRefusesNothing() throws Exception {
    var dryRun =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) {
            throw new AssertionError("a dry run asks the container nothing: " + command);
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout) {
            return exec(command);
          }

          @Override
          public boolean isDryRun() {
            return true;
          }
        };

    assertDoesNotThrow(
        () ->
            RepoOrigin.require(
                dryRun,
                "acme",
                "/home/dev/workspace/web",
                new SailYaml.Repo("https://github.com/org/web.git", "web", null)));
  }
}
