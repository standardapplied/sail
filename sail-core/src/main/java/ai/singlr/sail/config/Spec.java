/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.NameValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A single spec representing one unit of work. Each spec lives in its own directory inside the
 * {@code specs/} folder, containing a {@code spec.yaml} (this record) and optionally a {@code
 * spec.md} with the detailed description.
 *
 * @param id directory name and unique identifier
 * @param project client project this spec belongs to (nullable when loaded from legacy file-based
 *     specs that pre-date the project column; control-plane callers must always supply it)
 * @param title short human-readable title
 * @param status lifecycle state (see {@link SpecStatus})
 * @param assignee engineer responsible (nullable, matches git identity)
 * @param dependsOn IDs of specs that must be done first
 * @param repos repository paths this spec should branch and work in
 * @param agent agent CLI this spec should run with (nullable)
 * @param model model this spec should run with (nullable)
 * @param reasoningEffort model reasoning effort for this spec (nullable)
 * @param branch git branch for this spec's work (nullable)
 */
public record Spec(
    String id,
    String project,
    String title,
    SpecStatus status,
    String assignee,
    List<String> dependsOn,
    List<String> repos,
    String agent,
    String model,
    String reasoningEffort,
    String branch) {

  private static final Pattern MODEL_PATTERN = Pattern.compile("[A-Za-z0-9._:/-]+");
  private static final Set<String> REASONING_EFFORTS =
      Set.of("none", "low", "medium", "high", "xhigh");

  public Spec(
      String id,
      String title,
      SpecStatus status,
      String assignee,
      List<String> dependsOn,
      String branch) {
    this(id, null, title, status, assignee, dependsOn, List.of(), null, null, null, branch);
  }

  public Spec(
      String id,
      String title,
      SpecStatus status,
      String assignee,
      List<String> dependsOn,
      List<String> repos,
      String branch) {
    this(id, null, title, status, assignee, dependsOn, repos, null, null, null, branch);
  }

  public Spec(
      String id,
      String title,
      SpecStatus status,
      String assignee,
      List<String> dependsOn,
      List<String> repos,
      String agent,
      String branch) {
    this(id, null, title, status, assignee, dependsOn, repos, agent, null, null, branch);
  }

  @SuppressWarnings("unchecked")
  public static Spec fromMap(Map<String, Object> map) {
    var id = (String) map.get("id");
    if (Strings.isBlank(id)) {
      throw new IllegalArgumentException("spec.id is required");
    }
    NameValidator.requireValidSpecId(id);
    var project = (String) map.get("project");
    var title = Objects.requireNonNullElse((String) map.get("title"), "");
    var statusRaw = (String) map.get("status");
    var status = statusRaw == null ? SpecStatus.PENDING : SpecStatus.fromLegacy(statusRaw);
    var assignee = (String) map.get("assignee");
    var dependsOn = (List<String>) map.get("depends_on");
    var repos = reposFromMap(map);
    var agent = validatedAgent((String) map.get("agent"));
    var model = validatedModel((String) map.get("model"));
    var reasoningEffort = validatedReasoningEffort((String) map.get("reasoning_effort"));
    var branch = (String) map.get("branch");
    return new Spec(
        id,
        project,
        title,
        status,
        assignee,
        dependsOn != null ? List.copyOf(dependsOn) : List.of(),
        repos,
        agent,
        model,
        reasoningEffort,
        branch);
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("id", id);
    if (project != null) {
      map.put("project", project);
    }
    if (!title.isBlank()) {
      map.put("title", title);
    }
    map.put("status", status.wire());
    if (assignee != null) {
      map.put("assignee", assignee);
    }
    if (!dependsOn.isEmpty()) {
      map.put("depends_on", dependsOn);
    }
    if (repos.size() == 1) {
      map.put("repo", repos.getFirst());
    } else if (!repos.isEmpty()) {
      map.put("repos", repos);
    }
    if (agent != null) {
      map.put("agent", agent);
    }
    if (model != null) {
      map.put("model", model);
    }
    if (reasoningEffort != null) {
      map.put("reasoning_effort", reasoningEffort);
    }
    if (branch != null) {
      map.put("branch", branch);
    }
    return map;
  }

  private static List<String> reposFromMap(Map<String, Object> map) {
    var repo = (String) map.get("repo");
    var repos = (List<String>) map.get("repos");
    if (repo != null && repos != null) {
      throw new IllegalArgumentException("spec may define repo or repos, not both");
    }
    if (repo != null) {
      return validatedRepos(List.of(repo));
    }
    if (repos != null) {
      return validatedRepos(repos);
    }
    return List.of();
  }

  private static List<String> validatedRepos(List<String> repos) {
    repos.forEach(repo -> NameValidator.requireSafePath(repo, "spec.repo"));
    return List.copyOf(repos);
  }

  private static String validatedAgent(String agent) {
    if (Strings.isBlank(agent)) {
      return null;
    }
    AgentCli.fromYamlName(agent);
    return agent;
  }

  /** Validates a model id (or returns null when blank). Throws on shell-unsafe values. */
  public static String validatedModel(String model) {
    if (Strings.isBlank(model)) {
      return null;
    }
    if (!MODEL_PATTERN.matcher(model).matches()) {
      throw new IllegalArgumentException(
          "Invalid spec.model: '" + model + "'. Use a model id without spaces or shell syntax.");
    }
    return model;
  }

  /** Validates a reasoning-effort value (or returns null when blank). Throws if not allowed. */
  public static String validatedReasoningEffort(String reasoningEffort) {
    if (Strings.isBlank(reasoningEffort)) {
      return null;
    }
    if (!REASONING_EFFORTS.contains(reasoningEffort)) {
      throw new IllegalArgumentException(
          "Invalid spec.reasoning_effort: '"
              + reasoningEffort
              + "'. Must be one of: "
              + String.join(", ", REASONING_EFFORTS));
    }
    return reasoningEffort;
  }
}
