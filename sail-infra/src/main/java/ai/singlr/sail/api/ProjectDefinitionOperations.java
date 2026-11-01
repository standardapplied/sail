/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ProjectStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A project's definition at the API: read whole, created whole, and written one section at a time,
 * each section tagged ({@link ProjectStore#tagOf}) so a client writes only over the section it
 * read.
 */
final class ProjectDefinitionOperations {

  private final ProjectStore projects;

  ProjectDefinitionOperations(ProjectStore projects) {
    this.projects = Objects.requireNonNull(projects, "projects");
  }

  /** Project {@code project}'s definition and each section's tag, or not found. */
  ProjectDefinitionResponse definition(String project) {
    return response(held(project));
  }

  /** Creates the project {@code document}, a definition document, names; refused when it exists. */
  ProjectDefinitionResponse create(Map<String, Object> document) {
    var definition = ProjectDefinitionJson.read(YamlUtil.dumpJson(document));
    if (!Actor.reader().scope().admits(definition.name())) {
      throw ProjectReader.absent(definition.name());
    }
    if (projects.findByName(definition.name()).isPresent()) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "A project named '" + definition.name() + "' already exists.",
          Remedy.of("Choose another name, or edit that project's definition."));
    }
    return response(projects.create(definition));
  }

  /**
   * Writes {@code value} as {@code section} of project {@code project} ({@code null} unsets it),
   * only over the section {@code ifMatch}, its tag, names when one is given.
   */
  ProjectDefinitionResponse writeSection(
      String project, String section, Object value, String ifMatch) {
    held(project);
    var named = Section.of(section);
    try {
      return response(
          projects.writeSection(
              project,
              named,
              value == null ? null : YamlUtil.dumpJsonValue(value),
              unquoted(ifMatch)));
    } catch (ProjectStore.StaleSection stale) {
      throw new ApiException(
          ErrorCode.PRECONDITION_FAILED,
          "The " + named.key() + " section of '" + project + "' changed since you read it.",
          Remedy.of("Read the definition again, then write the section."));
    }
  }

  private ProjectStore.ProjectRow held(String project) {
    if (!Actor.reader().scope().admits(project)) {
      throw ProjectReader.absent(project);
    }
    return projects.findByName(project).orElseThrow(() -> ProjectReader.absent(project));
  }

  private static ProjectDefinitionResponse response(ProjectStore.ProjectRow row) {
    var sections = ProjectDefinitionJson.sections(row.definition());
    var tags = new LinkedHashMap<String, String>();
    sections.forEach((key, json) -> tags.put(key, ProjectStore.tagOf(json)));
    return new ProjectDefinitionResponse(
        row.name(), row.document(), tags, row.updatedBy(), DateTimeUtils.format(row.updatedAt()));
  }

  /** The tag an {@code If-Match} names, without the quotes HTTP wraps it in; null for none. */
  private static String unquoted(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.strip())) {
      return null;
    }
    var tag = ifMatch.strip();
    return tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")
        ? tag.substring(1, tag.length() - 1)
        : tag;
  }
}
