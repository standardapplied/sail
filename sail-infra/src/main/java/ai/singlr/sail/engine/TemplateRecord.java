/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.webauthn.Hashes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

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
    int kept,
    TokenProof proof) {

  /** The file a project's record is, beside its other state. */
  public static final String FILE = "template-build.json";

  /** How a build ended. */
  public enum Outcome {
    /** A new version was built and is current. */
    BUILT,
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

  /**
   * What lets a later process tell whether it holds the token values a build was given, never the
   * values themselves: a salt of the build's own, and each value's SHA-256 under it. Kept in the
   * file, never on the wire.
   */
  public record TokenProof(String salt, List<String> fingerprints) {
    public TokenProof {
      Objects.requireNonNull(salt, "salt");
      fingerprints = List.copyOf(fingerprints);
    }

    /** The proof of {@code tokens}, under a salt made for it. */
    public static TokenProof of(Collection<String> tokens) {
      var salt = new byte[16];
      RANDOM.nextBytes(salt);
      var said = HexFormat.of().formatHex(salt);
      return new TokenProof(said, tokens.stream().map(t -> fingerprint(said, t)).sorted().toList());
    }

    /** Whether {@code tokens} hold every value this proof was made of. */
    public boolean heldIn(Collection<String> tokens) {
      var held = tokens.stream().map(t -> fingerprint(salt, t)).collect(Collectors.toSet());
      return held.containsAll(fingerprints);
    }

    private static String fingerprint(String salt, String token) {
      return HexFormat.of()
          .formatHex(Hashes.sha256((salt + ":" + token).getBytes(StandardCharsets.UTF_8)));
    }
  }

  private static final SecureRandom RANDOM = new SecureRandom();

  /** A build {@code by} starts at {@code at}, at step 0 of {@code steps}. */
  public static TemplateRecord started(String by, Instant at, int steps) {
    return new TemplateRecord(by, at, 0, steps, at, null, null, null, null, 0, null);
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
    return new TemplateRecord(by, startedAt, step, steps, at, null, null, null, null, kept, proof);
  }

  /** This build with {@code kept} steps done when it began, resumed from an earlier one. */
  public TemplateRecord keeping(int kept) {
    return new TemplateRecord(
        by, startedAt, step, steps, stepStartedAt, outcome, endedAt, reason, remedy, kept, proof);
  }

  /** This build, given the tokens {@code proof} was made of. */
  public TemplateRecord proving(TokenProof proof) {
    return new TemplateRecord(
        by, startedAt, step, steps, stepStartedAt, outcome, endedAt, reason, remedy, kept, proof);
  }

  /** This build ended at {@code at} with {@code outcome}, and why when it failed. */
  public TemplateRecord ended(Outcome outcome, Instant at, String reason, Remedy remedy) {
    return new TemplateRecord(
        by, startedAt, step, steps, stepStartedAt, outcome, at, reason, remedy, kept, proof);
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

  /**
   * Writes this record to {@code file} whole and durably: a temporary file beside it, flushed to
   * the disk, renamed over it, and the rename flushed with its directory, so a power cut leaves the
   * record before or after, never none: a lost record would leave a cut build's template uncleaned.
   */
  public void write(Path file) {
    var temporary = file.resolveSibling("." + FILE + ".tmp");
    try {
      Files.createDirectories(file.getParent());
      try (var out =
          FileChannel.open(
              temporary,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        out.write(ByteBuffer.wrap(YamlUtil.dumpJson(toFile()).getBytes(StandardCharsets.UTF_8)));
        out.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      try (var directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
        directory.force(true);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not keep the template build's record: " + file, e);
    }
  }

  /** The record as the file keeps it: the wire's, and the proof of the build's tokens. */
  private Map<String, Object> toFile() {
    var m = toMap();
    if (proof != null) {
      m.put("token_salt", proof.salt());
      m.put("token_fingerprints", proof.fingerprints());
    }
    return m;
  }

  /** The record as the wire carries it: never the proof of the build's tokens. */
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("by", by);
    m.put("started_at", startedAt.toString());
    m.put("step", step);
    m.put("steps", steps);
    m.put("step_started_at", stepStartedAt.toString());
    if (outcome != null) {
      m.put("outcome", outcome.wire());
    }
    if (endedAt != null) {
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
        ((Number) m.get("kept")).intValue(),
        m.get("token_salt") instanceof String salt
                && m.get("token_fingerprints") instanceof List<?> fingerprints
            ? new TokenProof(salt, fingerprints.stream().map(String::valueOf).toList())
            : null);
  }
}
