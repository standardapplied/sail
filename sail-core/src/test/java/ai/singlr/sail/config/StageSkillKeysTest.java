/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.ReviewPipelineConfig.Gate;
import ai.singlr.sail.config.ReviewPipelineConfig.StageConfig;
import ai.singlr.sail.config.ReviewPipelineConfig.StageType;
import ai.singlr.sail.gen.SailYamlGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The three keys a project names its stages' skills with: {@code agent.build_skill}, {@code
 * agent.review_pipeline.fix_skill} and {@code agent.review_pipeline.stages[].skill}.
 */
class StageSkillKeysTest {

  private static final String NAMED =
      """
      name: acme
      resources:
        cpu: 2
        memory: 4GB
        disk: 20GB
      agent:
        type: claude-code
        install: [claude-code, codex]
        build_skill: acme-build
        review_pipeline:
          fix_skill: acme-fix
          stages:
            - name: security
              skill: acme-security
            - name: style
            - name: sign-off
              type: human
      agent_context:
        rules:
          java-style:
            paths: ["**/*.java"]
            body: Use records.
      """;

  private static final String DEFAULTS =
      """
      name: acme
      resources:
        cpu: 2
        memory: 4GB
        disk: 20GB
      agent:
        type: claude-code
        review_pipeline:
          stages:
            - name: review
      """;

  private static final String SAILS_OWN =
      " is the name of a skill sail installs itself (spec, spec-board, verify, and one per"
          + " agent_context.rules entry); give the stage's skill or the rule another name.";

  private static SailYaml parse(String yaml) {
    return SailYaml.fromMap(YamlUtil.parseMap(yaml));
  }

  private static String refusal(String yaml) {
    return assertThrows(IllegalArgumentException.class, () -> parse(yaml)).getMessage();
  }

  private static String agent(String agentLines) {
    return """
        name: acme
        agent:
          type: claude-code
        """
        + agentLines.indent(2);
  }

  @Test
  void aStageThatNamesNoSkillRunsUnderSailsOwn() {
    var agent = parse(DEFAULTS).agent();

    assertEquals("sail-build", agent.buildSkill());
    assertEquals("sail-fix", agent.reviewPipeline().fixSkill());
    assertEquals("sail-review", agent.reviewPipeline().stages().getFirst().skill());
    assertEquals("sail-fix", ReviewPipelineConfig.mandatoryDefault().fixSkill());
    assertEquals(
        "sail-review", ReviewPipelineConfig.mandatoryDefault().stages().getFirst().skill());
  }

  @Test
  void aStageRunsUnderTheSkillItsKeyNames() {
    var agent = parse(NAMED).agent();

    assertEquals("acme-build", agent.buildSkill());
    assertEquals("acme-fix", agent.reviewPipeline().fixSkill());
    assertEquals(
        List.of("acme-security", "sail-review"),
        agent.reviewPipeline().agentStages().stream().map(StageConfig::skill).toList());
    assertNull(agent.reviewPipeline().humanStages().getFirst().skill(), "a person follows none");
  }

  @Test
  void aKeyAcceptsItsOwnDefaultWrittenOut() {
    var agent =
        parse(
                agent(
                    """
                    build_skill: sail-build
                    review_pipeline:
                      fix_skill: sail-fix
                      stages:
                        - name: review
                          skill: sail-review
                    """))
            .agent();

    assertEquals(parse(DEFAULTS).agent().buildSkill(), agent.buildSkill());
    assertEquals(parse(DEFAULTS).agent().reviewPipeline(), agent.reviewPipeline());
  }

  @Test
  void theOlderConstructorShapesKeepSailsOwnSkills() {
    var stages =
        List.of(
            new StageConfig("review", StageType.AGENT, null, List.of(), Gate.NO_CRITICAL, null));
    var ten =
        new SailYaml.Agent("claude-code", true, null, false, null, null, null, null, null, null);
    var nine = new SailYaml.Agent("claude-code", true, null, false, null, null, null, null, null);
    var eight = new SailYaml.Agent("claude-code", true, null, false, null, null, null, null);

    assertEquals("sail-build", ten.buildSkill());
    assertEquals(ten, nine);
    assertEquals(ten, eight);
    assertEquals("sail-fix", new ReviewPipelineConfig(3, 2, stages).fixSkill());
    assertEquals(
        "sail-fix", new ReviewPipelineConfig(3, 2, stages, Guardrails.reviewDefaults()).fixSkill());
  }

