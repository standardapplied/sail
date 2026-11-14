/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.RunPlace;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a run goes, decided around its reservation, and what each way it can fail answers: a
 * project name too long to name a spec's workspace, an Incus that cannot be read or is interrupted,
 * a create that fails, and a box whose memory Incus does not say.
 */
class RunPlacesTest {

  private static final String YAML =
      """
      name: acme
      resources: {cpu: 2, memory: 4GB, disk: 50GB}
      repos:
        - url: https://github.com/acme/app.git
          path: app
      agent:
        type: claude-code
      """;

  @TempDir Path dir;

  private ProjectReader definitions() throws IOException {
    var yaml = dir.resolve("sail.yaml");
    Files.writeString(yaml, YAML);
    return TestProjects.reading(yaml);
  }

  private ProjectDefinition config() throws IOException {
    return TestDefinitions.map(YamlUtil.parseMap(YAML));
  }

  private RunPlaces places(ShellExec incus, ShellExec shell) throws IOException {
    return new RunPlaces(
        new ProjectLoader(shell, definitions()),
        TestWorkspaces.over(incus),
        null,
        shell,
        RunLauncher.Room.NONE);
  }

  /** A shell on which every command holding {@code fragment} is interrupted, and others answer. */
  private static ShellExec interrupting(ShellExec delegate, String fragment) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) throws IOException, InterruptedException {
        return exec(command, null, null);
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout)
          throws IOException, InterruptedException {
        if (String.join(" ", command).contains(fragment)) {
          throw new InterruptedException("interrupted");
        }
        try {
          return delegate.exec(command, workDir, timeout);
        } catch (TimeoutException e) {
          throw new IOException(e);
        }
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  @Test
  void aProjectNameTooLongToNameASpecsWorkspaceIsABadRequest() throws IOException {
    var project = "p".repeat(55);
    var places = places(TestWorkspaces.incus(definitions(), "acme"), new ScriptedShellExecutor());

    var refused =
        assertThrows(
            ApiException.class, () -> places.checkSpec(project, config(), "a-long-spec-id", "eng"));

    assertEquals(ErrorCode.BAD_REQUEST, refused.failure().errorCode());
    assertTrue(refused.getMessage().startsWith("Project '" + project + "' is 55 characters"));
  }

  @Test
  void anIncusThatCannotBeReadOrIsInterruptedIsACommandFailure() throws Exception {
    var unreadable =
        new ScriptedShellExecutor().onThrow("incus storage list", new IOException("no daemon"));
    var places = places(unreadable, new ScriptedShellExecutor());

    var refused =
        assertThrows(ApiException.class, () -> places.checkSpec("acme", config(), "auth", "eng"));
    var interrupted =
        assertThrows(
            ApiException.class,
            () ->
                places(interrupting(unreadable, "incus storage list"), new ScriptedShellExecutor())
                    .checkSpec("acme", config(), "auth", "eng"));

    assertEquals(ErrorCode.COMMAND_FAILED, refused.failure().errorCode());
    assertEquals("Could not read this box's Incus: no daemon", refused.getMessage());
    assertEquals(ErrorCode.COMMAND_FAILED, interrupted.failure().errorCode());
    assertTrue(Thread.interrupted(), "the interrupt is kept for whoever waits on the thread");
  }

  @Test
  void aCreateThatFailsAnswersItsOwnReasonAndAnInterruptedOneKeepsTheInterrupt() throws Exception {
    var refusing = TestWorkspaces.incus(definitions(), "acme").onFail("incus copy", "disk full");
    var places = places(refusing, new ScriptedShellExecutor());
    var check = places.checkSpec("acme", config(), "auth", "eng");

    var failed = assertThrows(ApiException.class, () -> places.enter(check, config(), "eng"));
    var interrupted =
        assertThrows(
            ApiException.class,
            () ->
                places(
                        interrupting(TestWorkspaces.incus(definitions(), "acme"), "incus copy"),
                        new ScriptedShellExecutor())
                    .enter(check, config(), "eng"));

    assertEquals(ErrorCode.COMMAND_FAILED, failed.failure().errorCode());
    assertEquals(
        "Workspace auth of acme could not be made ready: Failed to clone acme--tmpl/v1 as"
            + " 'acme-auth': disk full",
        failed.getMessage());
    assertEquals(ErrorCode.COMMAND_FAILED, interrupted.failure().errorCode());
    assertTrue(Thread.interrupted());
  }

  @Test
  void aBoxWhoseMemoryIncusDoesNotSayOrWillNotTellSetsNoCapEvenWithALiveWorkspace()
      throws Exception {
    var incus = TestWorkspaces.incus(definitions(), "acme");
    var db = Sqlite.open(dir.resolve("r.db"));
    new SchemaManager(db).migrate();
    var runs = new RunStore(db);
    Acting.system(
        () ->
            runs.reserveDispatch(
                DateTimeUtils.newId().toString(),
                "acme",
                "other",
                "eng",
                "build",
                List.of("app"),
                "claude-code",
                null,
                "t",
                "l",
                "u"));
    var unreadable =
        new ScriptedShellExecutor().onThrow("incus query /1.0/resources", new IOException("x"));
    var interrupted = interrupting(new ScriptedShellExecutor(), "incus query /1.0/resources");
    var silent = new ScriptedShellExecutor().onOk("incus query /1.0/resources", "{}");
    var roomy =
        new ScriptedShellExecutor()
            .onOk("incus query /1.0/resources", "{\"memory\": {\"total\": 68719476736}}");

    for (var shell : List.of(unreadable, interrupted, silent, roomy)) {
      var places =
          new RunPlaces(
              new ProjectLoader(shell, definitions()),
              TestWorkspaces.over(incus),
              runs,
              shell,
              RunLauncher.Room.NONE);
      assertInstanceOf(
          RunPlaces.InWorkspace.class, places.checkSpec("acme", config(), "auth", "eng"));
    }
    assertTrue(Thread.interrupted(), "a box's memory read that was interrupted keeps it");
  }

  @Test
  void aRunOfNoSpecGoesInTheProjectsRunningContainer() throws Exception {
    var running =
        new ScriptedShellExecutor()
            .onOk(
                "incus list ^acme$",
                "[{\"name\": \"acme\", \"status\": \"Running\", \"state\": {}}]");
    var places = places(new ScriptedShellExecutor(), running);

    var checked = places.check("acme", config(), null, "eng");

    assertEquals(new RunPlaces.InContainer(new RunPlace.ProjectContainer("acme")), checked);
    assertEquals(
        new RunPlaces.Entered(checked.place(), Optional.empty(), false),
        places.enter(checked, config(), "eng"));
  }

  @Test
  void anInstanceNameNoCatalogCanReadIsTakenForAProjectsAndLeftAlone() {
    var loader =
        new ProjectLoader(
            new ScriptedShellExecutor(),
            name -> {
              throw new ProjectReader.Unreadable(name, new IOException("corrupt"));
            });

    assertTrue(loader.held("acme-auth"));
  }

  @Test
  void aFirstEntryMakesTheWorkspaceFromATemplateAsTheDefinitionIsAndCallsNothingStale()
      throws Exception {
    var incus = TestWorkspaces.incus(definitions(), "acme");
    var places = places(incus, new ScriptedShellExecutor());
    var check = places.checkSpec("acme", config(), "auth", "eng");

    var entered = places.enter(check, config(), "eng");

    assertInstanceOf(Workspaces.Made.class, entered.workspace().orElseThrow());
    assertEquals(false, entered.stale());
  }
}
