/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Ids;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Ends the fix agent a server older than 0.46.4 started for a review, so no second fix agent ever
 * works the same branch beside it. Such a server ran a fix agent as a foreground exec that exported
 * {@code SAIL_RUN_ID=<reviewId>}, and that process outlives the server's restart: no run row names
 * it, no unit owns it, and nothing this server watches or stops can reach it. A run started by this
 * server exports its own run id and never a review id, so matching on the review id can only ever
 * find an agent an older server left behind.
 *
 * <p>Delete this class once {@code SchemaManager.FLOOR_VERSION} passes the step that added {@code
 * runs.review_id}: no box below that step can upgrade onto this server then, so no such process can
 * exist.
 */
public final class LegacyFixAgent {

  private static final String KILL_SCRIPT =
      """
      for environ in /proc/[0-9]*/environ; do
        if grep -zqxF -- "SAIL_RUN_ID=$1" "$environ" 2>/dev/null; then
          pid="${environ#/proc/}"
          kill -9 "${pid%/environ}" 2>/dev/null
        fi
      done
      exit 0
      """;

  private LegacyFixAgent() {}

  /**
   * Sends SIGKILL to every process in {@code project}'s container whose environment holds the exact
   * entry {@code SAIL_RUN_ID=<reviewId>}. One exec as the dev user; the review id travels as a
   * positional argument, never as script text.
   *
   * @throws IllegalArgumentException when {@code reviewId} is not a canonical UUID
   * @throws IOException when the container did not run the command, so nothing says whether such a
   *     process is still alive
   */
  public static void stop(ShellExec shell, String project, String reviewId) throws IOException {
    var command =
        ContainerExec.asDevUser(
            project, List.of("bash", "-c", KILL_SCRIPT, "bash", Ids.requireUuid(reviewId)));
    ShellExec.Result result;
    try {
      result = shell.exec(command);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException(unchecked(project, reviewId, "the check was interrupted"), interrupted);
    } catch (TimeoutException slow) {
      throw new IOException(unchecked(project, reviewId, "the check timed out"), slow);
    }
    if (!result.ok()) {
      throw new IOException(unchecked(project, reviewId, result.stderr().strip()));
    }
  }

  private static String unchecked(String project, String reviewId, String why) {
    return "Could not check container '"
        + project
        + "' for a fix agent an older server left running for review "
        + reviewId
        + ": "
        + why
        + ". Check that the container is running, then re-dispatch.";
  }
}
