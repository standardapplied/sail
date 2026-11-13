/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

/**
 * W10 for every verb that addresses a container by a project's name: each passes through the one
 * guard, {@code ContainerManager.queryState} or {@code queryInfo} (which refuse an instance of that
 * name that is a workspace, a template or a copy, {@code ContainerManagerTest}), or asks {@code
 * BoxInstances.requireNotTakenFor} itself, as the verbs that read no state do.
 */
class ProjectVerbsTakeNoWorkspaceTest {

  /** Each verb, by its class, and the guard it passes through. */
  private static final Map<Class<?>, String> VERBS =
      Map.ofEntries(
          Map.entry(UpCommand.class, "queryState("),
          Map.entry(DownCommand.class, "queryState("),
          Map.entry(SwitchCommand.class, "queryState("),
          Map.entry(PsCommand.class, "queryState("),
          Map.entry(LogsCommand.class, "queryState("),
          Map.entry(ExecCommand.class, "queryState("),
          Map.entry(ShellCommand.class, "queryState("),
          Map.entry(ConnectCommand.class, "queryState("),
          Map.entry(SnapCommand.class, "queryState("),
          Map.entry(SnapsCommand.class, "queryState("),
          Map.entry(RestoreCommand.class, "queryState("),
          Map.entry(SnapDeleteCommand.class, "queryState("),
          Map.entry(SnapsPruneCommand.class, "requireNotTakenFor("),
          Map.entry(ProjectRenameCommand.class, "queryState("),
          Map.entry(ProjectResourcesSetCommand.class, "queryInfo("),
          Map.entry(ProjectConfigShowCommand.class, "queryInfo("),
          Map.entry(ProjectInstallAgentCommand.class, "queryState("),
          Map.entry(ProjectAddRepoCommand.class, "queryState("),
          Map.entry(ProjectAddServiceCommand.class, "queryState("),
          Map.entry(ProjectRemoveServiceCommand.class, "queryState("),
          Map.entry(ProjectDemoCommand.class, "queryState("),
          Map.entry(ProjectApplyCommand.class, "queryState("),
          Map.entry(ProjectDestroyCommand.class, "requireNotTakenFor("),
          Map.entry(ProjectCreateCommand.class, "requireNotTakenFor("),
          Map.entry(RunCommand.class, "queryState("),
          Map.entry(AgentAttachCommand.class, "queryState("),
          Map.entry(AgentLogCommand.class, "queryState("),
          Map.entry(AgentReportCommand.class, "queryState("),
          Map.entry(AgentStatusCommand.class, "queryState("),
          Map.entry(AgentSweepCommand.class, "queryState("),
          Map.entry(AgentWatchCommand.class, "queryState("));

  private static final String WORKSPACE =
      "{\"name\": \"acme-fix\", \"status\": \"Running\", \"type\": \"container\", \"config\":"
          + " {\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"fix\","
          + " \"user.sail.ready\": \"1\"}}";
  private static final String INCOMPLETE =
      "{\"name\": \"acme-half\", \"status\": \"Running\", \"type\": \"container\", \"config\":"
          + " {\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"half\"}}";
  private static final String TEMPLATE =
      "{\"name\": \"acme--tmpl\", \"status\": \"Stopped\", \"type\": \"container\","
          + " \"config\": {\"user.sail.template.of\": \"acme\"}}";
  private static final String PROJECT =
      "{\"name\": \"acme\", \"status\": \"Running\", \"type\": \"container\", \"config\": {}}";

  /** This box's Incus as a verb reads it: acme's container, two workspaces and its template. */
  private static ScriptedShellExecutor incus() {
    return new ScriptedShellExecutor()
        .onOk("incus list ^acme-fix$", "[" + WORKSPACE + "]")
        .onOk("incus list ^acme-half$", "[" + INCOMPLETE + "]")
        .onOk("incus list ^acme--tmpl$", "[" + TEMPLATE + "]")
        .onOk("incus list ^acme$", "[" + PROJECT + "]")
        .onOk(
            "incus list --format json",
            "[" + String.join(",", PROJECT, WORKSPACE, INCOMPLETE, TEMPLATE) + "]");
  }

