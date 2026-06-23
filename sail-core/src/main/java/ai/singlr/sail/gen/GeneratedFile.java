/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

/**
 * A file to be pushed into the project container. Used by generators that produce files without
 * performing I/O themselves — callers handle the actual push.
 *
 * @param remotePath absolute path inside the container
 * @param content file content
 * @param executable whether the file should be marked executable
 * @param mergeMarker when non-null, the file is merge-managed: {@code content} is the regenerated
 *     body and everything from this marker down in the existing file is the engineer's preserved
 *     personal region (see {@link ContextMerge}); when null, the file is overwritten outright
 */
public record GeneratedFile(
    String remotePath, String content, boolean executable, String mergeMarker) {

  public GeneratedFile(String remotePath, String content, boolean executable) {
    this(remotePath, content, executable, null);
  }

  /**
   * A merge-managed file: the generated {@code body} is refreshed above {@code mergeMarker} while
   * the engineer's personal region below it is preserved across regeneration.
   */
  public static GeneratedFile merged(String remotePath, String body, String mergeMarker) {
    return new GeneratedFile(remotePath, body, false, mergeMarker);
  }
}
