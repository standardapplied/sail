package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ProjectMigrateCommandTest {

  @Test
  void projectHelpListsMigrate() {
    var command = new CommandLine(new Sail());
    var out = new StringWriter();
    command.setOut(new PrintWriter(out));

    var exitCode = command.execute("project", "--help");

    assertEquals(0, exitCode);
    assertTrue(out.toString().contains("migrate"));
  }

  @Test
  void migrateHelpShowsFleetOptions() {
    var command = new CommandLine(new Sail());
    var out = new StringWriter();
    command.setOut(new PrintWriter(out));

    var exitCode = command.execute("project", "migrate", "--help");

    assertEquals(0, exitCode);
    var help = out.toString();
    assertTrue(help.contains("--all"));
    assertTrue(help.contains("--pull-specs"));
    assertTrue(help.contains("--keep-index"));
    assertTrue(help.contains("--json"));
  }

  @Test
  void migrateRequiresSingleProjectOrAll() {
    var command = new CommandLine(new Sail());

    assertNotEquals(0, command.execute("project", "migrate"));
    assertNotEquals(0, command.execute("project", "migrate", "acme", "--all"));
  }

  @Test
  void importsLegacySingProjectDescriptors(@TempDir Path tempDir) throws Exception {
    var legacyProjects = tempDir.resolve(".sing/projects");
    var sailProjects = tempDir.resolve(".sail/projects");
    var legacyProject = legacyProjects.resolve("manatee");
    Files.createDirectories(legacyProject);
    Files.writeString(legacyProject.resolve("sing.yaml"), "name: manatee\n");
    Files.writeString(legacyProject.resolve("provision-state.yaml"), "ok: true\n");

    var imported =
        ProjectMigrateCommand.importLegacyProjects(
            legacyProjects, sailProjects, List.of(), "sail.yaml", "sing.yaml");

    assertEquals(List.of("manatee"), imported);
    assertEquals("name: manatee\n", Files.readString(sailProjects.resolve("manatee/sail.yaml")));
    assertEquals(
        "ok: true\n", Files.readString(sailProjects.resolve("manatee/provision-state.yaml")));
    assertFalse(Files.exists(sailProjects.resolve("manatee/sing.yaml")));
  }

  @Test
  void legacyImportDoesNotOverwriteCanonicalProject(@TempDir Path tempDir) throws Exception {
    var legacyProjects = tempDir.resolve(".sing/projects");
    var sailProjects = tempDir.resolve(".sail/projects");
    Files.createDirectories(legacyProjects.resolve("manatee"));
    Files.createDirectories(sailProjects.resolve("manatee"));
    Files.writeString(legacyProjects.resolve("manatee/sing.yaml"), "name: old\n");
    Files.writeString(sailProjects.resolve("manatee/sail.yaml"), "name: new\n");

    var imported =
        ProjectMigrateCommand.importLegacyProjects(
            legacyProjects, sailProjects, List.of("manatee"), "sail.yaml", "sing.yaml");

    assertEquals(List.of(), imported);
    assertEquals("name: new\n", Files.readString(sailProjects.resolve("manatee/sail.yaml")));
  }
}