  static Stream<Arguments> verbsOnAnInstanceThatIsNoProjectsContainer() {
    var workspace = "it is workspace fix of project acme.";
    var incomplete = "it is workspace half of project acme.";
    var template = "it is the template of project acme.";
    return Stream.of(
        Arguments.of((Verb) ShellCommand::new, List.of("acme-fix"), workspace),
        Arguments.of((Verb) ShellCommand::new, List.of("acme-half"), incomplete),
        Arguments.of((Verb) ShellCommand::new, List.of("acme--tmpl"), template),
        Arguments.of((Verb) ExecCommand::new, List.of("acme-fix", "true"), workspace),
        Arguments.of((Verb) ExecCommand::new, List.of("acme-half", "true"), incomplete),
        Arguments.of((Verb) ExecCommand::new, List.of("acme--tmpl", "true"), template),
        Arguments.of((Verb) UpCommand::new, List.of("acme--tmpl"), template),
        Arguments.of((Verb) UpCommand::new, List.of("acme-fix"), workspace),
        Arguments.of((Verb) DownCommand::new, List.of("acme-fix"), workspace),
        Arguments.of((Verb) DownCommand::new, List.of("acme--tmpl", "--dry-run"), template),
        Arguments.of((Verb) SwitchCommand::new, List.of("acme-fix"), workspace),
        Arguments.of((Verb) SnapCommand::new, List.of("acme--tmpl"), template),
        Arguments.of((Verb) SnapCommand::new, List.of("acme-fix", "mine"), workspace),
        Arguments.of((Verb) SnapDeleteCommand::new, List.of("acme--tmpl", "v1"), template),
        Arguments.of((Verb) SnapDeleteCommand::new, List.of("acme-fix", "mine"), workspace),
        Arguments.of((Verb) RestoreCommand::new, List.of("acme-fix"), workspace),
        Arguments.of((Verb) RestoreCommand::new, List.of("acme--tmpl", "v1"), template),
        Arguments.of((Verb) SnapsPruneCommand::new, List.of("acme-fix", "--keep", "0"), workspace),
        Arguments.of(
            (Verb) SnapsPruneCommand::new, List.of("acme--tmpl", "--older-than", "1d"), template),
        Arguments.of(
            (Verb) ProjectDestroyCommand::new, List.of("acme-fix", "--dry-run"), workspace),
        Arguments.of(
            (Verb) ProjectDestroyCommand::new, List.of("acme--tmpl", "--dry-run"), template));
  }

  /** A verb made over the box's host shells. */
  interface Verb extends Function<Function<Boolean, ShellExec>, Object> {}

  @ParameterizedTest
  @MethodSource("verbsOnAnInstanceThatIsNoProjectsContainer")
  void aVerbAddressingAWorkspaceOrTheTemplateByAProjectsNameIsRefusedHavingOnlyReadIncus(
      Verb verb, List<String> args, String what) {
    var incus = incus();

    var ran = CommandRun.of(verb.apply(dryRun -> incus), args.toArray(String[]::new));

    var name = args.getFirst();
    assertEquals(1, ran.exit(), ran.err());
    assertTrue(
        ran.err().contains("Project " + name + " cannot take instance " + name + ": " + what),
        ran.err());
    assertTrue(
        incus.invocations().stream().allMatch(command -> command.startsWith("incus list ")),
        String.valueOf(incus.invocations()));
  }

  @Test
  void anApplyOfAProjectNamedLikeAWorkspaceIsRefusedHavingOnlyReadIncus(@TempDir Path dir)
      throws IOException {
    Files.writeString(dir.resolve("host.yaml"), "pool: devpool\n");
    Files.createDirectories(SailPaths.apiSocketHostDir(dir));
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      Acting.system(
          () ->
              new ProjectStore(db)
                  .upsert(
                      TestDefinitions.yaml(
                          "name: acme-fix\nresources:\n  cpu: 2\n  memory: 4GB\n  disk: 50GB\n")));
    }
    var box =
        new ProjectApplyCommand.Box(
            dir.resolve("sail.db"), dir.resolve("host.yaml"), SailPaths.apiSocketHostDir(dir));
    var incus = incus();

    var ran =
        CommandRun.of(new ProjectApplyCommand(() -> box, dryRun -> incus), "acme-fix", "--yes");

