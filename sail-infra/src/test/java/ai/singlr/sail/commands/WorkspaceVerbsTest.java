/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.ProjectPhase;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProvisionTracker;
import ai.singlr.sail.engine.SailPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
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
  void aTemplateIsNotBuiltWhenARepoAsksForATokenNoOneGave() {
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkspaceVerbs.requireTokens(acme(), Map.of(), url -> true));

    assertEquals(
        "The template cannot clone https://github.com/acme/app.git: it asks for credentials and no"
            + " token was given for its host. Pass one with --git-token <token>, or keep"
            + " GITHUB_TOKEN through sudo with sudo --preserve-env=GITHUB_TOKEN.",
        failure.getMessage());
    WorkspaceVerbs.requireTokens(acme(), Map.of("*", "tok"), url -> true);
    WorkspaceVerbs.requireTokens(acme(), Map.of(), url -> false);
  }

  @Test
  void aBuildAsksAboutTokensBeforeItTakesThePairingLockAndARefusedOneNeverTakesIt()
      throws Exception {
    var held = new AtomicBoolean();
    var seen = new ArrayList<String>();
    Callable<AutoCloseable> pairing =
        () -> {
          seen.add("lock");
          held.set(true);
          return () -> held.set(false);
        };
    var build =
        WorkspaceVerbs.checkedThenPaired(
            acme(),
            Map.of(),
            url -> {
              seen.add("probe while held=" + held.get());
              return false;
            },
            pairing,
            (into, fresh) -> seen.add("provision while held=" + held.get()));
    var refused =
        WorkspaceVerbs.checkedThenPaired(
            acme(),
            Map.of(),
            url -> true,
            pairing,
            (into, fresh) -> seen.add("provisioned anyway"));
    var template = new ProjectProvisioner.Instance("acme--tmpl", Optional.of("tank"), Map.of());

    build.into(template, true);
    assertThrows(IllegalArgumentException.class, () -> refused.into(template, true));

    assertEquals(List.of("probe while held=false", "lock", "provision while held=true"), seen);
    assertFalse(held.get(), "the lock is let go after the build");
  }

  @Test
  void aTemplateIsBuiltFromTheBaseProfilesPoolAndTheDigestApplyRecords() throws Exception {
    var named =
        new HostYaml(
            "dir", "devpool", null, "incusbr0", "singlr-base", "ubuntu/24.04", "6.0", null, null);
    var unnamed =
        new HostYaml(
            "dir", null, null, "incusbr0", "singlr-base", "ubuntu/24.04", "6.0", null, null);

    var source =
        WorkspaceVerbs.source(named, acme(), Optional.empty(), name -> false, (into, fresh) -> {});

    assertEquals("devpool", source.basePool());
    assertEquals(ProjectProvisioner.inputs(acme(), Optional.empty()), source.inputs());
    assertEquals(
        HostYaml.DEFAULT_POOL,
        WorkspaceVerbs.source(unnamed, acme(), Optional.empty(), name -> false, (into, fresh) -> {})
            .basePool());
  }
}
