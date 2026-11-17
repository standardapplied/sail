/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a build of a project's template stands, or how its last one ended: who started it and when,
 * the step it is on of {@link #steps}, and, once it ended, its outcome, the reason it failed and
 * the remedy as parts. It is the project's {@value #FILE}, written whole as a temporary file and
 * renamed into place at every change, so a reader never sees half of one and a build cut short
 * leaves its last step.
 */
public record TemplateRecord(
    String by,
    Instant startedAt,
    int step,
    int steps,
    Instant stepStartedAt,
    Outcome outcome,
    Instant endedAt,
    String reason,
    Remedy remedy,
    int kept) {

  /** The file a project's record is, beside its other state. */
  public static final String FILE = "template-build.json";

  /** How a build ended. */
  public enum Outcome {
    /** A new version was built and is current. */
    BUILT,
    /** The current version was built from the definition as it is, so nothing was built. */
    CURRENT,
    /** A step failed: {@link #reason} says why. */
    FAILED,
    /** The build's process ended before it did, by a restart or a kill. */
    INTERRUPTED;

    /** As the wire says it. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public TemplateRecord {
    Objects.requireNonNull(by, "by");
    Objects.requireNonNull(startedAt, "startedAt");
  }

  /** A build {@code by} starts at {@code at}, at step 0, {@code kept} steps already done. */
  public static TemplateRecord started(String by, Instant at, int steps) {
    return new TemplateRecord(by, at, 0, steps, at, null, null, null, null, 0);
  }

  /** The record of project {@code project}'s builds, under {@code projectsDir}. */
  public static Path fileOf(Path projectsDir, String project) {
    return projectsDir.resolve(project).resolve(FILE);
  }

  /** Whether the build is still under way by its record: it has no outcome. */
  public boolean running() {
    return outcome == null;
  }

  /** This build at {@code step}, begun at {@code at}. */
  public TemplateRecord at(int step, Instant at) {
    return new TemplateRecord(by, startedAt, step, steps, at, null, null, null, null, kept);
  }

  /** This build with {@code kept} steps done when it began, resumed from an earlier one. */
  public TemplateRecord keeping(int kept) {
    return new TemplateRecord(
        by, startedAt, step, steps, stepStartedAt, outcome, endedAt, reason, remedy, kept);
  }

  /** This build ended at {@code at} with {@code outcome}, and why when it failed. */
  public TemplateRecord ended(Outcome outcome, Instant at, String reason, Remedy remedy) {
    return new TemplateRecord(
        by, startedAt, step, steps, stepStartedAt, outcome, at, reason, remedy, kept);
  }

  /** The record in {@code file}, empty when there is none or it cannot be read as one. */
  public static Optional<TemplateRecord> read(Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(fromMap(YamlUtil.parseMap(Files.readString(file))));
    } catch (IOException | RuntimeException unreadable) {
      return Optional.empty();
    }
  }

  /** Writes this record to {@code file} whole: a temporary file beside it, renamed over it. */
  public void write(Path file) {
    try {
      Files.createDirectories(file.getParent());
      var temporary = file.resolveSibling("." + FILE + ".tmp");
      Files.writeString(temporary, YamlUtil.dumpJson(toMap()));
      Files.move(
          temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not keep the template build's record: " + file, e);
    }
  }

  /** The record as the wire and the file carry it. */
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("by", by);
    m.put("started_at", startedAt.toString());
    m.put("step", step);
    m.put("steps", steps);
    m.put("step_started_at", stepStartedAt.toString());
    if (outcome != null) {
      m.put("outcome", outcome.wire());
      m.put("ended_at", endedAt.toString());
    }
    if (reason != null) {
      m.put("reason", reason);
    }
    if (remedy != null) {
      m.put("remedy", remedy.toMaps());
    }
    m.put("kept", kept);
    return m;
  }

  private static TemplateRecord fromMap(Map<String, Object> m) {
    var outcome =
        m.get("outcome") instanceof String said
            ? Outcome.valueOf(said.toUpperCase(Locale.ROOT))
            : null;
    return new TemplateRecord(
        (String) m.get("by"),
        Instant.parse((String) m.get("started_at")),
        ((Number) m.get("step")).intValue(),
        ((Number) m.get("steps")).intValue(),
        Instant.parse((String) m.get("step_started_at")),
        outcome,
        m.get("ended_at") instanceof String ended ? Instant.parse(ended) : null,
        (String) m.get("reason"),
        m.get("remedy") instanceof List<?> parts ? Remedy.fromMaps(parts) : null,
        ((Number) m.get("kept")).intValue());
  }
}
