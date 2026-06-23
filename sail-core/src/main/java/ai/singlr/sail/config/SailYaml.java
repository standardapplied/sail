/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.engine.NameValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  @SuppressWarnings("unchecked")
  public static SailYaml fromMap(Map<String, Object> map) {
    var resourcesRaw = (Map<String, Object>) map.get("resources");
    var runtimesRaw = (Map<String, Object>) map.get("runtimes");
    var gitRaw = (Map<String, Object>) map.get("git");
    var reposRaw = (List<Map<String, Object>>) map.get("repos");
    var servicesRaw = (Map<String, Object>) map.get("services");
    var processesRaw = (Map<String, Object>) map.get("processes");
    var agentRaw = (Map<String, Object>) map.get("agent");
    var agentCtxRaw = (Map<String, Object>) map.get("agent_context");
    var sshRaw = (Map<String, Object>) map.get("ssh");

    return new SailYaml(
        (String) map.get("name"),
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
        agentRaw != null ? Agent.fromMap(agentRaw) : null,
        agentCtxRaw != null ? AgentContext.fromMap(agentCtxRaw) : null,
        sshRaw != null ? Ssh.fromMap(sshRaw) : null);
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
      String specsDir,
      SecurityAudit securityAudit,
      CodeReview codeReview,
      Notifications notifications,
      Methodology methodology,
      ReviewPipelineConfig reviewPipeline) {
    public Agent(
        String type,
        boolean autoBranch,
        String branchPrefix,
        boolean autoSnapshot,
        List<String> install,
        Map<String, String> config,
        Guardrails guardrails,
        String specsDir,
        SecurityAudit securityAudit,
        CodeReview codeReview,
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
          specsDir,
          securityAudit,
          codeReview,
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
        String specsDir,
        SecurityAudit securityAudit,
        CodeReview codeReview,
        Notifications notifications) {
      this(
          type,
          autoBranch,
          branchPrefix,
          autoSnapshot,
          install,
          config,
          guardrails,
          specsDir,
          securityAudit,
          codeReview,
          notifications,
          null,
          null);
    }

    @SuppressWarnings("unchecked")
    public static Agent fromMap(Map<String, Object> map) {
      var guardrailsRaw = (Map<String, Object>) map.get("guardrails");
      var securityAuditRaw = (Map<String, Object>) map.get("security_audit");
      var codeReviewRaw = (Map<String, Object>) map.get("code_review");
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
          guardrailsRaw != null ? Guardrails.fromMap(guardrailsRaw) : null,
          validatedSpecsDir(Objects.requireNonNullElse((String) map.get("specs_dir"), "specs")),
          securityAuditRaw != null ? SecurityAudit.fromMap(securityAuditRaw) : null,
          codeReviewRaw != null ? CodeReview.fromMap(codeReviewRaw) : null,
          notificationsRaw != null ? Notifications.fromMap(notificationsRaw) : null,
          methodologyRaw != null ? Methodology.fromMap(methodologyRaw) : null,
          reviewPipelineRaw != null ? ReviewPipelineConfig.fromMap(reviewPipelineRaw) : null);
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
      map.put("specs_dir", Objects.requireNonNullElse(specsDir, "specs"));
      if (securityAudit != null) map.put("security_audit", securityAudit.toMap());
      if (codeReview != null) map.put("code_review", codeReview.toMap());
      if (notifications != null) map.put("notifications", notifications.toMap());
      if (methodology != null) map.put("methodology", methodology.toMap());
      if (reviewPipeline != null)
        map.put(
            "review_pipeline",
            Map.of(
                "max_iterations", reviewPipeline.maxIterations(),
                "stages", reviewPipeline.stages()));
      return map;
    }

    private static String validatedSpecsDir(String specsDir) {
      if (specsDir != null) {
        NameValidator.requireSafePath(specsDir, "agent.specs_dir");
      }
      return specsDir;
    }
  }

  public record AgentContext(
      String techStack,
      String conventions,
      String buildCommands,
      String projectSpecific,
      String security) {

    public AgentContext(
        String techStack, String conventions, String buildCommands, String projectSpecific) {
      this(techStack, conventions, buildCommands, projectSpecific, null);
    }

    public static AgentContext fromMap(Map<String, Object> map) {
      return new AgentContext(
          (String) map.get("tech_stack"),
          (String) map.get("conventions"),
          (String) map.get("build_commands"),
          (String) map.get("project_specific"),
          (String) map.get("security"));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (techStack != null) map.put("tech_stack", techStack);
      if (conventions != null) map.put("conventions", conventions);
      if (buildCommands != null) map.put("build_commands", buildCommands);
      if (projectSpecific != null) map.put("project_specific", projectSpecific);
      if (security != null) map.put("security", security);
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
            agent.specsDir(),
            agent.securityAudit(),
            agent.codeReview(),
            agent.notifications(),
            agent.methodology());
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
