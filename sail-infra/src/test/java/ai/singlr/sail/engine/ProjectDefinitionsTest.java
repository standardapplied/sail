/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectDefinitionsTest {

  @TempDir Path dir;
  private Sqlite db;
  private ProjectStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new ProjectStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void resolvePrefersTheCatalogOverTheCanonicalFile() throws Exception {
    store.upsert("acme", "from-db", "uday");
    var canonical = dir.resolve("acme.yaml");
    Files.writeString(canonical, "from-file");

    assertEquals(
        "from-db", ProjectDefinitions.resolve(store, "acme", null, canonical).orElseThrow());
  }

  @Test
  void resolveFallsBackToTheCanonicalFileWhenNotCatalogued() throws Exception {
    var canonical = dir.resolve("acme.yaml");
    Files.writeString(canonical, "only-on-disk");

    assertEquals(
        "only-on-disk", ProjectDefinitions.resolve(store, "acme", null, canonical).orElseThrow());
  }

  @Test
  void resolveLetsAnExplicitFileOverrideTheCatalog() throws Exception {
    store.upsert("acme", "from-db", "uday");
    var explicit = dir.resolve("override.yaml");
    Files.writeString(explicit, "from-explicit");
    var canonical = dir.resolve("acme.yaml");

    assertEquals(
        "from-explicit",
        ProjectDefinitions.resolve(store, "acme", explicit, canonical).orElseThrow());
  }

  @Test
  void resolveIsEmptyWhenNoSourceHasTheProject() {
    assertTrue(
        ProjectDefinitions.resolve(store, "ghost", null, dir.resolve("absent.yaml")).isEmpty());
  }

  @Test
  void explicitFileTreatsTheBareDefaultAsDatabaseFirst() {
    assertEquals(null, ProjectDefinitions.explicitFile("sail.yaml"));
    assertEquals(null, ProjectDefinitions.explicitFile(null));
    assertEquals(Path.of("custom.yaml"), ProjectDefinitions.explicitFile("custom.yaml"));
  }

  @Test
  void resolveForProvisioningFillsPlaceholdersFromTheLocalBox() throws Exception {
    var workstationKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILDpT0mMcK mady@box";
    var keyPath = dir.resolve("workstation_key.pub");
    Files.writeString(keyPath, workstationKey + "\n");
    var identity = new LocalIdentity(gitConfig("Mady M", "mady@example.com"), keyPath);

    var config =
        ProjectDefinitions.resolveForProvisioning(
            "name: acme\n"
                + "resources:\n  cpu: 2\n  memory: 8GB\n  disk: 50GB\n"
                + "git:\n  name: ${GIT_NAME}\n  email: ${GIT_EMAIL}\n"
                + "ssh:\n  user: dev\n  authorized_keys:\n    - ${SSH_PUBLIC_KEY}\n",
            identity);

    assertEquals("Mady M", config.git().name());
    assertEquals("mady@example.com", config.git().email());
    assertEquals(workstationKey, config.ssh().authorizedKeys().getFirst());
  }

  @Test
  void resolveForProvisioningNeedsNoIdentityWhenThereAreNoPlaceholders() {
    var noIdentity = new LocalIdentity(gitConfig("", ""), dir.resolve("missing.pub"));
    var config =
        ProjectDefinitions.resolveForProvisioning(
            "name: acme\nresources:\n  cpu: 2\n  memory: 8GB\n  disk: 50GB\n", noIdentity);
    assertEquals("acme", config.name());
  }

  @Test
  void resolveForProvisioningTakesValuesFromACallerSuppliedSource() {
    var config =
        ProjectDefinitions.resolveForProvisioning(
            "name: acme\n"
                + "resources:\n  cpu: 2\n  memory: 8GB\n  disk: 50GB\n"
                + "git:\n  name: ${GIT_NAME}\n  email: ${GIT_EMAIL}\n",
            placeholder ->
                "GIT_NAME".equals(placeholder) ? "Prompted Person" : "typed@example.com");

    assertEquals("Prompted Person", config.git().name());
    assertEquals("typed@example.com", config.git().email());
  }

  private static ShellExec gitConfig(String name, String email) {
    var values = java.util.Map.of("user.name", name, "user.email", email);
    return new ShellExec() {
      @Override
      public Result exec(java.util.List<String> command) {
        return new Result(0, values.getOrDefault(command.getLast(), ""), "");
      }

      @Override
      public Result exec(java.util.List<String> command, Path workDir, java.time.Duration timeout) {
        return exec(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }
}
