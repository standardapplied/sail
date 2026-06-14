package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.GitSpecSync;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectApplier;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SpecIndexMigrator;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "migrate",
    description = "Bring existing project containers up to the current SAIL layout.",
    mixinStandardHelpOptions = true)
public final class ProjectMigrateCommand implements Runnable {

  private final Function<Boolean, ShellExec> shellFactory;

  public ProjectMigrateCommand() {
    this(ShellExecutor::new);
  }

  ProjectMigrateCommand(Function<Boolean, ShellExec> shellFactory) {
    this.shellFactory = shellFactory;
  }

  @Parameters(index = "0", arity = "0..1", description = "Project name.")
  private String name;

  @Option(names = "--all", description = "Migrate all managed SAIL projects.")
  private boolean all;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor for single-project migration.",
      defaultValue = "sail.yaml")
  private String file;

  @Option(names = "--no-start", description = "Do not start stopped projects automatically.")
  private boolean noStart;

  @Option(names = "--keep-index", description = "Keep specs/index.yaml after successful migration.")
  private boolean keepIndex;

  @Option(names = "--pull-specs", description = "Pull Git-backed specs before migration when safe.")
  private boolean pullSpecs;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var selectedProjects = selectedProjects();
    var shell = shellFactory.apply(dryRun);
    var migrator = new Migrator(shell, noStart, keepIndex, pullSpecs, json);
    var results = new ArrayList<ProjectResult>();
    for (var project : selectedProjects) {
      results.add(migrator.migrate(project, descriptorFile(project)));
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("action", "project_migrate");
      map.put("results", results.stream().map(ProjectResult::toMap).toList());
      map.put("summary", summary(results));
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    printHuman(results);
    if (results.stream().anyMatch(result -> "failed".equals(result.status()))) {
      throw new IllegalStateException("One or more project migrations failed.");
    }
  }

  private List<String> selectedProjects() throws Exception {
    if (all == (Strings.isNotBlank(name))) {
      throw new IllegalArgumentException("Specify exactly one project name or --all.");
    }
    if (Strings.isNotBlank(name)) {
      NameValidator.requireValidProjectName(name);
      importLegacyState(List.of(name));
      return List.of(name);
    }
    importLegacyState(List.of());
    var projects = managedProjects();
    if (projects.isEmpty()) {
      throw new IllegalStateException("No projects found.");
    }
    return projects;
  }

  private static List<String> managedProjects() throws Exception {
    var projectsDir = SailPaths.projectsDir();
    if (!Files.isDirectory(projectsDir)) {
      return List.of();
    }
    try (var paths = Files.list(projectsDir)) {
      return paths
          .filter(Files::isDirectory)
          .filter(path -> Files.isRegularFile(path.resolve(SailPaths.PROJECT_DESCRIPTOR)))
          .map(path -> path.getFileName().toString())
          .sorted(Comparator.naturalOrder())
          .toList();
    }
  }

  private static void importLegacyState(List<String> projects) throws Exception {
    importLegacyHostConfig();
    importLegacyProjects(
        legacyProjectsDir(),
        SailPaths.projectsDir(),
        projects,
        SailPaths.PROJECT_DESCRIPTOR,
        "sing.yaml");
  }

  private static void importLegacyHostConfig() throws Exception {
    var source = legacySailDir().resolve("host.yaml");
    var target = SailPaths.hostConfigPath();
    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.exists(target)) {
      return;
    }
    Files.createDirectories(target.getParent());
    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
  }

  static List<String> importLegacyProjects(
      Path legacyProjectsDir,
      Path projectsDir,
      List<String> requestedProjects,
      String descriptor,
      String legacyDescriptor)
      throws Exception {
    if (!Files.isDirectory(legacyProjectsDir, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    var requested = requestedProjects == null ? List.<String>of() : requestedProjects;
    var projects =
        requested.isEmpty() ? legacyProjects(legacyProjectsDir, legacyDescriptor) : requested;
    var imported = new ArrayList<String>();
    for (var project : projects) {
      NameValidator.requireValidProjectName(project);
      var legacyProjectDir = legacyProjectsDir.resolve(project);
      if (!Files.isDirectory(legacyProjectDir, LinkOption.NOFOLLOW_LINKS)
          || !Files.isRegularFile(
              legacyProjectDir.resolve(legacyDescriptor), LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      var projectDir = projectsDir.resolve(project);
      if (Files.isRegularFile(projectDir.resolve(descriptor), LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      copyLegacyProject(legacyProjectDir, projectDir, descriptor, legacyDescriptor);
      imported.add(project);
    }
    return imported.stream().sorted(Comparator.naturalOrder()).toList();
  }

  private static List<String> legacyProjects(Path legacyProjectsDir, String legacyDescriptor)
      throws Exception {
    try (var paths = Files.list(legacyProjectsDir)) {
      return paths
          .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .filter(
              path ->
                  Files.isRegularFile(path.resolve(legacyDescriptor), LinkOption.NOFOLLOW_LINKS))
          .map(path -> path.getFileName().toString())
          .sorted(Comparator.naturalOrder())
          .toList();
    }
  }

  private static void copyLegacyProject(
      Path legacyProjectDir, Path projectDir, String descriptor, String legacyDescriptor)
      throws Exception {
    try (var paths = Files.walk(legacyProjectDir)) {
      for (var source : paths.sorted(Comparator.naturalOrder()).toList()) {
        var relative = legacyProjectDir.relativize(source);
        var target =
            relative.toString().equals(legacyDescriptor)
                ? projectDir.resolve(descriptor)
                : projectDir.resolve(relative);
        if (Files.isSymbolicLink(source) || Files.exists(target)) {
          continue;
        }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
          Files.createDirectories(target);
        } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
          Files.createDirectories(target.getParent());
          Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  private static Path legacySailDir() {
    return Path.of(System.getProperty("user.home"), ".sing");
  }

  private static Path legacyProjectsDir() {
    return legacySailDir().resolve("projects");
  }

  private String descriptorFile(String project) {
    return all ? SailPaths.resolveSailYaml(project, file).toString() : file;
  }

  private static Map<String, Object> summary(List<ProjectResult> results) {
    var map = new LinkedHashMap<String, Object>();
    map.put("total", results.size());
    map.put("ok", results.stream().filter(result -> "ok".equals(result.status())).count());
    map.put("failed", results.stream().filter(result -> "failed".equals(result.status())).count());
    map.put("started", results.stream().filter(ProjectResult::started).count());
    map.put("specs_converted", results.stream().mapToInt(ProjectResult::specsConverted).sum());
    return map;
  }

  private static void printHuman(List<ProjectResult> results) {
    Banner.printBranding(System.out, Ansi.AUTO);
    System.out.println();
    for (var result : results) {
      var marker = "ok".equals(result.status()) ? "@|green \u2713|@" : "@|red \u2717|@";
      System.out.println(
          Ansi.AUTO.string("  " + marker + " @|bold " + result.name() + "|@ " + result.status()));
      if (result.error() != null) {
        System.out.println("    " + result.error());
      } else {
        System.out.println(
            "    context: "
                + result.contextFiles()
                + ", specs converted: "
                + result.specsConverted()
                + ", sync: "
                + result.specSyncState());
        if (result.indexRemoved()) {
          System.out.println("    removed legacy specs/index.yaml");
        }
      }
      for (var warning : result.warnings()) {
        System.out.println(Ansi.AUTO.string("    @|yellow \u26a0|@ " + warning));
      }
    }
  }

  private static final class Migrator {
    private final ShellExec shell;
    private final boolean noStart;
    private final boolean keepIndex;
    private final boolean pullSpecs;
    private final boolean json;

    private Migrator(
        ShellExec shell, boolean noStart, boolean keepIndex, boolean pullSpecs, boolean json) {
      this.shell = shell;
      this.noStart = noStart;
      this.keepIndex = keepIndex;
      this.pullSpecs = pullSpecs;
      this.json = json;
    }

    private ProjectResult migrate(String project, String file) {
      try {
        NameValidator.requireValidProjectName(project);
        var warnings = new ArrayList<String>();
        var manager = new ContainerManager(shell);
        var started = ensureRunning(project, manager);
        var singYamlPath = SailPaths.resolveSailYaml(project, file);
        if (!Files.exists(singYamlPath)) {
          throw new IllegalStateException("Project descriptor not found: " + singYamlPath);
        }
        var config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));
        if (config.agent() == null || config.agent().specsDir() == null) {
          throw new IllegalStateException(
              "No specs_dir configured in agent block. Add it to sail.yaml.");
        }

        var specsDir = "/home/" + config.sshUser() + "/workspace/" + config.agent().specsDir();
        var syncState = syncBeforeMigration(project, specsDir, warnings);
        var output = json ? new PrintStream(OutputStream.nullOutputStream()) : System.out;
        var applier = new ProjectApplier(shell, output);
        var context = applier.applyAgentContext(project, config);
        applier.applySpecsScaffold(project, config);
        var index = new SpecIndexMigrator(shell).migrate(project, specsDir, keepIndex);
        warnings.addAll(index.warnings());
        syncState = syncState(project, specsDir, warnings);
        return ProjectResult.ok(
            project,
            started,
            context.added(),
            index.converted(),
            index.skipped(),
            index.indexRemoved(),
            syncState,
            warnings);
      } catch (Exception e) {
        return ProjectResult.failed(project, e.getMessage());
      }
    }

    private boolean ensureRunning(String project, ContainerManager manager) throws Exception {
      return switch (manager.queryState(project)) {
        case ContainerState.Running ignored -> false;
        case ContainerState.Stopped ignored -> {
          if (noStart) {
            throw new IllegalStateException(
                "Project is stopped. Start it with: sail project start " + project);
          }
          manager.start(project);
          yield true;
        }
        case ContainerState.NotCreated ignored ->
            throw new IllegalStateException(
                "Project does not exist. Run 'sail project create' first.");
        case ContainerState.Error error ->
            throw new IllegalStateException("Container error: " + error.message());
      };
    }

    private String syncBeforeMigration(String project, String specsDir, List<String> warnings) {
      if (!pullSpecs) {
        return syncState(project, specsDir, warnings);
      }
      try {
        var sync = sync(project, specsDir);
        var status = sync.status();
        if (!status.repository()) {
          return status.state().name().toLowerCase();
        }
        switch (status.state()) {
          case CLEAN, BEHIND, AHEAD -> {
            sync.pull();
            return sync.status().state().name().toLowerCase();
          }
          case DIRTY, DIVERGED, CONFLICTED, NO_UPSTREAM, NOT_A_REPOSITORY, ERROR ->
              warnings.add("Skipped spec pull: " + status.message());
        }
        return status.state().name().toLowerCase();
      } catch (Exception e) {
        warnings.add("Spec pull failed: " + e.getMessage());
        return "error";
      }
    }

    private String syncState(String project, String specsDir, List<String> warnings) {
      try {
        return sync(project, specsDir).status().state().name().toLowerCase();
      } catch (Exception e) {
        warnings.add("Spec sync status failed: " + e.getMessage());
        return "error";
      }
    }

    private GitSpecSync sync(String project, String specsDir) {
      return new GitSpecSync(
          shell, ContainerExec.asDevUser(project, List.of("git", "-C", specsDir)));
    }
  }

  private record ProjectResult(
      String name,
      String status,
      boolean started,
      int contextFiles,
      int specsConverted,
      int specsSkipped,
      boolean indexRemoved,
      String specSyncState,
      List<String> warnings,
      String error) {

    private ProjectResult {
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    private static ProjectResult ok(
        String name,
        boolean started,
        int contextFiles,
        int specsConverted,
        int specsSkipped,
        boolean indexRemoved,
        String syncState,
        List<String> warnings) {
      return new ProjectResult(
          name,
          "ok",
          started,
          contextFiles,
          specsConverted,
          specsSkipped,
          indexRemoved,
          syncState,
          warnings,
          null);
    }

    private static ProjectResult failed(String name, String error) {
      return new ProjectResult(name, "failed", false, 0, 0, 0, false, "unknown", List.of(), error);
    }

    private Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("status", status);
      map.put("started", started);
      map.put("context_files", contextFiles);
      map.put("specs_converted", specsConverted);
      map.put("specs_skipped", specsSkipped);
      map.put("index_removed", indexRemoved);
      map.put("spec_sync_state", specSyncState);
      if (!warnings.isEmpty()) {
        map.put("warnings", warnings);
      }
      if (error != null) {
        map.put("error", error);
      }
      return map;
    }
  }
}
