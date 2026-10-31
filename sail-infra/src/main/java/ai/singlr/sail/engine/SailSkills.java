/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.gen.SpecSkillGenerator;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Sail's own skills, the ones this binary writes and installs beside a project's own for every
 * harness: {@code spec-board}, how an agent manages specs, and {@code sail-portal}, how it brings
 * the project's app up. Their names are sail's: no project skill may take one, or any name starting
 * {@code sail-}.
 */
public final class SailSkills {

  private SailSkills() {}

  /** Every skill sail ships, in name order. */
  public static List<StageSkill> all() {
    return List.of(PortalSkill.skill(), SpecSkillGenerator.skill());
  }

  /** The skill of sail's named {@code name}, if sail ships one. */
  public static Optional<StageSkill> named(String name) {
    return all().stream().filter(skill -> skill.name().equals(name)).findFirst();
  }

  /** The {@code SKILL.md} of sail's skill {@code name}, if sail ships one. */
  public static Optional<String> manifest(String name) {
    return switch (name) {
      case SpecSkillGenerator.NAME -> Optional.of(SpecSkillGenerator.skillMd());
      case PortalSkill.NAME -> Optional.of(PortalSkill.skillMd());
      default -> Optional.empty();
    };
  }

  /** The bytes of {@code file} of sail's {@code skill}. */
  public static InputStream content(StageSkill skill, StageSkill.File file) {
    return switch (skill.name()) {
      case SpecSkillGenerator.NAME -> SpecSkillGenerator.content(file);
      case PortalSkill.NAME -> PortalSkill.content(file);
      default -> throw new IllegalArgumentException("Sail ships no skill '" + skill.name() + "'.");
    };
  }
}
