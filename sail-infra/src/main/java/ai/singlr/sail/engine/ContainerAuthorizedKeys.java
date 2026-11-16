/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The {@code authorized_keys} of the accounts in each of this box's containers, which provisioning
 * fills from the project's {@code ssh.authorized_keys}. A removal reaches every container, an
 * addition each container sail made for the projects it is given, running or not alike, since
 * {@code incus file pull} and {@code push} reach a stopped container's filesystem. The accounts are
 * read from the container's own {@code /etc/passwd}, never from the catalog, which may name another
 * user than the one a container was provisioned with. A removal edits the file of every account
 * whose home is under {@code /home}, where provisioning creates the SSH user; an addition only the
 * SSH user's, the account at uid 1000 provisioning requires. Only the lines whose key is the exact
 * key are matched, so the other keys an engineer put there stay.
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
   * A new workstation key for this box, one path for a pairing and {@code host config set
   * ssh-public-key}: {@code replaced} out of every container, and {@code key} into the container of
   * each project this box runs and the workspaces of its person, {@code boxHandle}. Answers a
   * sentence for each container left behind; a box whose containers cannot be listed names the
   * apply of every one instead, since the catalog holds projects this box never ran.
   */
  public static List<String> rekeyWorkstation(
      ShellExec shell,
      Path database,
      String boxHandle,
      Optional<SshPublicKey> replaced,
      SshPublicKey key,
      OperatorCommand operator)
      throws InterruptedException {
    var keys = new ContainerAuthorizedKeys(shell);
    var unreached = new ArrayList<String>();
    if (replaced.isPresent()) {
      var old = replaced.get();
      try {
        keys.removeFromEach(old)
            .forEach(c -> unreached.add(stillTrusts(c.container(), c.reason(), old)));
      } catch (IOException | TimeoutException failure) {
        unreached.add(stillTrusts("This box's containers", Remedied.reasonOf(failure), old));
      }
    }
    List<String> projects;
    try (var db = Sqlite.open(database)) {
      projects = new ProjectStore(db).list().stream().map(ProjectStore.ProjectRow::name).toList();
    }
    if (projects.isEmpty()) {
      return List.copyOf(unreached);
    }
    try {
      var containers = new BoxInstances(shell).containers();
      var kinds = new HashMap<String, BoxInstances.Kind>();
      containers.forEach(instance -> kinds.put(instance.name(), instance.kind()));
      keys.addTo(key, containers.stream().filter(trusting(projects, boxHandle)).toList())
          .forEach(
              c ->
                  unreached.add(
                      notTrustedYet(
                          c.container(), kinds.get(c.container()), c.reason(), operator)));
    } catch (IOException | TimeoutException failure) {
      unreached.add(noneTrustYet(Remedied.reasonOf(failure).strip(), operator));
    }
    return List.copyOf(unreached);
  }

  /** A workspace takes its owner's keys at its start; {@code project apply} refuses one. */
  public static String notTrustedYet(
      String container, BoxInstances.Kind kind, String why, OperatorCommand operator) {
    var told = container + " does not trust this key yet: " + why;
    if (kind instanceof BoxInstances.Workspace w) {
      var named = w.project() + " " + w.workspace();
      return told
          + ". It takes it at its next start: '"
          + operator.sail("project workspace stop " + named)
          + "', then '"
          + operator.sail("project workspace start " + named)
          + "'.";
    }
    return told + ". Make it: '" + operator.sail("project apply " + container) + "'.";
  }

  public static String noneTrustYet(String why, OperatorCommand operator) {
    return "This box's containers do not trust this key yet: "
        + why
        + ". Once incus answers, make them: '"
        + operator.sail("project apply --all")
        + "'.";
  }

  private static String stillTrusts(String where, String why, SshPublicKey replaced) {
    return where
        + " may still trust the replaced workstation key "
        + describe(replaced)
        + ": "
        + why.strip()
        + ". Take its line out of every /home account's .ssh/authorized_keys there.";
  }

  /** A key as a person recognises it: its fingerprint, and its comment when it has one. */
  public static String describe(SshPublicKey key) {
    return key.comment() == null
        ? key.fingerprint()
        : key.fingerprint() + " (" + key.comment() + ")";
  }

  /** A container an edit did not reach, and why. */
  public record Unreached(String container, String reason) {}

  @FunctionalInterface
  private interface Edit {
    void apply(String container) throws IOException, InterruptedException, TimeoutException;
  }

  /**
   * Removes {@code key} from every container of this box, and returns those whose files it changed.
   */
  public List<String> remove(SshPublicKey key)
      throws IOException, InterruptedException, TimeoutException {
    var entry = entry(key);
    var changed = new ArrayList<String>();
    for (var container : containers()) {
      if (removeFromEvery(container, entry)) {
        changed.add(container);
      }
    }
    return List.copyOf(changed);
  }

  /**
   * Removes {@code key} from every container of this box as {@link #remove} does, and returns those
   * it could not, each with why: one container that cannot be written stops none of the others.
   */
  public List<Unreached> removeFromEach(SshPublicKey key)
      throws IOException, InterruptedException, TimeoutException {
    var entry = entry(key);
    return each(containers(), container -> removeFromEvery(container, entry));
  }

  /**
   * The workstation key goes to the box person's workspaces alone (T2). A container bearing a
   * project's name that carries no sail-api-sock device is returned, not written: sail did not make
   * it, or did not finish.
   */
  public List<Unreached> add(SshPublicKey key, Collection<String> projects, String boxHandle)
      throws IOException, InterruptedException, TimeoutException {
    return addTo(
        key,
        new BoxInstances(shell)
            .containers().stream().filter(trusting(projects, boxHandle)).toList());
  }

  private static Predicate<BoxInstances.Instance> trusting(
      Collection<String> projects, String boxHandle) {
    return instance ->
        instance.kind() instanceof BoxInstances.Plain && projects.contains(instance.name())
            || BoxInstances.wholeWorkspaceOf(boxHandle).test(instance);
  }

  /** A person's own key goes to their own workspaces alone. */
  public List<Unreached> addToWorkspacesOf(SshPublicKey key, String handle)
      throws IOException, InterruptedException, TimeoutException {
    return addTo(key, new BoxInstances(shell).wholeWorkspacesOf(handle));
  }

  private List<Unreached> addTo(SshPublicKey key, List<BoxInstances.Instance> reached)
      throws InterruptedException {
    var entry = entry(key);
    var devices = new IncusDeviceManager(shell);
    return each(
        reached.stream().map(BoxInstances.Instance::name).toList(),
        container -> {
          if (!devices.sailManaged(container)) {
            throw new IOException(
                "it carries no sail-api-sock device, so it is not sail's or its setup did not"
                    + " finish");
          }
          addTo(container, homeAccounts(container), key, entry);
        });
  }

  private static List<Unreached> each(List<String> containers, Edit edit)
      throws InterruptedException {
    var unreached = new ArrayList<Unreached>();
    for (var container : containers) {
      try {
        edit.apply(container);
      } catch (IOException | TimeoutException failure) {
        unreached.add(new Unreached(container, Remedied.reasonOf(failure).strip()));
      }
    }
    return List.copyOf(unreached);
  }

  /**
   * Removes {@code entry} from each account of {@code container}, one that cannot be edited
   * stopping none of the others, then fails with the first that could not.
   */
  private boolean removeFromEvery(String container, Pattern entry)
      throws IOException, InterruptedException, TimeoutException {
    var removed = false;
    IOException first = null;
    for (var account : homeAccounts(container)) {
      try {
        removed |= removeFrom(container, account, entry);
      } catch (IOException | TimeoutException failure) {
        first = first != null ? first : new IOException(Remedied.reasonOf(failure), failure);
      }
    }
    if (first != null) {
      throw first;
    }
    return removed;
  }

  private List<String> containers() throws IOException, InterruptedException, TimeoutException {
    return new BoxInstances(shell).containers().stream().map(BoxInstances.Instance::name).toList();
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

  private void addTo(String container, List<Account> accounts, SshPublicKey key, Pattern entry)
      throws IOException, InterruptedException, TimeoutException {
    var account =
        accounts.stream()
            .filter(held -> held.uid().equals(ProjectProvisioner.SSH_UID))
            .findFirst()
            .orElseThrow(
                () -> new IOException("it has no SSH user at uid " + ProjectProvisioner.SSH_UID));
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
