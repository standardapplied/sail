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

/** The host's guard against accidentally replicating an enormous shared file. */
public record FileLimits(long fileMax) {
  public static final long DEFAULT_MAX = 1024L * 1024 * 1024;

  public FileLimits {
    if (fileMax <= 0 || fileMax > BlobStore.MAX_SIZE) {
      throw new IllegalArgumentException(
          "limits.file_max must be between 1 byte and 8 GiB: a blob manifest must fit one sync frame");
    }
  }

  public static FileLimits defaults() {
    return new FileLimits(DEFAULT_MAX);
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
    if (size < 0) throw new IllegalArgumentException("A declared file length is required");
    if (size > fileMax)
      throw new IllegalArgumentException(
          "File of " + size + " bytes exceeds limits.file_max (" + fileMax + " bytes)");
  }

  public InputStream bounded(InputStream input, long declaredSize) {
    check(declaredSize);
    return new FilterInputStream(input) {
      private long read;

      @Override
      public int read() throws IOException {
        var value = in.read();
        if (value != -1 && ++read > fileMax)
          throw new IOException("File exceeds limits.file_max (" + fileMax + " bytes)");
        return value;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (read == fileMax) return read();
        var count = in.read(bytes, offset, (int) Math.min(length, fileMax - read));
        if (count > 0) read += count;
        return count;
      }
    };
  }
}
