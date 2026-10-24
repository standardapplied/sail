/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * What sail installs on its host and owns there, named here and nowhere else: Incus from Zabbly's
 * stable repository, and zfsutils-linux for a zfs pool. A backend that needs a package adds its
 * entry to {@link #forBackend}. The operator owns the rest of the OS, which sail never upgrades.
 *
 * <p>Incus is held once installed: an operator's {@code apt upgrade} or unattended-upgrades would
 * restart incusd, which ends every interactive {@code incus exec}, every terminal and agent run in
 * every container. It moves only when someone runs {@code sail host incus upgrade}.
 */
public final class HostPackages {

  /** A package sail installs: its apt name, and the command whose presence shows it installed. */
  public record Package(String name, String command) {}

  public static final Package INCUS = new Package("incus", "incus");
  public static final Package ZFS = new Package("zfsutils-linux", "zpool");

  /** What a box that held Incus for the first time is told. */
  public static final Remedy HELD =
      Remedy.of("incus held (", Remedy.sail("host incus upgrade"), " moves it)");

  static final String ZABBLY_KEY_URL = "https://pkgs.zabbly.com/key.asc";
  static final String ZABBLY_KEYRING = "/etc/apt/keyrings/zabbly.asc";
  public static final String ZABBLY_SOURCE = "/etc/apt/sources.list.d/zabbly-incus-stable.sources";

  private static final Duration KEY_TIMEOUT = Duration.ofMinutes(2);
  private static final String HELD_PATTERN = "incus*";

  private final ShellExec shell;
  private final Apt apt;
  private boolean indexed;

  /** Packages on the host {@code shell} runs on, apt's progress shown through {@code progress}. */
  public HostPackages(ShellExec shell, Consumer<String> progress) {
    this.shell = shell;
    this.apt = new Apt(shell, progress);
  }

  /** The packages a box with the {@code storageBackend} pool runs on, in install order. */
  public static List<Package> forBackend(String storageBackend) {
    return HostYaml.BACKEND_ZFS.equals(storageBackend) ? List.of(INCUS, ZFS) : List.of(INCUS);
  }

  /**
   * Installs {@code pkg} unless its command is already there, and says whether it did. Incus's
   * Zabbly source is written first. apt's index is refreshed once, before the first install, so a
   * box with nothing missing runs no apt at all.
   */
  public boolean ensure(Package pkg) throws IOException, InterruptedException, TimeoutException {
    if (shell.exec(List.of("which", pkg.command())).ok()) {
      return false;
    }
    if (pkg.equals(INCUS)) {
      addZabblySource();
    }
    if (!indexed) {
      apt.update();
      indexed = true;
    }
    apt.install(List.of(pkg.name()));
    return true;
  }

  /** Holds every installed Incus package not held yet; says whether any needed it. */
  public boolean hold() throws IOException, InterruptedException, TimeoutException {
    var unheld = incusPackages().stream().filter(p -> !p.held()).map(Installed::name).toList();
    if (unheld.isEmpty()) {
      return false;
    }
    mark(unheld);
    return true;
  }

  private void mark(List<String> packages) throws IOException, InterruptedException {
    apt.hold(packages);
  }

  /**
   * Incus as apt's local index has it, no network: installed and candidate, and whether the
   * candidate is the newer of the two as dpkg orders versions, since apt's candidate can be older
   * than what is installed and a move to it would be a downgrade.
   */
  public Versions versions() throws IOException, InterruptedException, TimeoutException {
    var policy = shell.exec(List.of("env", "LC_ALL=C", "apt-cache", "policy", INCUS.name()));
    if (!policy.ok()) {
      return Versions.NONE;
    }
    var installed = Versions.field(policy.stdout(), "Installed:");
    var candidate = Versions.field(policy.stdout(), "Candidate:");
    var differ = installed != null && candidate != null && !installed.equals(candidate);
    var newer =
        differ
            && shell.exec(List.of("dpkg", "--compare-versions", candidate, "gt", installed)).ok();
    return new Versions(installed, candidate, newer);
  }

  /**
   * Moves every held Incus package to apt's candidate, then holds every Incus package installed
   * after it, a dependency the new release brought in among them. The move drops the holds, so a
   * failed move holds the packages it started from again, its failure kept; a move that succeeded
   * but could not be held again says so, naming the command that holds it.
   */
  public void upgrade() throws IOException, InterruptedException, TimeoutException {
    var held = incusPackages().stream().map(Installed::name).toList();
    if (held.isEmpty()) {
      throw new IOException("No Incus package is installed to upgrade.");
    }
    try {
      apt.upgradeHeld(held);
    } catch (IOException failed) {
      holdAfter(failed, held);
      throw failed;
    }
    try {
      hold();
    } catch (IOException | TimeoutException unheld) {
      throw Remedied.io(
          "Incus moved but is not held again (" + unheld.getMessage() + ").",
          Remedy.of("Hold it: ", Remedy.sail("migrate")));
    }
  }

  private void holdAfter(IOException failed, List<String> held) throws InterruptedException {
    try {
      mark(held);
    } catch (IOException holdFailed) {
      failed.addSuppressed(holdFailed);
    }
  }

  private void addZabblySource() throws IOException, InterruptedException, TimeoutException {
    var key =
        shell.exec(
            List.of("curl", "-fsSL", "--create-dirs", "-o", ZABBLY_KEYRING, ZABBLY_KEY_URL),
            null,
            KEY_TIMEOUT);
    if (!key.ok()) {
      throw new IOException("Failed to add Zabbly GPG key: " + key.stderr());
    }
    var codename = shell.exec(List.of("bash", "-c", ". /etc/os-release && echo $VERSION_CODENAME"));
    if (!codename.ok()) {
      throw new IOException("Failed to detect OS codename: " + codename.stderr());
    }
    var arch = shell.exec(List.of("dpkg", "--print-architecture"));
    var source =
        """
        Enabled: yes
        Types: deb
        URIs: https://pkgs.zabbly.com/incus/stable
        Suites: %s
        Components: main
        Architectures: %s
        Signed-By: %s
        """
            .formatted(
                codename.stdout().strip(),
                arch.ok() ? arch.stdout().strip() : "amd64",
                ZABBLY_KEYRING);
    new HostDropIn("Zabbly incus source", ZABBLY_SOURCE, source, "").install(shell);
  }

  private List<Installed> incusPackages()
      throws IOException, InterruptedException, TimeoutException {
    var query =
        shell.exec(
            List.of(
                "dpkg-query",
                "-W",
                "-f=${Package} ${db:Status-Want} ${db:Status-Status}\\n",
                HELD_PATTERN));
    return query.stdout().lines().map(Installed::parse).flatMap(Optional::stream).toList();
  }

  /** An installed package, and whether it is held. */
  private record Installed(String name, boolean held) {
    static Optional<Installed> parse(String line) {
      var fields = line.strip().split(" ");
      return fields.length == 3 && fields[2].equals("installed")
          ? Optional.of(new Installed(fields[0], fields[1].equals("hold")))
          : Optional.empty();
    }
  }

  /**
   * Incus's installed and candidate versions as apt names them, each null when apt has none, and
   * whether the candidate is the {@code newer}. Incus moves only to a newer candidate: an older
   * one, a release Zabbly pulled or a build installed by hand, is nothing to move to.
   */
  public record Versions(String installed, String candidate, boolean newer) {

    static final Versions NONE = new Versions(null, null, false);

    static String field(String policy, String name) {
      return policy
          .lines()
          .map(String::strip)
          .filter(line -> line.startsWith(name))
          .map(line -> line.substring(name.length()).strip())
          .filter(value -> !value.equals("(none)"))
          .findFirst()
          .orElse(null);
    }

    /** Whether apt's candidate is newer than what is installed, so Incus would move. */
    public boolean movable() {
      return newer;
    }

    /** Whether apt's candidate is older than what is installed: nothing to move to. */
    public boolean candidateOlder() {
      return differ() && !newer;
    }

    /** The installed version as Incus numbers it, or whole when that hides the difference. */
    public String from() {
      return installed == null ? null : distinct() ? release(installed) : installed;
    }

    /**
     * The candidate as Incus numbers it, or whole when that hides the difference; null for none.
     */
    public String to() {
      return candidate == null ? null : distinct() ? release(candidate) : candidate;
    }

    private boolean differ() {
      return installed != null && candidate != null && !installed.equals(candidate);
    }

    private boolean distinct() {
      return !differ() || !release(installed).equals(release(candidate));
    }

    /**
     * {@code version} without apt's epoch and package revision: {@code 1:6.18-ubuntu24.04-…} is
     * 6.18.
     */
    static String release(String version) {
      var upstream = version.substring(version.indexOf(':') + 1);
      var revision = upstream.indexOf('-');
      return revision < 0 ? upstream : upstream.substring(0, revision);
    }
  }
}
