/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoOriginTest {

  private static final String URL = "https://github.com/org/repo.git";

  @TempDir Path tempDir;

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
                new ProjectDefinition.Repo("https://github.com/org/web.git", "web", null)));
  }

  /** Runs the git a container would, on this machine, so git finds repositories as it does. */
  private static final class LocalGit implements ShellExec {
    private final ShellExecutor git = new ShellExecutor(false);

    @Override
    public Result exec(List<String> command) throws IOException {
      return exec(command, null, null);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      try {
        return git.exec(command.subList(command.indexOf("--") + 1, command.size()));
      } catch (InterruptedException | TimeoutException e) {
        throw new IOException(e);
      }
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  private static void git(Path dir, String... args) throws Exception {
    var command = new ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    var result = new ShellExecutor(false).exec(command);
    assertTrue(result.ok(), String.join(" ", command) + ": " + result.stderr());
  }

  private Path checkout(String origin) throws Exception {
    var dir = Files.createDirectories(tempDir.resolve("repo"));
    git(dir, "init", "-q");
    if (origin != null) {
      git(dir, "remote", "add", "origin", origin);
    }
    return dir;
  }

  private static String refusal(Path dir) {
    return assertThrows(
            IllegalStateException.class,
            () ->
                RepoOrigin.require(
                    new LocalGit(),
                    "acme",
                    dir.toString(),
                    new ProjectDefinition.Repo(URL, "web", null)))
        .getMessage();
  }

  private static String refused(String found) {
    return "Repo 'web' "
        + found
        + ", but the definition says "
        + URL
        + ". Move it aside (it may hold work) or fix the URL.";
  }

  @Test
  void aCheckoutOfTheDefinedRepositoryConverges() throws Exception {
    var dir = checkout("git@github.com:org/repo");

    assertDoesNotThrow(
        () ->
            RepoOrigin.require(
                new LocalGit(),
                "acme",
                dir.toString(),
                new ProjectDefinition.Repo(URL, "web", null)));
  }

  @Test
  void anEmptyDirectoryInsideACheckoutOfTheRepositoryIsNoCheckout() throws Exception {
    var child = Files.createDirectories(checkout(URL).resolve("web"));

    assertEquals(refused("is not a git checkout"), refusal(child));
  }

  @Test
  void aBareRepositoryIsNoCheckout() throws Exception {
    var bare = Files.createDirectories(tempDir.resolve("bare.git"));
    git(bare, "init", "-q", "--bare");
    git(bare, "remote", "add", "origin", URL);

    assertEquals(refused("is not a git checkout"), refusal(bare));
  }

  @Test
  void aDirectoryOutsideAnyRepositoryIsNoCheckout() throws Exception {
    var plain = Files.createDirectories(tempDir.resolve("plain"));
    Files.createDirectories(plain.resolve(".git"));

    assertEquals(refused("is not a git checkout"), refusal(plain));
  }

  @Test
  void aCheckoutWithNoOriginOrAnotherOriginIsRefused() throws Exception {
    var dir = checkout(null);

    assertEquals(refused("is a clone with no origin"), refusal(dir));
    git(dir, "remote", "add", "origin", "https://token@github.com/org/old.git");
    assertEquals(refused("is a clone of https://github.com/org/old.git"), refusal(dir));
  }

  @Test
  void aTokenInTheDefinitionsUrlIsNeverEchoed() throws Exception {
    var dir = checkout("https://github.com/org/old.git");
    var withToken =
        new ProjectDefinition.Repo(
            "https://x-access-token:secret@github.com/org/new.git", "web", null);

    var refusal =
        assertThrows(
                IllegalStateException.class,
                () -> RepoOrigin.require(new LocalGit(), "acme", dir.toString(), withToken))
            .getMessage();

    assertEquals(
        "Repo 'web' is a clone of https://github.com/org/old.git, but the definition says"
            + " https://github.com/org/new.git. Move it aside (it may hold work) or fix the URL.",
        refusal);
  }
}
