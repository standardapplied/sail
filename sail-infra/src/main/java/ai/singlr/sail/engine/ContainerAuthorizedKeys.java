/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.ssh.SshPublicKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * The {@code authorized_keys} of the SSH user in each of this box's project containers, which
 * provisioning fills from the project's {@code ssh.authorized_keys}. A removal edits the file of
 * every container whether it runs or not, since {@code incus file pull} and {@code push} reach a
 * stopped container's filesystem, and takes only the lines that carry the exact key, so the other
 * keys an engineer put there stay.
 */
public final class ContainerAuthorizedKeys {

  private static final List<String> OWNED_BY_THE_SSH_USER =
      List.of("--uid", "1000", "--gid", "1000", "--mode", "0600");

  private final ShellExec shell;

  public ContainerAuthorizedKeys(ShellExec shell) {
    this.shell = shell;
  }

  /**
   * Removes {@code key} from the container of each project in {@code sshUsers} (project to its SSH
   * user) that exists on this box, and returns the containers whose file it changed.
   */
  public List<String> remove(SshPublicKey key, Map<String, String> sshUsers)
      throws IOException, InterruptedException, TimeoutException {
    if (sshUsers.isEmpty()) {
      return List.of();
    }
    var changed = new ArrayList<String>();
    for (var container : new ContainerManager(shell).listAll()) {
      var user = sshUsers.get(container.name());
      if (user != null && removeFrom(container.name(), user, key.blob())) {
        changed.add(container.name());
      }
    }
    return List.copyOf(changed);
  }

  private boolean removeFrom(String container, String user, String blob)
      throws IOException, InterruptedException, TimeoutException {
    var file = "/home/" + user + "/.ssh/authorized_keys";
    var pulled = shell.exec(List.of("incus", "file", "pull", container + file, "-"));
    if (!pulled.ok()) {
      if (absent(pulled.stderr())) {
        return false;
      }
      throw new IOException(
          "could not read " + file + " in " + container + ": " + pulled.stderr().strip());
    }
    var lines = pulled.stdout().lines().toList();
    var kept = lines.stream().filter(line -> !carries(line, blob)).toList();
    if (kept.size() == lines.size()) {
      return false;
    }
    var content = kept.stream().map(line -> line + "\n").reduce("", String::concat);
    ContainerFilePush.push(shell, container, file, content, OWNED_BY_THE_SSH_USER);
    return true;
  }

  /** Whether a failed pull says the file is not there: a container never given a key has none. */
  private static boolean absent(String stderr) {
    var said = stderr.toLowerCase(Locale.ROOT);
    return said.contains("not found") || said.contains("does not exist");
  }

  private static boolean carries(String line, String blob) {
    var fields = line.strip().split("\\s+");
    return !fields[0].startsWith("#") && Arrays.asList(fields).contains(blob);
  }
}
