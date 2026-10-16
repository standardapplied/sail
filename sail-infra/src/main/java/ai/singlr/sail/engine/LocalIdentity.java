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
import java.util.ArrayList;
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
  private final Path home;
  private final boolean sudo;

  /** This process's git identity and the workstation key at {@code sshPublicKeyPath}. */
  public LocalIdentity(ShellExec shell, Path sshPublicKeyPath) {
    this(shell, sshPublicKeyPath, null, false);
  }

  /**
   * The identity of the account whose home is {@code home}: its git config and its key. {@code
   * sudo} when that account is root acting for a {@code sudo} caller, who sets root's git config
   * with {@code sudo}.
   */
  LocalIdentity(ShellExec shell, Path sshPublicKeyPath, Path home, boolean sudo) {
    this.shell = shell;
    this.sshPublicKeyPath = sshPublicKeyPath;
    this.home = home;
    this.sudo = sudo;
  }

  /**
   * This box's identity: the git config and workstation public key of the account that owns it
   * ({@link SailPaths#identityHome()}).
   */
  public static LocalIdentity detect() {
    return detect(
        new ShellExecutor(false),
        SailPaths.isRoot(),
        SailPaths.identityHome(),
        SailPaths.invokingHome());
  }

  /**
   * The identity at {@code home}, read for the person whose home is {@code invokingHome}: root's
   * own, when root reads it for a {@code sudo} caller, is set with {@code sudo}.
   */
  static LocalIdentity detect(ShellExec shell, boolean root, Path home, Path invokingHome) {
    return new LocalIdentity(
        shell, SailPaths.workstationPublicKeyPath(home), home, root && !home.equals(invokingHome));
  }

  /**
   * This box's git value for a {@code ${GIT_NAME}}/{@code ${GIT_EMAIL}} placeholder, or empty when
   * unset. Never throws — used to offer a default the engineer can accept or override at a prompt,
   * where {@link #valueFor} would hard-fail instead.
   */
  public Optional<String> gitValue(String placeholder) {
    var key = gitKey(placeholder);
    if (key == null) {
      return Optional.empty();
    }
    var value = run(gitConfigCommand(key));
    return Strings.isBlank(value) ? Optional.empty() : Optional.of(value);
  }

  /**
   * Writes {@code value} as this identity's git value for a {@code ${GIT_NAME}}/{@code
   * ${GIT_EMAIL}} placeholder, into the account's global git config, so a later apply resolves it.
   */
  public void adoptGit(String placeholder, String value)
      throws InterruptedException, TimeoutException {
    var key = gitKey(placeholder);
    if (key == null) {
      throw new IllegalArgumentException("${" + placeholder + "} is not a git identity field");
    }
    String failure;
    try {
      var result = shell.exec(withHome(List.of("git", "config", "--global", key, value)));
      if (result.ok()) {
        return;
      }
      failure = result.stderr().strip();
    } catch (IOException unrunnable) {
      failure = unrunnable.getMessage();
    }
    throw new IllegalStateException(
        "Couldn't set this box's git "
            + key
            + ": "
            + failure
            + "\n  Set it yourself and re-run: "
            + gitFix(key));
  }

  /** The git config key a {@code ${GIT_NAME}}/{@code ${GIT_EMAIL}} placeholder reads. */
  public static String gitKey(String placeholder) {
    return switch (placeholder) {
      case PlaceholderResolver.GIT_NAME -> "user.name";
      case PlaceholderResolver.GIT_EMAIL -> "user.email";
      default -> null;
    };
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

  /**
   * {@code git config --global} of this identity's account, with its home passed as an argument.
   */
  List<String> gitConfigCommand(String key) {
    return withHome(List.of("git", "config", "--global", "--get", key));
  }

  private List<String> withHome(List<String> command) {
    if (home == null) {
      return command;
    }
    var withHome = new ArrayList<>(List.of("env", "HOME=" + home));
    withHome.addAll(command);
    return List.copyOf(withHome);
  }

  private String gitConfig(String key) {
    var value = run(gitConfigCommand(key));
    if (Strings.isBlank(value)) {
      throw new IllegalStateException(
          "This box has no git "
              + key
              + " set, so a project here can't take on your identity.\n"
              + "  Set it once and re-run: "
              + gitFix(key));
    }
    return value;
  }

  /** The command that sets {@code key} for the account whose git config this identity reads. */
  private String gitFix(String key) {
    return (sudo ? "sudo " : "") + "git config --global " + key + " \"...\"";
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
          Register it (auto-detected from this box's authorized_keys, or pass the key):
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
