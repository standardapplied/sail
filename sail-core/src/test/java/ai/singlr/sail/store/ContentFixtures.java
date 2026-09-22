/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ContentFixtures {
  private ContentFixtures() {}

  public static void put(FileStore files, String project, String path, String text) {
    files.put(project, path, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), 0644);
  }

  public static String text(FileStore files, String project, String path) {
    return files.blobs().text(files.find(project, path).orElseThrow().contentHash());
  }

  public static String encoded(FileStore files, String project, String path) {
    try (var input = files.open(files.find(project, path).orElseThrow())) {
      return java.util.Base64.getEncoder().encodeToString(input.readAllBytes());
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  public static Map<String, Object> snapshot(FileStore files, String text) {
    return Map.of("content_hash", files.blobs().putText(text), "mode", 0644, "kind", "text");
  }
}