    assertEquals(1, ran.exit(), ran.err());
    assertTrue(
        ran.err()
            .contains(
                "Project acme-fix cannot take instance acme-fix: it is workspace fix of project"
                    + " acme."),
        ran.err());
    assertEquals(List.of("incus list ^acme-fix$ --format json"), incus.invocations());
  }

  @Test
  void aDryRunReadsIncusForRealSoItNamesWhatTheRealRunWouldChange() {
    var observed = incus();
    var destroying = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var stopping = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var stoppingAll = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var switching = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    var destroy =
        CommandRun.of(
            new ProjectDestroyCommand(dryRun -> dryRun ? destroying : observed),
            "acme",
            "--dry-run");
    var stop =
        CommandRun.of(new DownCommand(dryRun -> dryRun ? stopping : observed), "acme", "--dry-run");
    var stopAll =
        CommandRun.of(new DownCommand(dryRun -> dryRun ? stoppingAll : observed), "--dry-run");

    assertEquals(0, destroy.exit(), destroy.err());
    assertEquals(
        List.of(
            "incus delete acme-fix --force",
            "incus delete acme-half --force",
            "incus delete acme--tmpl --force",
            "incus delete acme --force"),
        destroying.invocations().stream()
            .filter(command -> command.startsWith("incus delete"))
            .toList());
    assertEquals(0, stop.exit(), stop.err());
    assertEquals(List.of("incus stop acme"), stopping.invocations());
    assertEquals(0, stopAll.exit(), stopAll.err());
    assertEquals(
        List.of("incus stop acme", "incus stop acme-fix", "incus stop acme-half"),
        stoppingAll.invocations());
    var switched =
        CommandRun.of(
            new SwitchCommand(dryRun -> dryRun ? switching : observed),
            "acme",
            "--dry-run",
            "--json");
    assertEquals(0, switched.exit(), switched.err());
    assertTrue(switched.out().contains("\"name\": \"acme\""), switched.out());
    assertTrue(switched.out().contains("acme-fix"), switched.out());
    assertEquals(List.of(), switching.invocations());
    var narrating = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var told =
        CommandRun.of(
            new SwitchCommand(dryRun -> dryRun ? narrating : observed), "acme", "--dry-run");
    assertEquals(0, told.exit(), told.err());
    assertTrue(
        narrating.invocations().stream().allMatch(command -> command.startsWith("incus exec acme")),
        "the ports are asked through the dry shell: " + narrating.invocations());
    assertTrue(
        observed.invocations().stream().allMatch(command -> command.startsWith("incus list ")),
        "nothing but a listing runs for real: " + observed.invocations());
    assertTrue(
        observed.invocations().stream().allMatch(command -> command.startsWith("incus list ")),
        String.valueOf(observed.invocations()));
  }

  @Test
  void everyVerbThatAddressesAContainerByAProjectsNamePassesThroughTheGuard() throws IOException {
    var unguarded = new ArrayList<String>();
    for (var verb : VERBS.entrySet()) {
      var source =
          Files.readString(
              Path.of("src", "main", "java", "ai", "singlr", "sail", "commands")
                  .resolve(verb.getKey().getSimpleName() + ".java"));
      if (!source.contains(verb.getValue())) {
        unguarded.add(verb.getKey().getSimpleName());
      }
    }

    assertEquals(List.of(), unguarded);
  }

  @Test
  void everyVerbOfTheTableIsOneSailHas() {
    var reached = new ArrayList<Class<?>>();
    collect(new CommandLine(new Sail()), reached);

    for (var verb : VERBS.keySet()) {
      assertTrue(reached.contains(verb), verb.getSimpleName() + " is no verb of sail's");
    }
  }

  @Test
  void theFilesVerbAndTheApiDoorsPassThroughTheGuardToo() throws IOException {
    var root = Path.of("src", "main", "java", "ai", "singlr", "sail");
    assertTrue(
        Files.readString(root.resolve("commands").resolve("ContainerWorkspace.java"))
            .contains("queryState("));
    assertTrue(
        Files.readString(root.resolve("api").resolve("ProjectLoader.java"))
            .contains("queryState("));
  }

  private static void collect(CommandLine command, List<Class<?>> reached) {
    reached.add(command.getCommandSpec().userObject().getClass());
    for (var sub : command.getSubcommands().values()) {
      collect(sub, reached);
    }
  }
}
