/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.ssh.SshPublicKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The {@code authorized_keys} of the accounts in each of this box's containers, which provisioning
 * fills from the project's {@code ssh.authorized_keys}. A removal edits the file of every account
 * whose home is under {@code /home} (where provisioning creates the SSH user) in every container,
 * running or not, since {@code incus file pull} and {@code push} reach a stopped container's
 * filesystem. The accounts are read from the container's own {@code /etc/passwd}, never from the
 * catalog, which may name another user than the one a container was provisioned with. Only the
 * lines whose key is the exact key go, so the other keys an engineer put there stay.
 */
public final class ContainerAuthorizedKeys {

  private static final Pattern HOME_ACCOUNT =
      Pattern.compile("^[^:]+:[^:]*:(\\d+):(\\d+):[^:]*:(/home/[^/:.][^/:]*)/?:[^:]*$");

  /** An {@code authorized_keys} options field: unquoted characters, escapes and quoted strings. */
  static final String OPTIONS = "(?:[^\\s\"\\\\]|\\\\.|\"(?:[^\"\\\\]|\\\\.)*\")+";

  private final ShellExec shell;

  public ContainerAuthorizedKeys(ShellExec shell) {
    this.shell = shell;
  }

  /**
   * Removes {@code key} from every container of this box, and returns those whose files it changed.
   */
  public List<String> remove(SshPublicKey key)
      throws IOException, InterruptedException, TimeoutException {
    var entry =
        Pattern.compile(
            "^(?!\\s*#)\\s*(?:"
                + OPTIONS
                + "\\s+)?"
                + Pattern.quote(key.type())
                + "\\s+"
                + Pattern.quote(key.blob())
                + "(?:\\s.*)?$");
    var changed = new ArrayList<String>();
    for (var container : new ContainerManager(shell).listContainers()) {
      var removed = false;
      for (var account : homeAccounts(container.name())) {
        removed |= removeFrom(container.name(), account, entry);
      }
      if (removed) {
        changed.add(container.name());
      }
    }
    return List.copyOf(changed);
  }

  private record Account(String uid, String gid, String home) {}

  private List<Account> homeAccounts(String container)
      throws IOException, InterruptedException, TimeoutException {
    var passwd = shell.exec(List.of("incus", "file", "pull", container + "/etc/passwd", "-"));
    if (!passwd.ok()) {
      throw new IOException(
          "could not read /etc/passwd in " + container + ": " + passwd.stderr().strip());
    }
    return passwd
        .stdout()
        .lines()
        .map(HOME_ACCOUNT::matcher)
        .filter(Matcher::matches)
        .map(account -> new Account(account.group(1), account.group(2), account.group(3)))
        .toList();
  }

  private boolean removeFrom(String container, Account account, Pattern entry)
      throws IOException, InterruptedException, TimeoutException {
    var file = account.home() + "/.ssh/authorized_keys";
    var pulled = shell.exec(List.of("incus", "file", "pull", container + file, "-"));
    if (!pulled.ok()) {
      if (absent(pulled.stderr())) {
        return false;
      }
      throw new IOException(
          "could not read " + file + " in " + container + ": " + pulled.stderr().strip());
    }
    var lines = pulled.stdout().lines().toList();
    var kept = lines.stream().filter(line -> !entry.matcher(line).matches()).toList();
    if (kept.size() == lines.size()) {
      return false;
    }
    var content = kept.stream().map(line -> line + "\n").collect(Collectors.joining());
    ContainerFilePush.push(
        shell,
        container,
        file,
        content,
        List.of("--uid", account.uid(), "--gid", account.gid(), "--mode", "0600"));
    return true;
  }

  /** Whether a failed pull says the file is not there: an account never given a key has none. */
  private static boolean absent(String stderr) {
    var said = stderr.toLowerCase(Locale.ROOT);
    return said.contains("not found") || said.contains("does not exist");
  }
}
