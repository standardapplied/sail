/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ProjectLoaderTest {

  private static final ProjectDefinition ACME = TestDefinitions.map(Map.of("name", "acme"));

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

    assertEquals(ErrorCode.PROJECT_NOT_IN_CATALOG, refused.failure().errorCode());
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
            new ScriptedShellExecutor().onThrow("incus list", new IOException("incus unreachable")),
            p -> Optional.of(ACME));

    var failed = assertThrows(ApiException.class, () -> loader.load("acme"));

    assertEquals(ErrorCode.PROJECT_LOAD_FAILED, failed.failure().errorCode());
    assertEquals("Failed to load project.", failed.getMessage());
  }

  @Test
  void aProjectWhoseNameIsAWorkspacesInstanceIsAConflictEveryLoadRefusesAlike() {
    var loader =
        new ProjectLoader(
            listing(
                "[{\"name\":\"acme-fix\",\"status\":\"Running\",\"config\":"
                    + "{\"user.sail.project\":\"acme\",\"user.sail.workspace\":\"fix\"}}]"),
            p -> Optional.of(ACME));

    for (Executable load :
        List.<Executable>of(
            () -> loader.load("acme-fix"),
            () -> loader.loadRunning("acme-fix"),
            () -> loader.loadCreated("acme-fix"),
            () -> loader.requireExists("acme-fix"))) {
      var refused = assertThrows(ApiException.class, load);
      assertEquals(ErrorCode.CONFLICT, refused.failure().errorCode());
      assertEquals(
          "Project acme-fix cannot take instance acme-fix: it is workspace fix of project acme.",
          refused.getMessage());
      assertEquals(
          Remedy.of(
              "Delete the workspace (",
              Remedy.sail("project workspace delete", "acme", "fix"),
              ") or give the project another name; the workspace itself is reached with ",
              Remedy.sail("project workspace shell", "acme", "fix"),
              "."),
          refused.remedy());
    }
  }

  @Test
  void aStoppedProjectIsRefusedWithTheCommandThatStartsIt() {
    var loader =
        new ProjectLoader(
            listing("[{\"name\":\"gamma\",\"status\":\"Stopped\"}]"), p -> Optional.of(ACME));

    var refused = assertThrows(ApiException.class, () -> loader.loadRunning("gamma"));

    assertEquals(ErrorCode.PROJECT_STOPPED, refused.failure().errorCode());
    assertEquals("Project 'gamma' is stopped.", refused.getMessage());
    assertEquals(
        Remedy.of("Start it with ", Remedy.sail("project start", "gamma"), "."), refused.remedy());
    assertEquals("Start it with project start gamma.", refused.error().action());
  }

  @Test
  void aFrozenProjectIsRefusedWithTheCommandThatResumesIt() {
    var loader =
        new ProjectLoader(
            listing("[{\"name\":\"gamma\",\"status\":\"Frozen\"}]"), p -> Optional.of(ACME));

    var refused = assertThrows(ApiException.class, () -> loader.loadRunning("gamma"));

    assertEquals(ErrorCode.PROJECT_STOPPED, refused.failure().errorCode());
    assertEquals("Project 'gamma' is frozen.", refused.getMessage());
    assertEquals(
        Remedy.of("Resume it with ", Remedy.sail("project start", "gamma"), "."), refused.remedy());
    assertEquals(new ContainerState.Frozen(), loader.loadCreated("gamma").state());
  }

  @Test
  void aLoaderNeedsAReader() {
    assertThrows(NullPointerException.class, () -> new ProjectLoader(listing("[]"), null));
  }

  private static ShellExec listing(String json) {
    return new ScriptedShellExecutor().onOk("incus list", json);
  }
}
