/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Logs the forge CLI the work prompt relies on into each GitHub host among a project's repos, as
 * the dev user, with the token the git credential store got for that host: {@code gh} is in every
 * container, and a prompt that asks for the pull request's checks needs it authenticated. The token
 * reaches {@code gh} on stdin from a {@code 0600} file under the dev home that one command reads
 * and removes, success or not: it is never an argument and never in a process list. Other forges
 * get nothing here; the prompt's last sentence covers them.
 */
public final class ForgeCliLogin {

  private static final String LOGIN =
      "gh auth login --hostname \"$1\" --with-token < \"$2\"; s=$?; rm -f \"$2\"; exit $s";

  private ForgeCliLogin() {}

  /**
   * Logs {@code gh} in for every GitHub host of {@code repos} that {@code tokens} resolve a token
   * for ({@link GitCredentials#resolveTokenForHost}, the store's own resolution).
   *
   * @throws IOException naming the host when a login fails
   */
  public static void loginGitHub(
      ShellExec shell,
      String container,
      String user,
      Map<String, String> tokens,
      List<SailYaml.Repo> repos)
      throws IOException, InterruptedException, TimeoutException {
    for (var host : GitCredentials.githubHosts(repos)) {
      var token = GitCredentials.resolveTokenForHost(host, tokens);
      if (Strings.isNotBlank(token)) {
        login(shell, container, user, host, token);
      }
    }
  }

  private static void login(
      ShellExec shell, String container, String user, String host, String token)
      throws IOException, InterruptedException, TimeoutException {
    var file = "/home/" + user + "/.sail/gh-token-" + DateTimeUtils.newId();
    var made =
        shell.exec(ContainerExec.asDevUser(container, List.of("mkdir", "-p", parentOf(file))));
    if (!made.ok()) {
      throw new IOException("Failed to create " + parentOf(file) + ": " + made.stderr());
    }
    ContainerFilePush.push(
        shell,
        container,
        file,
        token + "\n",
        List.of("--uid", ContainerExec.DEV_UID, "--gid", ContainerExec.DEV_GID, "--mode", "0600"));
    var login =
        shell.exec(
            ContainerExec.asDevUser(container, List.of("sh", "-c", LOGIN, "sh", host, file)));
    if (!login.ok()) {
      throw new IOException(
          "Failed to log gh in for " + host + " in " + container + ": " + login.stderr().strip());
    }
  }

  private static String parentOf(String path) {
    return path.substring(0, path.lastIndexOf('/'));
  }
}
