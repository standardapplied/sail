/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.engine;

import ai.singlr.sail.api.ProjectFiles;
import ai.singlr.sail.identity.Acting;
import java.io.ByteArrayInputStream;

public final class ProjectFileFixtures {
  private ProjectFileFixtures() {}

  public static String put(ProjectFiles files, String path, byte[] bytes) {
    return Acting.system(
        () -> files.put(path, new ByteArrayInputStream(bytes), bytes.length, 0644));
  }
}
