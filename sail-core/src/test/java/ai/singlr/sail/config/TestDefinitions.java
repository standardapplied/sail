/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.Map;

/** Project definitions for tests, read as the catalog reads one. */
public final class TestDefinitions {

  private TestDefinitions() {}

  /** The definition of {@code name} with no sections set. */
  public static ProjectDefinition named(String name) {
    return yaml("name: " + name + "\n");
  }

  /**
   * The definition of {@code name} whose description is {@code version}: two versions are two
   * definitions that differ in one section.
   */
  public static ProjectDefinition described(String name, String version) {
    return new ProjectDefinition(
        name, version, null, null, null, null, null, null, null, null, null);
  }

  /** The definition {@code map}, a parsed document, holds, checked whole. */
  public static ProjectDefinition map(Map<String, Object> map) {
    return ProjectDefinition.fromMap(map);
  }

  /** The pipeline section {@code map} holds. */
  public static ReviewPipelineConfig pipeline(Map<String, Object> map) {
    return ReviewPipelineConfig.fromMap(map);
  }

  /** The definition {@code yaml}, a document in YAML, holds, checked whole. */
  public static ProjectDefinition yaml(String yaml) {
    return ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));
  }
}
