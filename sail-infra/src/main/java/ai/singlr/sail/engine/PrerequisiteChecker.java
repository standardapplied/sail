/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Checks that required system commands are available before provisioning begins. Commands like
 * {@code incus} and {@code zpool} are excluded — they're installed by the provisioner itself.
 */
public final class PrerequisiteChecker {

  /** A command that must be present on the system. */
  public record Prerequisite(String command, String pkg, String reason) {}

  /** Result of checking all prerequisites. */
  public record CheckResult(List<Prerequisite> present, List<Prerequisite> missing) {}

  static final List<Prerequisite> REQUIRED =
      List.of(
          new Prerequisite("bash", null, "Shell execution"),
          new Prerequisite("lsblk", "util-linux", "Disk detection"),
          new Prerequisite("apt-get", null, "Package management"),
          new Prerequisite("curl", "curl", "Downloading signing keys"),
          new Prerequisite("gpg", "gnupg", "Verifying signing keys"));

  private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(5);

  private final ShellExec shell;

  public PrerequisiteChecker(ShellExec shell) {
    this.shell = shell;
  }

  /** Checks which required commands are available via {@code which}. */
  public CheckResult check() throws IOException, InterruptedException, TimeoutException {
    var present = new ArrayList<Prerequisite>();
    var missing = new ArrayList<Prerequisite>();

    for (var prereq : REQUIRED) {
      var result = shell.exec(List.of("which", prereq.command()), null, CHECK_TIMEOUT);
      if (result.ok()) {
        present.add(prereq);
      } else {
        missing.add(prereq);
      }
    }

    return new CheckResult(List.copyOf(present), List.copyOf(missing));
  }

  /**
   * Installs missing prerequisites that have a known package name, after the {@link
   * NeedrestartPolicy}: apt's needrestart hook would otherwise restart incusd on a box that has it.
   */
  public void installMissing(List<Prerequisite> missing)
      throws IOException, InterruptedException, TimeoutException {
    var packages = missing.stream().filter(p -> p.pkg() != null).map(Prerequisite::pkg).toList();

    if (packages.isEmpty()) {
      return;
    }

    NeedrestartPolicy.DROP_IN.install(shell);
    var command = new ArrayList<>(List.of("apt-get", "install", "-y", "-qq"));
    command.addAll(packages);
    var result = shell.exec(command, null, Duration.ofMinutes(3));
    if (!result.ok()) {
      throw new IOException(
          "Failed to install prerequisite packages ("
              + String.join(", ", packages)
              + "): "
              + result.stderr());
    }
  }
}