  @Test
  void generateThenParseGivesTheSameConfigurationAndWritesNoDefault() {
    var named = parse(NAMED);
    var defaults = parse(DEFAULTS);

    var writtenNamed = SailYamlGenerator.generate(named);
    var writtenDefaults = SailYamlGenerator.generate(defaults);

    assertEquals(named.agent(), parse(writtenNamed).agent());
    assertEquals(defaults.agent(), parse(writtenDefaults).agent());
    assertEquals(named, SailYaml.fromMap(named.toMap()));
    assertEquals(defaults, SailYaml.fromMap(defaults.toMap()));
    assertFalse(writtenDefaults.contains("skill"), writtenDefaults);
    assertFalse(writtenNamed.contains("sail-"), "the stage left at its default: " + writtenNamed);
    assertEquals(
        List.of(
            "type", "auto_branch", "auto_snapshot", "install", "build_skill", "review_pipeline"),
        List.copyOf(named.agent().toMap().keySet()));
    assertEquals(
        List.of("fix_skill", "stages"),
        List.copyOf(named.agent().reviewPipeline().toMap().keySet()));
    assertEquals(
        List.of("name", "type", "gate", "skill"),
        List.copyOf(named.agent().reviewPipeline().stages().getFirst().toMap().keySet()));
  }

  @Test
  void changingTheInstallListKeepsTheBuildSkill() {
    var reinstalled = parse(NAMED).withAgentInstall(List.of("claude-code"));

    assertEquals("acme-build", reinstalled.agent().buildSkill());
    assertEquals("acme-fix", reinstalled.agent().reviewPipeline().fixSkill());
    assertEquals(List.of("claude-code"), reinstalled.agent().install());
  }

  @Test
  void aNameOnlySailShipsIsRefusedNamingTheKey() {
    assertEquals(
        "agent.build_skill 'sail-x' is not a skill a project can name: names starting sail- are"
            + " sail's, and this key's is sail-build.",
        refusal(agent("build_skill: sail-x")));
    assertEquals(
        "agent.review_pipeline.fix_skill 'sail-x' is not a skill a project can name: names"
            + " starting sail- are sail's, and this key's is sail-fix.",
        refusal(agent("review_pipeline:\n  fix_skill: sail-x")));
    assertEquals(
        "agent.review_pipeline.stages[security].skill 'sail-x' is not a skill a project can name:"
            + " names starting sail- are sail's, and this key's is sail-review.",
        refusal(agent("review_pipeline:\n  stages:\n    - name: security\n      skill: sail-x")));
  }

  @Test
  void anotherKeysDefaultIsRefusedNamingTheKey() {
    assertEquals(
        "agent.build_skill 'sail-review' is not a skill a project can name: names starting sail-"
            + " are sail's, and this key's is sail-build.",
        refusal(agent("build_skill: sail-review")));
    assertEquals(
        "agent.review_pipeline.fix_skill 'sail-build' is not a skill a project can name: names"
            + " starting sail- are sail's, and this key's is sail-fix.",
        refusal(agent("review_pipeline:\n  fix_skill: sail-build")));
    assertEquals(
        "agent.review_pipeline.stages[security].skill 'sail-fix' is not a skill a project can"
            + " name: names starting sail- are sail's, and this key's is sail-review.",
        refusal(agent("review_pipeline:\n  stages:\n    - name: security\n      skill: sail-fix")));
  }

  @Test
  void aNameThatIsNoSkillNameIsRefusedNamingTheKey() {
    assertEquals(
        "agent.build_skill 'Bad Name' is not a skill name: it must match [a-z0-9][a-z0-9-]{0,63}.",
        refusal(agent("build_skill: Bad Name")));
    assertEquals(
        "agent.review_pipeline.fix_skill '../etc' is not a skill name: it must match"
            + " [a-z0-9][a-z0-9-]{0,63}.",
        refusal(agent("review_pipeline:\n  fix_skill: ../etc")));
    assertEquals(
        "agent.review_pipeline.stages[security].skill '' is not a skill name: it must match"
            + " [a-z0-9][a-z0-9-]{0,63}.",
        refusal(agent("review_pipeline:\n  stages:\n    - name: security\n      skill: ''")));
  }

