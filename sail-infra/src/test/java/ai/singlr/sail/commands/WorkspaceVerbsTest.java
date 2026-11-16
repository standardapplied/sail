/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.CheckedTokens;
import ai.singlr.sail.engine.ProjectPhase;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProvisionTracker;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceVerbsTest {

  @TempDir Path tempDir;

  private static ProjectDefinition acme() {
    return new ProjectDefinition(
        "acme",
        null,
        new ProjectDefinition.Resources(2, "4GB", "50GB"),
        null,
        null,
        null,
        List.of(new ProjectDefinition.Repo("https://github.com/acme/app.git", "app", null)),
        null,
        null,
        null,
        null);
  }

  /**
   * Scenario 21: a stop or delete of a spec's workspace is refused while a run of that spec is live
   * in it on this box; a run of another spec, of no spec, or on another box is no reason.
   */
  @Test
  void aWorkspaceIsHeldByALiveRunOfItsOwnSpecOnThisBoxAlone(@TempDir Path dir) throws Exception {
    var dbPath = dir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.named("acme")));
      var runs = new RunStore(db);
      for (var run :
          List.of(List.of("auth", "eng"), List.of("billing", "other"), List.of("", "eng"))) {
        Acting.system(
            () ->
                runs.reserveDispatch(
                    DateTimeUtils.newId().toString(),
                    "acme",
                    run.get(0).isEmpty() ? null : run.get(0),
                    run.get(1),
                    run.get(0).isEmpty() ? "adhoc" : "build",
                    List.of("app"),
                    "claude-code",
                    null,
                    "t",
                    "l",
                    "u"));
      }
    }

    var live = WorkspaceVerbs.liveRun("acme", () -> OperationsFactory.open(dbPath), "eng");

    assertTrue(live.test("auth"));
    assertFalse(live.test("billing"), "billing's run is another box's");
    assertFalse(live.test("docs"));
  }

  @Test
  void aTemplateBuildKeepsItsProgressApartFromTheProjectsOwn() {
    assertEquals(SailPaths.templateProvisionState("acme"), WorkspaceVerbs.progressOf("acme"));
    assertNotEquals(SailPaths.provisionState("acme"), WorkspaceVerbs.progressOf("acme"));
  }

  @Test
  void aFreshTemplateOrAFinishedRecordStartsEveryStepAgainAndAnInterruptedOneResumes()
      throws Exception {
    var progress = tempDir.resolve("template-provision-state.yaml");
    var earlier = new ProvisionTracker<>(ProjectPhase.class, progress, false).load("in");
    earlier.advance(ProjectPhase.CONTAINER_LAUNCHED);

    var resumed = WorkspaceVerbs.tracker(progress, "in", false, false);
    assertTrue(
        resumed.isCompleted(ProjectPhase.CONTAINER_LAUNCHED), "an interrupted build resumes");

    var fresh = WorkspaceVerbs.tracker(progress, "in", true, false);
    assertFalse(
        fresh.isCompleted(ProjectPhase.CONTAINER_LAUNCHED),
        "a template that does not exist yet starts from the first step");
  }

  @Test
  void aTemplateIsBuiltFromTheBaseProfilesPoolAndItsDigestIsTheDefinitionsAlone() throws Exception {
    var named =
        new HostYaml(
            "dir", "devpool", null, "incusbr0", "singlr-base", "ubuntu/24.04", "6.0", null, null);
    var unnamed =
        new HostYaml(
            "dir", null, null, "incusbr0", "singlr-base", "ubuntu/24.04", "6.0", null, null);

    var source = WorkspaceVerbs.source(named, acme(), none(), name -> false, (into, fresh) -> {});

    assertEquals("devpool", source.basePool());
    assertEquals(ProjectProvisioner.inputs(acme(), Optional.empty()), source.inputs());
    assertEquals(
        HostYaml.DEFAULT_POOL,
        WorkspaceVerbs.source(unnamed, acme(), none(), name -> false, (into, fresh) -> {})
            .basePool());
  }

  /**
   * sail#421 N1: the built template is searched for the tokens this build was given, the ones
   * already in it, and no other: a stored token for a host the project does not name never enters
   * the template, not even as a search pattern.
   */
  @Test
  void aTemplateIsSearchedForTheTokensItsBuildWasGivenAndNoOther() {
    var host =
        new HostYaml(
            "dir", "devpool", null, "incusbr0", "singlr-base", "ubuntu/24.04", "6.0", null, null);
    var given =
        new WorkspaceVerbs.Keeper(
                Optional.of("eng"),
                Map.of(
                    "github.com", "ghp_stored_for_github", "gitlab.com", "glpat_stored_for_gitlab"))
            .check(acme(), url -> true, Map.of());

    var source = WorkspaceVerbs.source(host, acme(), given, name -> false, (into, fresh) -> {});

    assertEquals(List.of("ghp_stored_for_github"), List.copyOf(source.tokens()));
  }

  private static CheckedTokens none() {
    return new WorkspaceVerbs.Keeper(Optional.empty(), Map.of())
        .check(acme(), url -> false, Map.of());
  }
}
