/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.PlaceholderResolver;
import ai.singlr.sail.ssh.SshPublicKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Answers a project definition's personal-field placeholders from <em>this</em> box: the git
 * identity from {@code git config --global}, and {@code ${SSH_PUBLIC_KEY}} from the box owner's
 * workstation key ({@link SailPaths#workstationPublicKeyPath()}) — the human key an engineer SSHes
 * into containers with, distinct from the machine sync key. Resolution happens once, when a project
 * is provisioned, so the synced definition stays identity-free and each box's containers trust
 * their owner's laptop key. A missing value fails loud with the one command that fixes it, rather
 * than silently provisioning a container no laptop can reach.
 */
public final class LocalIdentity {

  private final ShellExec shell;
  private final Path sshPublicKeyPath;

  public LocalIdentity(ShellExec shell, Path sshPublicKeyPath) {
    this.shell = shell;
    this.sshPublicKeyPath = sshPublicKeyPath;
  }

  /** This box's identity: real git config and the box owner's workstation public key. */
  public static LocalIdentity detect() {
    return new LocalIdentity(new ShellExecutor(false), SailPaths.workstationPublicKeyPath());
  }

  /**
   * This box's git value for a {@code ${GIT_NAME}}/{@code ${GIT_EMAIL}} placeholder, or empty when
   * unset. Never throws — used to offer a default the engineer can accept or override at a prompt,
   * where {@link #valueFor} would hard-fail instead.
   */
  public Optional<String> gitValue(String placeholder) {
    var key =
        switch (placeholder) {
          case PlaceholderResolver.GIT_NAME -> "user.name";
          case PlaceholderResolver.GIT_EMAIL -> "user.email";
          default -> null;
        };
    if (key == null) {
      return Optional.empty();
    }
    var value = run(List.of("git", "config", "--global", "--get", key));
    return Strings.isBlank(value) ? Optional.empty() : Optional.of(value);
  }

  /** Resolves one known placeholder name to this box's value, failing loud if it is not set. */
  public String valueFor(String placeholder) {
    return switch (placeholder) {
      case PlaceholderResolver.GIT_NAME -> gitConfig("user.name");
      case PlaceholderResolver.GIT_EMAIL -> gitConfig("user.email");
      case PlaceholderResolver.SSH_PUBLIC_KEY -> sshPublicKey();
      default ->
          throw new IllegalArgumentException("No box-local source for ${" + placeholder + "}");
    };
  }

  private String gitConfig(String key) {
    var value = run(List.of("git", "config", "--global", "--get", key));
    if (Strings.isBlank(value)) {
      throw new IllegalStateException(
          "This box has no git "
              + key
              + " set, so a project here can't take on your identity.\n"
              + "  Set it once and re-run: git config --global "
              + key
              + " \"...\"");
    }
    return value;
  }

  private String sshPublicKey() {
    return WorkstationIdentity.registeredAt(sshPublicKeyPath)
        .map(SshPublicKey::line)
        .orElseThrow(() -> new IllegalStateException(missingKeyMessage()));
  }

  private String missingKeyMessage() {
    return """
        This box has no valid workstation SSH key set (%s), so a container here can't authorize \
        you to connect from your laptop.
          Set it once with the public key you SSH with:
            %s"""
        .formatted(sshPublicKeyPath, WorkstationIdentity.SET_KEY_HINT);
  }

  private String run(List<String> command) {
    try {
      var result = shell.exec(command);
      return result.ok() ? result.stdout().strip() : "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    } catch (IOException | TimeoutException e) {
      return "";
    }
  }
}
