/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.config;

import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.BlobStore;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The host's guard against accidentally replicating an enormous shared file. */
public record FileLimits(long fileMax, long mainFileMax) {
  public static final long DEFAULT_MAX = 1024L * 1024 * 1024;

  public FileLimits {
    if (fileMax <= 0 || fileMax > BlobStore.MAX_SIZE) {
      throw new IllegalArgumentException(
          "limits.file_max must be between 1 byte and 8 GiB: a blob manifest must fit one sync frame");
    }
    if (mainFileMax >= fileMax) {
      mainFileMax = 0;
    }
  }

  /** This box's own limit, with no cap of main's. */
  public FileLimits(long fileMax) {
    this(fileMax, 0);
  }

  public static FileLimits defaults() {
    return new FileLimits(DEFAULT_MAX);
  }

  /**
   * This limit capped at {@code otherMax} when that is a tighter positive cap — main's {@code
   * file_max} as a node learned it — so an ingest enforces the lower of its own and main's and a
   * refusal names both; a 0 ({@code otherMax} not yet known) or a looser cap leaves this one.
   */
  public FileLimits cappedAt(long otherMax) {
    return otherMax > 0 && otherMax < fileMax ? new FileLimits(fileMax, otherMax) : this;
  }

  /** The cap an ingest enforces: main's when it is the tighter, else this box's own. */
  public long effectiveMax() {
    return mainFileMax > 0 ? mainFileMax : fileMax;
  }

  public static FileLimits fromMap(Map<String, Object> map) {
    if (map == null || !map.containsKey("file_max")) return defaults();
    var value = map.get("file_max");
    if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
      throw new IllegalArgumentException("limits.file_max must be an integer number of bytes");
    }
    return new FileLimits(number.longValue());
  }

  public static FileLimits load() {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) return defaults();
    try {
      return HostYaml.fromMap(YamlUtil.parseFile(path)).limits();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read file limits from " + path, e);
    }
  }

  public void check(long size) {
    problem(size)
        .ifPresent(
            problem -> {
              throw new IllegalArgumentException(problem);
            });
  }

  /** Why a file of {@code size} bytes cannot be shared under this cap, naming where to raise it. */
  public Optional<String> problem(long size) {
    if (size < 0) return Optional.of("A declared file length is required");
    if (size > effectiveMax()) return Optional.of(exceeds(size));
    return Optional.empty();
  }

  private String exceeds(long size) {
    if (mainFileMax > 0) {
      return "File of "
          + size
          + " bytes exceeds main's limits.file_max ("
          + mainFileMax
          + " bytes; this box allows "
          + fileMax
          + "); raise limits.file_max in main's host.yaml to share it";
    }
    return "File of "
        + size
        + " bytes exceeds limits.file_max ("
        + fileMax
        + " bytes); raise limits.file_max in host.yaml to share it";
  }

  public InputStream bounded(InputStream input, long declaredSize) {
    check(declaredSize);
    return new FilterInputStream(input) {
      private long read;

      @Override
      public int read() throws IOException {
        var value = in.read();
        if (value != -1 && ++read > effectiveMax())
          throw new IOException("File exceeds limits.file_max (" + effectiveMax() + " bytes)");
        return value;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (read == effectiveMax()) return read();
        var count = in.read(bytes, offset, (int) Math.min(length, effectiveMax() - read));
        if (count > 0) read += count;
        return count;
      }
    };
  }
}
