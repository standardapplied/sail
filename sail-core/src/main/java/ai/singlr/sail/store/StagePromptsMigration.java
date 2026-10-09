/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rewrites, once, every definition this box's catalog holds without the keys sail no longer reads
 * ({@link SailYaml#DELETED_KEYS}, and a stage's {@code skill} and {@code categories}), through
 * {@link ProjectStore#upsert} so the rewrite is journalled and syncs. It lists, per project, what
 * it dropped and where the text now belongs. A definition that still sets one of them afterwards —
 * a revision that arrives by sync from a box that has not run this, say — is refused when it is
 * read ({@code SailYaml.fromMap}), naming the key; the refusal is for what comes back after this
 * has run. A row that is not YAML is left alone and reported.
 */
public final class StagePromptsMigration implements DataMigration {

  public static final String NAME = "stages-are-prompts-v1";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter) {
    var store = new ProjectStore(db);
    var applied = 0;
    var skipped = 0;
    var notes = new ArrayList<String>();
    for (var row : store.list()) {
      Map<String, Object> map;
      try {
        map = YamlUtil.parseMap(row.definition());
      } catch (RuntimeException notYaml) {
        skipped++;
        notes.add(
            "Left project '"
                + row.name()
                + "' alone: its definition is not YAML ("
                + notYaml.getMessage()
                + ")");
        continue;
      }
      var dropped = strip(map);
      if (dropped.isEmpty()) {
        continue;
      }
      store.upsert(row.name(), YamlUtil.dumpToString(map));
      applied++;
      for (var sentence : dropped) {
        notes.add("Project '" + row.name() + "': dropped " + sentence);
      }
    }
    return new Report(applied, 0, skipped, notes);
  }

  /** Removes every deleted key from {@code map}, and says for each where its text belongs. */
  @SuppressWarnings("unchecked")
  static List<String> strip(Map<String, Object> map) {
    var dropped = new ArrayList<String>();
    if (map.remove("agent_context") != null) {
      dropped.add("agent_context. " + SailYaml.DELETED_KEYS.get("agent_context"));
    }
    if (!(map.get("agent") instanceof Map<?, ?> agentRaw)) {
      return dropped;
    }
    var agent = (Map<String, Object>) agentRaw;
    for (var key : List.of("methodology", "build_skill")) {
      if (agent.remove(key) != null) {
        dropped.add("agent." + key + ". " + SailYaml.DELETED_KEYS.get("agent." + key));
      }
    }
    if (!(agent.get("review_pipeline") instanceof Map<?, ?> pipelineRaw)) {
      return dropped;
    }
    var pipeline = (Map<String, Object>) pipelineRaw;
    if (pipeline.remove("fix_skill") != null) {
      dropped.add(
          "agent.review_pipeline.fix_skill. "
              + SailYaml.DELETED_KEYS.get("agent.review_pipeline.fix_skill"));
    }
    if (!(pipeline.get("stages") instanceof List<?> stages)) {
      return dropped;
    }
    for (var stageRaw : stages) {
      if (!(stageRaw instanceof Map<?, ?> stage)) {
        continue;
      }
      var named = (Map<String, Object>) stage;
      var name = String.valueOf(named.getOrDefault("name", "?"));
      for (var key : List.of("skill", "categories")) {
        if (named.remove(key) != null) {
          dropped.add(ReviewPipelineConfig.deletedStageKey(name, key));
        }
      }
    }
    return dropped;
  }
}