  @Test
  void aSkillOnAHumanStageIsRefusedNamingTheKey() {
    assertEquals(
        "agent.review_pipeline.stages[sign-off].skill is set on a human stage; a person follows"
            + " no skill, so remove it.",
        refusal(
            agent(
                """
                review_pipeline:
                  stages:
                    - name: sign-off
                      type: human
                      skill: acme-review
                """)));
  }

  @Test
  void theNameOfASkillSailGeneratesIsRefusedNamingTheKey() {
    assertEquals("agent.build_skill 'verify'" + SAILS_OWN, refusal(agent("build_skill: verify")));
    assertEquals(
        "agent.review_pipeline.fix_skill 'spec'" + SAILS_OWN,
        refusal(agent("review_pipeline:\n  fix_skill: spec")));
    assertEquals(
        "agent.review_pipeline.stages[security].skill 'spec-board'" + SAILS_OWN,
        refusal(
            agent("review_pipeline:\n  stages:\n    - name: security\n      skill: spec-board")));
  }

  @Test
  void theNameOfOneOfTheProjectsRulesIsRefusedNamingTheKey() {
    var rules =
        """
        agent_context:
          rules:
            java-style:
              body: Use records.
        """;
    assertEquals(
        "agent.build_skill 'java-style'" + SAILS_OWN,
        refusal(agent("build_skill: java-style") + rules));
    assertEquals(
        "agent.review_pipeline.fix_skill 'java-style'" + SAILS_OWN,
        refusal(agent("review_pipeline:\n  fix_skill: java-style") + rules));
    assertEquals(
        "agent.review_pipeline.stages[style].skill 'java-style'" + SAILS_OWN,
        refusal(
            agent("review_pipeline:\n  stages:\n    - name: style\n      skill: java-style")
                + rules));
    assertEquals(
        "java-style",
        parse(agent("build_skill: java-style")).agent().buildSkill(),
        "with no such rule the name is the project's to use");
    assertEquals(
        "acme-build",
        parse(agent("build_skill: acme-build") + "agent_context:\n  tech_stack: Java\n")
            .agent()
            .buildSkill(),
        "a context that holds no rules takes no name");
  }

  @Test
  void aRuleNamedAsAStagesOwnDefaultIsRefusedSinceCodexWouldInstallBothInOneFolder() {
    assertEquals(
        "agent.build_skill 'sail-build'" + SAILS_OWN,
        refusal(
            agent("install: [claude-code, codex]")
                + "agent_context:\n  rules:\n    sail-build:\n      body: Build my way.\n"));
  }

  @Test
  void aRuleNamedAsADefaultTheLoopFallsBackToIsRefusedThoughNoBlockNamesIt() {
    var codex = "install: [claude-code, codex]\n";
    var review = "agent_context:\n  rules:\n    sail-review:\n      body: Review my way.\n";
    var fix = "agent_context:\n  rules:\n    sail-fix:\n      body: Fix my way.\n";
    var reviewRefused = "agent.review_pipeline.stages[review].skill 'sail-review'" + SAILS_OWN;
    var fixRefused = "agent.review_pipeline.fix_skill 'sail-fix'" + SAILS_OWN;

    assertEquals(reviewRefused, refusal(agent(codex) + review), "no review_pipeline block");
    assertEquals(fixRefused, refusal(agent(codex) + fix), "no review_pipeline block");
    assertEquals(
        reviewRefused,
        refusal(agent(codex + "review_pipeline:\n  stages: []") + review),
        "a block with no stages runs the default's");
    assertEquals(
        reviewRefused,
        refusal(agent(codex + "review_pipeline:\n  fix_skill: acme-fix") + review),
        "a block with only a fix skill runs the default's stages too");
    assertEquals(fixRefused, refusal(agent(codex + "review_pipeline:\n  max_iterations: 5") + fix));
    assertEquals(
        "acme-security",
        parse(
                agent(
                        codex
                            + "review_pipeline:\n  fix_skill: acme-fix\n  stages:\n"
                            + "    - name: security\n      skill: acme-security")
                    + review
                    + "    sail-fix:\n      body: Fix my way.\n")
            .agent()
            .reviewPipeline()
            .stages()
            .getFirst()
            .skill(),
        "a pipeline that runs neither default leaves both names to the project's rules");
  }

  @Test
  void aDefinitionWithNoAgentNamesNoSkill() {
    assertNull(parse("name: acme\nagent_context:\n  rules:\n    verify:\n      body: b\n").agent());
  }
}
