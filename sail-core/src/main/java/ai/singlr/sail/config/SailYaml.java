/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.StageSkill;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Root model for {@code sail.yaml} project descriptor. */
public record SailYaml(
    String name,
    String description,
    Resources resources,
    String image,
    List<String> packages,
    Runtimes runtimes,
    Git git,
    List<Repo> repos,
    Map<String, Service> services,
    Map<String, Process> processes,
    Agent agent,
    AgentContext agentContext,
    Ssh ssh) {

  /** The skills sail generates into every harness's skills folder under these names. */
  private static final Set<String> GENERATED_SKILLS = Set.of("spec", "spec-board", "verify");

  @SuppressWarnings("unchecked")
  public static SailYaml fromMap(Map<String, Object> map) {
    var name = (String) map.get("name");
    var resourcesRaw = (Map<String, Object>) map.get("resources");
    var runtimesRaw = (Map<String, Object>) map.get("runtimes");
    var gitRaw = (Map<String, Object>) map.get("git");
    var reposRaw = (List<Map<String, Object>>) map.get("repos");
    var servicesRaw = (Map<String, Object>) map.get("services");
    var processesRaw = (Map<String, Object>) map.get("processes");
    var agentRaw = (Map<String, Object>) map.get("agent");
    var agentCtxRaw = (Map<String, Object>) map.get("agent_context");
    var sshRaw = (Map<String, Object>) map.get("ssh");

    var agent = agentRaw != null ? Agent.fromMap(agentRaw, descriptor(name)) : null;
    var agentContext = agentCtxRaw != null ? AgentContext.fromMap(agentCtxRaw) : null;
    requireStageSkillsOfTheirOwn(agent, agentContext);
    return new SailYaml(
        name,
        (String) map.get("description"),
        resourcesRaw != null ? Resources.fromMap(resourcesRaw) : null,
        (String) map.get("image"),
        (List<String>) map.get("packages"),
        runtimesRaw != null ? Runtimes.fromMap(runtimesRaw) : null,
        gitRaw != null ? Git.fromMap(gitRaw) : null,
        reposRaw != null ? reposRaw.stream().map(Repo::fromMap).toList() : null,
        servicesRaw != null
            ? servicesRaw.entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Service.fromMap((Map<String, Object>) e.getValue()),
                        (a, b) -> a,
                        LinkedHashMap::new))
            : null,
        processesRaw != null
            ? processesRaw.entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Process.fromMap((Map<String, Object>) e.getValue()),
                        (a, b) -> a,
                        LinkedHashMap::new))
            : null,
        agent,
        agentContext,
        sshRaw != null ? Ssh.fromMap(sshRaw) : null);
  }

  /**
   * A stage's skill is installed as a folder named for it, beside the skills sail generates: the
   * methodology's, the spec board's, and on Codex one per {@code agent_context.rules} entry. A
   * stage skill under one of those names would replace that folder, so the definition is refused.
   * This is the one place that holds both the agent block and the rules.
   */
  private static void requireStageSkillsOfTheirOwn(Agent agent, AgentContext agentContext) {
    if (agent == null) {
      return;
    }
    var taken = new HashSet<>(GENERATED_SKILLS);
    if (agentContext != null && agentContext.rules() != null) {
      agentContext.rules().forEach(rule -> taken.add(rule.name()));
    }
    requireOwnName(Agent.BUILD_SKILL_KEY, agent.buildSkill(), taken);
    var pipeline = agent.reviewPipeline();
    if (pipeline == null) {
      return;
    }
    requireOwnName(ReviewPipelineConfig.FIX_SKILL_KEY, pipeline.fixSkill(), taken);
    for (var stage : pipeline.agentStages()) {
      requireOwnName(ReviewPipelineConfig.stageSkillKey(stage.name()), stage.skill(), taken);
    }
  }

  private static void requireOwnName(String key, String skill, Set<String> taken) {
    if (taken.contains(skill)) {
      throw new IllegalArgumentException(
          key
              + " '"
              + skill
              + "' is the name of a skill sail installs itself (spec, spec-board, verify, and one"
              + " per agent_context.rules entry); give the stage's skill or the rule another name.");
    }
  }

  private static String descriptor(String name) {
    return Objects.requireNonNullElse(name, "project") + "/sail.yaml";
  }

  /** Converts this config to a map suitable for YAML serialization. */
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", name);
    if (description != null) map.put("description", description);
    if (resources != null) map.put("resources", resources.toMap());
    if (image != null) map.put("image", image);
    if (packages != null) map.put("packages", packages);
    if (runtimes != null) map.put("runtimes", runtimes.toMap());
    if (git != null) map.put("git", git.toMap());
    if (repos != null) {
      map.put("repos", repos.stream().map(Repo::toMap).toList());
    }
    if (services != null) {
      var svcs = new LinkedHashMap<String, Object>();
      for (var entry : services.entrySet()) {
        svcs.put(entry.getKey(), entry.getValue().toMap());
      }
      map.put("services", svcs);
    }
    if (processes != null) {
      var procs = new LinkedHashMap<String, Object>();
      for (var entry : processes.entrySet()) {
        procs.put(entry.getKey(), entry.getValue().toMap());
      }
      map.put("processes", procs);
    }
    if (agent != null) map.put("agent", agent.toMap());
    if (agentContext != null) map.put("agent_context", agentContext.toMap());
    if (ssh != null) map.put("ssh", ssh.toMap());
    return map;
  }

  public record Resources(int cpu, String memory, String disk) {
    public static Resources fromMap(Map<String, Object> map) {
      var cpu = map.get("cpu");
      if (!(cpu instanceof Number)) {
        throw new IllegalArgumentException("resources.cpu is required and must be a number");
      }
      var memory = map.get("memory");
      if (memory == null) {
        throw new IllegalArgumentException("resources.memory is required (e.g. \"8GB\")");
      }
      var disk = map.get("disk");
      if (disk == null) {
        throw new IllegalArgumentException("resources.disk is required (e.g. \"50GB\")");
      }
      return new Resources(((Number) cpu).intValue(), normalizeSize(memory), normalizeSize(disk));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("cpu", cpu);
      map.put("memory", memory);
      map.put("disk", disk);
      return map;
    }
  }

  public record Runtimes(int jdk, String node, String maven) {
    public static Runtimes fromMap(Map<String, Object> map) {
      var jdk = map.get("jdk");
      var nodeVal = map.get("node");
      var node = toVersionString(nodeVal);
      var maven = toVersionString(map.get("maven"));
      if (node != null) {
        NameValidator.requireValidVersion(node, "runtimes.node");
      }
      if (maven != null) {
        NameValidator.requireValidVersion(maven, "runtimes.maven");
      }
      return new Runtimes(jdk instanceof Number n ? n.intValue() : 0, node, maven);
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (jdk > 0) map.put("jdk", jdk);
      if (node != null) map.put("node", node);
      if (maven != null) map.put("maven", maven);
      return map;
    }

    /** Converts a YAML value (Integer, Double, or String) to a version string. */
    private static String toVersionString(Object val) {
      if (val instanceof Integer n) return String.valueOf(n);
      if (val instanceof Double d)
        return d % 1 == 0 ? String.valueOf(d.intValue()) : String.valueOf(d);
      if (val instanceof String s && !s.isBlank()) return s;
      return null;
    }
  }

  /**
   * Git identity and authentication configuration for the dev user. The {@code sshKey} field is a
   * path to a private key file on the host, used only when {@code auth} is {@code "ssh"}.
   */
  public record Git(String name, String email, String auth, String sshKey) {
    public static Git fromMap(Map<String, Object> map) {
      var name = map.get("name");
      if (!(name instanceof String)) {
        throw new IllegalArgumentException("git.name is required");
      }
      var email = map.get("email");
      if (!(email instanceof String)) {
        throw new IllegalArgumentException("git.email is required");
      }
      var authValue = Objects.requireNonNullElse((String) map.get("auth"), "token");
      if (!"token".equals(authValue) && !"ssh".equals(authValue)) {
        throw new IllegalArgumentException("git.auth must be 'token' or 'ssh', got: " + authValue);
      }
      var sshKey = (String) map.get("ssh_key");
      return new Git((String) name, (String) email, authValue, sshKey);
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("email", email);
      map.put("auth", auth);
      if (sshKey != null) map.put("ssh_key", sshKey);
      return map;
    }
  }

  /** A source repository to clone into {@code ~/workspace/<path>}. */
  public record Repo(String url, String path, String branch) {
    public static Repo fromMap(Map<String, Object> map) {
      var url = map.get("url");
      if (!(url instanceof String)) {
        throw new IllegalArgumentException("repos[].url is required");
      }
      NameValidator.requireValidGitUrl((String) url, "repos[].url");
      var path = map.get("path");
      if (!(path instanceof String)) {
        throw new IllegalArgumentException("repos[].path is required");
      }
      NameValidator.requireSafePath((String) path, "repos[].path");
      var branch = (String) map.get("branch");
      if (branch != null) {
        NameValidator.requireValidGitRef(branch, "repos[].branch");
      }
      return new Repo((String) url, (String) path, branch);
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("url", url);
      map.put("path", path);
      if (branch != null) map.put("branch", branch);
      return map;
    }
  }

  public record Service(
      String image,
      List<Integer> ports,
      Map<String, String> environment,
      String command,
      List<String> volumes) {
    @SuppressWarnings("unchecked")
    public static Service fromMap(Map<String, Object> map) {
      var envRaw = (Map<String, Object>) map.get("environment");
      Map<String, String> env = null;
      if (envRaw != null) {
        var envMap = new LinkedHashMap<String, String>();
        for (var entry : envRaw.entrySet()) {
          envMap.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        env = envMap;
      }
      return new Service(
          (String) map.get("image"),
          (List<Integer>) map.get("ports"),
          env,
          (String) map.get("command"),
          (List<String>) map.get("volumes"));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("image", image);
      if (ports != null) map.put("ports", ports);
      if (environment != null) map.put("environment", new LinkedHashMap<>(environment));
      if (command != null) map.put("command", command);
      if (volumes != null) map.put("volumes", volumes);
      return map;
    }
  }

  public record Process(String command, String workdir) {
    public static Process fromMap(Map<String, Object> map) {
      return new Process((String) map.get("command"), (String) map.get("workdir"));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (command != null) map.put("command", command);
      if (workdir != null) map.put("workdir", workdir);
      return map;
    }
  }

  @SuppressWarnings("unchecked")
  public record Agent(
      String type,
      boolean autoBranch,
      String branchPrefix,
      boolean autoSnapshot,
      List<String> install,
      Map<String, String> config,
      Guardrails guardrails,
      Notifications notifications,
      Methodology methodology,
      ReviewPipelineConfig reviewPipeline,
      String buildSkill) {

    static final String BUILD_SKILL_KEY = "agent.build_skill";

    /** The build works under {@link StageSkill#BUILD} when the block names no skill. */
    public Agent {
      buildSkill = StageSkill.configured(BUILD_SKILL_KEY, buildSkill, StageSkill.BUILD);
    }

    /** An agent block whose build works under sail's own skill. */
    public Agent(
        String type,
        boolean autoBranch,
        String branchPrefix,
        boolean autoSnapshot,
        List<String> install,
        Map<String, String> config,
        Guardrails guardrails,
        Notifications notifications,
        Methodology methodology,
        ReviewPipelineConfig reviewPipeline) {
      this(
          type,
          autoBranch,
          branchPrefix,
          autoSnapshot,
          install,
          config,
          guardrails,
          notifications,
          methodology,
          reviewPipeline,
          null);
    }

    public Agent(
        String type,
        boolean autoBranch,
        String branchPrefix,
        boolean autoSnapshot,
        List<String> install,
        Map<String, String> config,
        Guardrails guardrails,
        Notifications notifications,
        Methodology methodology) {
      this(
          type,
          autoBranch,
          branchPrefix,
          autoSnapshot,
          install,
          config,
          guardrails,
          notifications,
          methodology,
          null);
    }

    public Agent(
        String type,
        boolean autoBranch,
        String branchPrefix,
        boolean autoSnapshot,
        List<String> install,
        Map<String, String> config,
        Guardrails guardrails,
        Notifications notifications) {
      this(
          type,
          autoBranch,
          branchPrefix,
          autoSnapshot,
          install,
          config,
          guardrails,
          notifications,
          null,
          null);
    }

    @SuppressWarnings("unchecked")
    public static Agent fromMap(Map<String, Object> map) {
      return fromMap(map, "sail.yaml");
    }

    @SuppressWarnings("unchecked")
    static Agent fromMap(Map<String, Object> map, String descriptor) {
      if (map.containsKey("specs_dir")) {
        throw new IllegalArgumentException(
            "Unknown agent key `specs_dir` in "
                + descriptor
                + "; remove `specs_dir` from "
                + descriptor
                + " because specs live in the Sail database.");
      }
      var notificationsRaw = (Map<String, Object>) map.get("notifications");
      var methodologyRaw = (Map<String, Object>) map.get("methodology");
      var reviewPipelineRaw = (Map<String, Object>) map.get("review_pipeline");
      return new Agent(
          (String) map.get("type"),
          Boolean.TRUE.equals(map.get("auto_branch")),
          (String) map.get("branch_prefix"),
          Boolean.TRUE.equals(map.get("auto_snapshot")),
          (List<String>) map.get("install"),
          (Map<String, String>) map.get("config"),
          Guardrails.fromBlock(map.get("guardrails"), Guardrails.BUILD_BLOCK, descriptor)
              .orElse(null),
          notificationsRaw != null ? Notifications.fromMap(notificationsRaw, descriptor) : null,
          methodologyRaw != null ? Methodology.fromMap(methodologyRaw) : null,
          reviewPipelineRaw != null
              ? ReviewPipelineConfig.fromMap(reviewPipelineRaw, descriptor)
              : null,
          (String) map.get("build_skill"));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("type", type);
      map.put("auto_branch", autoBranch);
      if (branchPrefix != null) map.put("branch_prefix", branchPrefix);
      map.put("auto_snapshot", autoSnapshot);
      if (install != null) map.put("install", new ArrayList<>(install));
      if (config != null) map.put("config", new LinkedHashMap<>(config));
      if (guardrails != null) map.put("guardrails", guardrails.toMap());
      if (notifications != null) map.put("notifications", notifications.toMap());
      if (methodology != null) map.put("methodology", methodology.toMap());
      if (!buildSkill.equals(StageSkill.BUILD)) map.put("build_skill", buildSkill);
      if (reviewPipeline != null) map.put("review_pipeline", reviewPipeline.toMap());
      return map;
    }

    /**
     * The limits a run in {@code lane} is held to: {@code review_pipeline.guardrails} for a
     * reviewer or a fix agent, {@code guardrails} for every other lane, each lane's own defaults
     * when its block is absent. The one place a lane is mapped to its limits.
     */
    public Guardrails guardrailsFor(Lane lane) {
      if (lane != null && lane.servesReview()) {
        return reviewPipeline != null ? reviewPipeline.guardrails() : Guardrails.reviewDefaults();
      }
      return guardrails != null ? guardrails : Guardrails.defaults();
    }

    /**
     * The hard lifetime the project sets for a run in {@code lane}, which bounds that run's
     * credential, or null when none does. A reviewer and a fix agent have their lane's — its
     * default when the project writes no block — and every other lane one only from an {@code
     * agent.guardrails} block the project wrote; a block that names no {@code max_duration} sets
     * none. A run nothing bounds must not lose its credential to a clock mid-work.
     */
    public Duration lifetimeFor(Lane lane) {
      var bounded = guardrails != null || (lane != null && lane.servesReview());
      return bounded ? Guardrails.parseDuration(guardrailsFor(lane).maxDuration()) : null;
    }
  }

  public record AgentContext(
      String techStack,
      String conventions,
      String buildCommands,
      String projectSpecific,
      String security,
      List<AgentRule> rules) {

    public AgentContext(
        String techStack, String conventions, String buildCommands, String projectSpecific) {
      this(techStack, conventions, buildCommands, projectSpecific, null, null);
    }

    public AgentContext(
        String techStack,
        String conventions,
        String buildCommands,
        String projectSpecific,
        String security) {
      this(techStack, conventions, buildCommands, projectSpecific, security, null);
    }

    public static AgentContext fromMap(Map<String, Object> map) {
      return new AgentContext(
          (String) map.get("tech_stack"),
          (String) map.get("conventions"),
          (String) map.get("build_commands"),
          (String) map.get("project_specific"),
          (String) map.get("security"),
          AgentRule.listFromMap(map.get("rules")));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (techStack != null) map.put("tech_stack", techStack);
      if (conventions != null) map.put("conventions", conventions);
      if (buildCommands != null) map.put("build_commands", buildCommands);
      if (projectSpecific != null) map.put("project_specific", projectSpecific);
      if (security != null) map.put("security", security);
      if (rules != null && !rules.isEmpty()) map.put("rules", AgentRule.listToMap(rules));
      return map;
    }
  }

  /**
   * An org-supplied coding-standard rule the agent loads only when it touches matching files. The
   * {@code body} is supplied verbatim by the project; sail materializes it into each agent's native
   * path-scoped channel (a Claude {@code .claude/rules/<name>.md} with a {@code paths:} glob, a
   * Codex skill loaded by description). Sail ships no rule content of its own.
   */
  public record AgentRule(String name, List<String> paths, String body) {

    public AgentRule {
      NameValidator.requireSafePath(name, "agent_context.rules name");
      paths = paths == null ? List.of() : List.copyOf(paths);
    }

    @SuppressWarnings("unchecked")
    static List<AgentRule> listFromMap(Object raw) {
      if (!(raw instanceof Map<?, ?> map)) {
        return null;
      }
      var rules = new ArrayList<AgentRule>();
      for (var entry : map.entrySet()) {
        if (entry.getValue() instanceof Map<?, ?> value) {
          rules.add(
              new AgentRule(
                  (String) entry.getKey(),
                  (List<String>) value.get("paths"),
                  (String) value.get("body")));
        }
      }
      return List.copyOf(rules);
    }

    static Map<String, Object> listToMap(List<AgentRule> rules) {
      var map = new LinkedHashMap<String, Object>();
      for (var rule : rules) {
        var inner = new LinkedHashMap<String, Object>();
        if (!rule.paths().isEmpty()) inner.put("paths", new ArrayList<>(rule.paths()));
        if (rule.body() != null) inner.put("body", rule.body());
        map.put(rule.name(), inner);
      }
      return map;
    }
  }

  @SuppressWarnings("unchecked")
  public record Ssh(String user, List<String> authorizedKeys) {
    public static Ssh fromMap(Map<String, Object> map) {
      var user = (String) map.get("user");
      if (user != null) {
        NameValidator.requireValidSshUser(user);
      }
      return new Ssh(user, (List<String>) map.get("authorized_keys"));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (user != null) map.put("user", user);
      if (authorizedKeys != null) map.put("authorized_keys", new ArrayList<>(authorizedKeys));
      return map;
    }
  }

  /** Returns a copy of this config with Node.js added to the runtimes. */
  public SailYaml withNodeRuntime(String nodeVersion) {
    var newRuntimes =
        runtimes != null
            ? new Runtimes(runtimes.jdk(), nodeVersion, runtimes.maven())
            : new Runtimes(0, nodeVersion, null);
    return new SailYaml(
        name,
        description,
        resources,
        image,
        packages,
        newRuntimes,
        git,
        repos,
        services,
        processes,
        agent,
        agentContext,
        ssh);
  }

  /** Returns a copy of this config with the agent install list replaced. */
  public SailYaml withAgentInstall(List<String> install) {
    var newAgent =
        new Agent(
            agent.type(),
            agent.autoBranch(),
            agent.branchPrefix(),
            agent.autoSnapshot(),
            install,
            agent.config(),
            agent.guardrails(),
            agent.notifications(),
            agent.methodology(),
            agent.reviewPipeline(),
            agent.buildSkill());
    return new SailYaml(
        name,
        description,
        resources,
        image,
        packages,
        runtimes,
        git,
        repos,
        services,
        processes,
        newAgent,
        agentContext,
        ssh);
  }

  /** Returns the SSH username from config, defaulting to "dev". */
  public String sshUser() {
    return ssh != null && ssh.user() != null ? ssh.user() : "dev";
  }

  /** Returns absolute repo paths inside the container for the configured repos. */
  public List<String> repoPaths() {
    var base = "/home/" + sshUser() + "/workspace";
    if (repos != null && !repos.isEmpty()) {
      return repos.stream().map(r -> base + "/" + r.path()).toList();
    }
    return List.of(base);
  }

  /** Normalizes a size value: bare numbers like {@code 4} become {@code "4GB"}. */
  static String normalizeSize(Object val) {
    var s = String.valueOf(val).strip().replaceAll("\\s+", "");
    if (s.matches("^\\d+$")) {
      return s + "GB";
    }
    return s;
  }
}
