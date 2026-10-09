/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagePromptsMigrationTest {

  private static final String WITH_SKILLS =
      """
      name: api
      agent:
        type: claude-code
        build_skill: acme-build
        review_pipeline:
          fix_skill: acme-fix
          max_iterations: 4
          stages:
            - name: security
              skill: acme-security
              categories: [security, injection]
              gate: all_clear
            - name: style
            - name: sign-off
              type: human
      """;

  private static final String WITH_CONTEXT =
      """
      name: web
      agent:
        type: codex
        methodology:
          verify: npm test
      agent_context:
        tech_stack: Node 24
        rules:
          ts:
            paths: ["**/*.ts"]
            body: strict
      """;

  private static final String CLEAN = "name: docs\nagent:\n  type: claude-code\n";

  @TempDir Path dir;
  private Sqlite db;
  private ProjectStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    store = new ProjectStore(db);
    Acting.system(
        () -> {
          store.upsert("api", WITH_SKILLS);
          store.upsert("web", WITH_CONTEXT);
          store.upsert("docs", CLEAN);
        });
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private DataMigration.Report apply() {
    return Acting.system(
        () ->
            new StagePromptsMigration()
                .apply(
                    db,
                    ProjectRegistry.loadFromDisk(dir.resolve("no-projects")),
                    DataMigration.Prompter.NON_INTERACTIVE));
  }

  private String definition(String name) {
    return store.findByName(name).orElseThrow().definition();
  }

  @Test
  void everyDefinitionWithADeletedKeyIsRewrittenWithoutItAsANewRevisionThatSyncs() {
    var apiBefore = store.latestRev("api");
    var docsBefore = store.latestRev("docs");

    var report = apply();

    assertEquals(2, report.applied());
    assertEquals(0, report.skipped());
    assertEquals(
        Map.of(
            "name",
            "api",
            "agent",
            Map.of(
                "type",
                "claude-code",
                "review_pipeline",
                Map.of(
                    "max_iterations",
                    4,
                    "stages",
                    List.of(
                        Map.of("name", "security", "gate", "all_clear"),
                        Map.of("name", "style"),
                        Map.of("name", "sign-off", "type", "human"))))),
        YamlUtil.parseMap(definition("api")));
    assertEquals(
        Map.of("name", "web", "agent", Map.of("type", "codex")),
        YamlUtil.parseMap(definition("web")));
    assertEquals(CLEAN, definition("docs"), "a clean definition is not touched");
    assertNotEquals(apiBefore, store.latestRev("api"), "the rewrite is a revision, so it syncs");
    assertEquals(docsBefore, store.latestRev("docs"));
    assertTrue(store.dirtyIds().containsAll(List.of("api", "web")), store.dirtyIds().toString());
    for (var name : List.of("api", "web", "docs")) {
      SailYaml.fromMap(YamlUtil.parseMap(definition(name)));
    }
  }

  @Test
  void theReportListsPerProjectWhatItDroppedAndWhereItBelongs() {
    var notes = apply().notes();

    assertEquals(
        List.of(
            "Project 'api': dropped agent.build_skill. "
                + SailYaml.DELETED_KEYS.get("agent.build_skill"),
            "Project 'api': dropped agent.review_pipeline.fix_skill. "
                + SailYaml.DELETED_KEYS.get("agent.review_pipeline.fix_skill"),
            "Project 'api': dropped agent.review_pipeline.stages[security].skill is no longer read:"
                + " a stage judges under its brief. Put what the skill said in the stage's brief"
                + " (agent.review_pipeline.stages[security].brief), or keep it as a project skill"
                + " (sail project skills add) the brief tells the stage to run.",
            "Project 'api': dropped agent.review_pipeline.stages[security].categories is no longer"
                + " read: what a stage focuses on is its brief. Say it in"
                + " agent.review_pipeline.stages[security].brief.",
            "Project 'web': dropped agent_context. " + SailYaml.DELETED_KEYS.get("agent_context"),
            "Project 'web': dropped agent.methodology. "
                + SailYaml.DELETED_KEYS.get("agent.methodology")),
        notes);
  }

  @Test
  void aSecondRunChangesNothing() {
    apply();
    var api = store.latestRev("api");
    var web = store.latestRev("web");

    var report = apply();

    assertEquals(new DataMigration.Report(0, 0, 0, List.of()), report);
    assertEquals(api, store.latestRev("api"));
    assertEquals(web, store.latestRev("web"));
  }

  @Test
  void aRowThatIsNotYamlIsLeftAloneAndReported() {
    db.execute("UPDATE projects SET definition = ? WHERE name = ?", "agent: [unterminated", "docs");

    var report = apply();

    assertEquals(2, report.applied());
    assertEquals(1, report.skipped());
    assertTrue(
        report.notes().stream().anyMatch(note -> note.startsWith("Left project 'docs' alone:")),
        report.notes().toString());
    assertEquals("agent: [unterminated", definition("docs"));
  }

  @Test
  void theMigrationIsRegisteredAndNeedsNothingOfTheBoxsFiles() {
    var migration =
        DataMigrations.ALL.stream()
            .filter(m -> m.name().equals(StagePromptsMigration.NAME))
            .findFirst()
            .orElseThrow();

    assertTrue(DataMigrations.databaseOnly().contains(migration));
  }
}
