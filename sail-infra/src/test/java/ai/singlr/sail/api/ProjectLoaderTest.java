/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ShellExec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectLoaderTest {

  private static final SailYaml ACME = SailYaml.fromMap(Map.of("name", "acme"));

  @Test
  void aProjectIsItsDefinitionAndItsContainerState() {
    var loader =
        new ProjectLoader(
            listing("[{\"name\":\"acme\",\"status\":\"Running\"}]"), p -> Optional.of(ACME));

    var loaded = loader.load("acme");

    assertSame(ACME, loaded.config());
    assertEquals(ContainerState.Running.class, loaded.state().getClass());
  }

  @Test
  void aProjectWithNoRowIsNotFoundWithTheReadersOwnError() {
    var loader = new ProjectLoader(listing("[]"), p -> Optional.empty());

    var refused = assertThrows(ApiException.class, () -> loader.load("acme"));

    assertEquals(ErrorCode.PROJECT_DESCRIPTOR_NOT_FOUND, refused.failure().errorCode());
    assertEquals("Project 'acme' is not in the catalog.", refused.getMessage());
  }

  @Test
  void aRowThatCannotBeReadFailsTheLoadNamingTheProject() {
    var unreadable = new ProjectReader.Unreadable("acme", new IllegalArgumentException("bad"));
    var loader =
        new ProjectLoader(
            listing("[]"),
            p -> {
              throw unreadable;
            });

    var failed = assertThrows(ApiException.class, () -> loader.load("acme"));

    assertEquals(ErrorCode.PROJECT_LOAD_FAILED, failed.failure().errorCode());
    assertEquals(unreadable.getMessage(), failed.getMessage());
    assertSame(unreadable, failed.getCause());
  }

  @Test
  void aContainerThatCannotBeQueriedFailsTheLoadWithoutBlamingTheDefinition() {
    var loader =
        new ProjectLoader(
            new ShellExec() {
              public Result exec(List<String> command) {
                throw new IllegalStateException("incus unreachable");
              }

              public Result exec(List<String> command, Path workDir, Duration timeout) {
                return exec(command);
              }

              public boolean isDryRun() {
                return false;
              }
            },
            p -> Optional.of(ACME));

    var failed = assertThrows(ApiException.class, () -> loader.load("acme"));

    assertEquals(ErrorCode.PROJECT_LOAD_FAILED, failed.failure().errorCode());
    assertEquals("Failed to load project.", failed.getMessage());
  }

  @Test
  void aLoaderNeedsAReader() {
    assertThrows(NullPointerException.class, () -> new ProjectLoader(listing("[]"), null));
  }

  private static ShellExec listing(String json) {
    return new ShellExec() {
      public Result exec(List<String> command) {
        return new Result(0, json, "");
      }

      public Result exec(List<String> command, Path workDir, Duration timeout) {
        return exec(command);
      }

      public boolean isDryRun() {
        return false;
      }
    };
  }
}
