/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.LoginStore;
import ai.singlr.sail.store.RunStore;
import java.util.Objects;

/**
 * The secrets a run's process starts with: its run credential, which the {@code spec} CLI and the
 * hooks present to the local API, and, for a run that names a spec, the stored login its harness
 * reads, {@code null} for a run on its container's own login. They reach the container only as the
 * run's {@code launch.env} ({@link #lines}), {@code 0600} and the dev user's, never as an argument,
 * an {@code --env} or a {@code --setenv}; every launch shape's wrapper starts with {@link #read},
 * which exports each line and removes the file before the harness starts. {@link #toString} names
 * no value.
 */
public record LaunchEnv(String runCredential, LoginStore.Login stored) {

  /** The variable the run credential is exported as. */
  public static final String RUN_CREDENTIAL = "SAIL_RUN_CREDENTIAL";

  /**
   * Reads the file its first word names line by line, exports each line as it is ({@code export
   * "$kv"} assigns and evaluates nothing) and removes the file, with tracing off so a profile's
   * {@code set -x} prints no value. A missing file ends the launch, so a harness never starts
   * without its credential or its person's login.
   */
  private static final String READ =
      "f=@FILE@; set +x; while IFS= read -r kv; do export \"$kv\"; done < \"$f\" || exit 1;"
          + " rm -f \"$f\"";

  public LaunchEnv {
    if (runCredential != null && (runCredential.contains("\n") || runCredential.contains("\r"))) {
      throw new IllegalArgumentException("A run credential is one line.");
    }
  }

  /** The login the run starts with, as its row records it: never the value. */
  public RunStore.RunLogin login() {
    return stored == null
        ? RunStore.RunLogin.CONTAINER
        : new RunStore.RunLogin(stored.row().name(), stored.kind().kind());
  }

  /**
   * The file's content: one {@code NAME=value} line each, the run credential's first. A run on a
   * stored login also sets empty every other variable its harness takes a credential from, so none
   * its container exports outranks the login it records.
   */
  public String lines() {
    var lines =
        new StringBuilder(RUN_CREDENTIAL + "=" + Objects.toString(runCredential, "") + "\n");
    if (stored == null) {
      return lines.toString();
    }
    var own = stored.kind().variable();
    for (var variable : Harnesses.of(stored.row().harness()).loginVariables()) {
      if (!variable.equals(own)) {
        lines.append(variable).append("=\n");
      }
    }
    return lines.append(own).append('=').append(stored.value()).append('\n').toString();
  }

  /**
   * {@code command}, a shell command line, run after the file at {@code path} is read into the
   * environment and removed: the shell that runs it is the innermost, after every profile it
   * sourced, so nothing a profile exports outranks the run's own values.
   */
  public static String wrap(String path, String command) {
    return read(path) + "; exec " + command;
  }

  /**
   * The fixed text every launch shape's wrapper starts with: it reads the file at {@code path},
   * exports each line and removes the file, or ends the shell when there is no file.
   *
   * @throws IllegalArgumentException when {@code path} is not an absolute path of plain characters
   */
  public static String read(String path) {
    if (!path.matches("/[A-Za-z0-9/._-]+")) {
      throw new IllegalArgumentException("Not a launch file's path: '" + path + "'.");
    }
    return READ.replace("@FILE@", path);
  }

  @Override
  public String toString() {
    return "LaunchEnv[login=" + login() + "]";
  }
}
