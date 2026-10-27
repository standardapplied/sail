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
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The {@code authorized_keys} of the accounts in each of this box's containers, which provisioning
 * fills from the project's {@code ssh.authorized_keys}. Both edits reach every container, running
 * or not, since {@code incus file pull} and {@code push} reach a stopped container's filesystem.
 * The accounts are read from the container's own {@code /etc/passwd}, never from the catalog, which
 * may name another user than the one a container was provisioned with. A removal edits the file of
 * every account whose home is under {@code /home}, where provisioning creates the SSH user; an
 * addition only the SSH user's, the account at uid 1000 provisioning requires. Only the lines whose
 * key is the exact key are matched, so the other keys an engineer put there stay.
 */
public final class ContainerAuthorizedKeys {

  private static final Pattern HOME_ACCOUNT =
      Pattern.compile("^[^:]+:[^:]*:(\\d+):(\\d+):[^:]*:(/home/[^/:.][^/:]*)/?:[^:]*$");

  /** An {@code authorized_keys} options field: unquoted characters, escapes and quoted strings. */
  static final String OPTIONS = "(?:[^\\s\"\\\\]|\\\\.|\"(?:[^\"\\\\]|\\\\.)*\")+";

  private static final String SSH_USER_UID = "1000";

  private final ShellExec shell;

  public ContainerAuthorizedKeys(ShellExec shell) {
    this.shell = shell;
  }

  /** A container that does not trust a key, and why. */
  public record Untrusted(String container, String reason) {}

  /**
   * Removes {@code key} from every container of this box, and returns those whose files it changed.
   */
  public List<String> remove(SshPublicKey key)
      throws IOException, InterruptedException, TimeoutException {
    var entry = entry(key);
    var changed = new ArrayList<String>();
    for (var container : containers()) {
      var removed = false;
      for (var account : homeAccounts(container)) {
        removed |= removeFrom(container, account, entry);
      }
      if (removed) {
        changed.add(container);
      }
    }
    return List.copyOf(changed);
  }

  /**
   * Adds {@code key} to the SSH user's file in every container of this box that does not hold it
   * yet, and returns those it could not, each with why: one container that cannot be written stops
   * none of the others.
   */
  public List<Untrusted> add(SshPublicKey key)
      throws IOException, InterruptedException, TimeoutException {
    var entry = entry(key);
    var untrusted = new ArrayList<Untrusted>();
    for (var container : containers()) {
      try {
        addTo(container, key, entry);
      } catch (IOException | TimeoutException failure) {
        untrusted.add(new Untrusted(container, failure.getMessage().strip()));
      }
    }
    return List.copyOf(untrusted);
  }

  private List<String> containers() throws IOException, InterruptedException, TimeoutException {
    return new ContainerManager(shell)
        .listContainers().stream().map(ContainerManager.ContainerInfo::name).toList();
  }

  private static Pattern entry(SshPublicKey key) {
    return Pattern.compile(
        "^(?!\\s*#)\\s*(?:"
            + OPTIONS
            + "\\s+)?"
            + Pattern.quote(key.type())
            + "\\s+"
            + Pattern.quote(key.blob())
            + "(?:\\s.*)?$");
  }

  private record Account(String uid, String gid, String home) {
    String authorizedKeys() {
      return home + "/.ssh/authorized_keys";
    }
  }

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
    var lines = read(container, account);
    if (lines.isEmpty()) {
      return false;
    }
    var kept = lines.get().stream().filter(line -> !entry.matcher(line).matches()).toList();
    if (kept.size() == lines.get().size()) {
      return false;
    }
    write(container, account, kept);
    return true;
  }

  private void addTo(String container, SshPublicKey key, Pattern entry)
      throws IOException, InterruptedException, TimeoutException {
    var account =
        homeAccounts(container).stream()
            .filter(held -> held.uid().equals(SSH_USER_UID))
            .findFirst()
            .orElseThrow(() -> new IOException("it has no SSH user at uid " + SSH_USER_UID));
    var lines =
        read(container, account)
            .orElseThrow(() -> new IOException("it has no " + account.authorizedKeys() + " yet"));
    if (lines.stream().noneMatch(line -> entry.matcher(line).matches())) {
      write(container, account, Stream.concat(lines.stream(), Stream.of(key.line())).toList());
    }
  }

  /** The lines of {@code account}'s file, or none when it has none. */
  private Optional<List<String>> read(String container, Account account)
      throws IOException, InterruptedException, TimeoutException {
    var file = account.authorizedKeys();
    var pulled = shell.exec(List.of("incus", "file", "pull", container + file, "-"));
    if (pulled.ok()) {
      return Optional.of(pulled.stdout().lines().toList());
    }
    if (absent(pulled.stderr())) {
      return Optional.empty();
    }
    throw new IOException(
        "could not read " + file + " in " + container + ": " + pulled.stderr().strip());
  }

  /**
   * Writes {@code lines} as {@code account}'s file, with the owner and mode provisioning gives it.
   */
  private void write(String container, Account account, List<String> lines)
      throws IOException, InterruptedException, TimeoutException {
    ContainerFilePush.push(
        shell,
        container,
        account.authorizedKeys(),
        lines.stream().map(line -> line + "\n").collect(Collectors.joining()),
        List.of("--uid", account.uid(), "--gid", account.gid(), "--mode", "0600"));
  }

  /** Whether a failed pull says the file is not there: an account never given a key has none. */
  private static boolean absent(String stderr) {
    var said = stderr.toLowerCase(Locale.ROOT);
    return said.contains("not found") || said.contains("does not exist");
  }
}
