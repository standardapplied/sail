/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.store.LoginStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * How an agent session in a workspace starts signed in as its person: the login's lines, as {@link
 * LaunchEnv} writes them, reach the workspace only as a {@code 0600} file of the dev user's in a
 * directory of the session's own ({@link #file}), written from its input ({@link #HAND}), and the
 * session's shell reads them into its environment and removes the file before the harness starts
 * ({@link #command}). Nothing of the login is on any command line, here or in the workspace.
 */
public final class SessionLogin {

  /** Where sessions' login files are handed over in a workspace, one directory each. */
  public static final String DIR = ContainerExec.DEV_HOME + "/.sail/sessions";

  /**
   * Run as the dev user, {@code $1} the login file and its lines on stdin: removes every session's
   * directory there older than a minute, what an exec killed before its read left, then makes the
   * file's own directory, refused when the name is there already, a link included, and writes the
   * file in it: a directory just made holds nothing a name could already stand for.
   */
  public static final String HAND =
      """
      set -eu
      umask 077
      d=${1%/*}
      mkdir -p -- "${d%/*}"
      find "${d%/*}" -mindepth 1 -maxdepth 1 -mmin +1 -exec rm -rf -- {} +
      mkdir -- "$d"
      cat > "$1"
      """;

  /** Run as the dev user, {@code $1} a login file: removes its directory, the file within it. */
  public static final String REMOVE = "rm -rf -- \"${1%/*}\"";

  private SessionLogin() {}

  /** The file the login of the session of {@code instanceId} is handed over as. */
  public static String file(String instanceId) {
    return DIR + "/" + instanceId + "/login.env";
  }

  /**
   * What a session running {@code harness} in {@code mode} runs in its workspace, as {@code bash
   * -lc} takes it: with {@code stored}, its login's file at {@code file} read into the environment
   * and removed with its directory, and the harness's own sign-in from it; without, nothing of a
   * login. A harness its workspace does not hold says so and ends 127.
   *
   * @throws IllegalArgumentException when the harness takes no {@code mode}
   */
  public static List<String> command(
      Harness harness, String mode, Optional<LoginStore.Login> stored, String file) {
    var steps = new ArrayList<String>();
    stored.ifPresent(
        login -> {
          steps.add(LaunchEnv.read(file));
          steps.add("rmdir -- " + file.substring(0, file.lastIndexOf('/')));
        });
    steps.add(
        "command -v "
            + harness.binaryName()
            + " > /dev/null 2>&1 || { echo \""
            + harness.displayName()
            + " is not installed in this workspace: its template was built before the project"
            + " installed it.\"; exit 127; }");
    stored
        .map(login -> harness.interactiveSignIn(login.kind()))
        .filter(signIn -> !signIn.isEmpty())
        .ifPresent(steps::add);
    steps.add("exec " + harness.interactive(mode));
    return List.of("bash", "-lc", String.join("; ", steps));
  }

  /** The command the session shows as running: the harness's own, never what started it. */
  public static List<String> shown(Harness harness, String mode) {
    return List.of(harness.interactive(mode).split(" "));
  }
}
