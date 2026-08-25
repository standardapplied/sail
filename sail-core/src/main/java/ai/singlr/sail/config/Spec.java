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
 * A single spec representing one unit of work. Specs are stored as rows in the control-plane
 * database — the shared, synced source of truth — and managed through the {@code spec} CLI; this
 * record is their in-memory form, with the detailed body held separately.
 *
 * @param id unique identifier
 * @param project client project this spec belongs to
 * @param title short human-readable title
 * @param status lifecycle state (see {@link SpecStatus})
 * @param assignee engineer responsible (nullable, matches git identity)
 * @param dependsOn IDs of specs that must be done first
 * @param repos repository paths this spec should branch and work in
 * @param agent agent CLI this spec should run with (nullable)
 * @param model model this spec should run with (nullable)
 * @param reasoningEffort model reasoning effort for this spec (nullable)
 * @param branch git branch for this spec's work (nullable)
 * @param priority dispatch ordering weight; 0 is the default
 * @param createdBy FDE handle that created the spec (nullable)
 * @param createdAt creation instant as recorded by the store (nullable)
 * @param updatedAt last-edit instant as recorded by the store (nullable)
 * @param updatedBy FDE handle of the last editor (nullable)
 * @param roomId the room this spec lives in — its own id for identity rooms (nullable)
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
    String branch,
    int priority,
    String createdBy,
    String createdAt,
    String updatedAt,
    String updatedBy,
    String roomId) {

  private static final Pattern MODEL_PATTERN = Pattern.compile("[A-Za-z0-9._:/-]+");
  private static final Set<String> REASONING_EFFORTS =
      Set.of("none", "low", "medium", "high", "xhigh");

  public Spec {
    project = Objects.requireNonNull(project, "spec.project is required");
  }

  /** The creation shape — a spec's definition without attribution or room linkage. */
  public Spec(
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
    this(
        id,
        project,
        title,
        status,
        assignee,
        dependsOn,
        repos,
        agent,
        model,
        reasoningEffort,
        branch,
        0,
        null,
        null,
        null,
        null,
        null);
  }

  /** This spec with {@code status} replaced — the single-field copy the status surfaces use. */
  public Spec withStatus(SpecStatus status) {
    return new Spec(
        id,
        project,
        title,
        status,
        assignee,
        dependsOn,
        repos,
        agent,
        model,
        reasoningEffort,
        branch,
        priority,
        createdBy,
        createdAt,
        updatedAt,
        updatedBy,
        roomId);
  }

  /** This spec with {@code repos} replaced — the dispatch-resolution copy. */
  public Spec withRepos(List<String> repos) {
    return new Spec(
        id,
        project,
        title,
        status,
        assignee,
        dependsOn,
        repos,
        agent,
        model,
        reasoningEffort,
        branch,
        priority,
        createdBy,
        createdAt,
        updatedAt,
        updatedBy,
        roomId);
  }

  @SuppressWarnings("unchecked")
  public static Spec fromMap(Map<String, Object> map) {
    var id = (String) map.get("id");
    if (Strings.isBlank(id)) {
      throw new IllegalArgumentException("spec.id is required");
    }
    NameValidator.requireValidSpecId(id);
    var project = Objects.requireNonNull((String) map.get("project"), "spec.project is required");
    var title = Objects.requireNonNullElse((String) map.get("title"), "");
    var statusRaw = (String) map.get("status");
    var status = statusRaw == null ? SpecStatus.PENDING : SpecStatus.fromWire(statusRaw);
    var assignee = (String) map.get("assignee");
    var dependsOn = (List<String>) map.get("depends_on");
    var repos = reposFromMap(map);
    var agent = validatedAgent((String) map.get("agent"));
    var model = validatedModel((String) map.get("model"));
    var reasoningEffort = validatedReasoningEffort((String) map.get("reasoning_effort"));
    var branch = (String) map.get("branch");
    var priority = map.get("priority");
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
        branch,
        priority instanceof Number n ? n.intValue() : 0,
        (String) map.get("created_by"),
        (String) map.get("created_at"),
        (String) map.get("updated_at"),
        (String) map.get("updated_by"),
        (String) map.get("room_id"));
  }

  /**
   * The definition-shaped projection — the sail.yaml face of a spec. Deliberately narrower than the
   * record: priority, attribution, and the room link are store-owned state, not definition, so they
   * never appear here.
   */
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("id", id);
    map.put("project", project);
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
