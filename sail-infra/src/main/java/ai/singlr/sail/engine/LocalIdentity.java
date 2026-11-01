/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * This box's git identity, from {@code git config --global} of the account that owns it: the name
 * and email every container provisioned here commits as. A project's definition carries none, so
 * each engineer's containers commit as them; a box with none set fails loud with the command that
 * sets it, rather than provisioning a container that commits as nobody.
 */
public final class LocalIdentity {

  /** A git identity field and the {@code git config} key that holds it. */
  public enum GitField {
    NAME("user.name"),
    EMAIL("user.email");

    private final String key;

    GitField(String key) {
      this.key = key;
    }

    /** The {@code git config} key. */
    public String key() {
      return key;
    }
  }

  /** The name and email a container commits as. */
  public record GitIdentity(String name, String email) {}

  private final ShellExec shell;
  private final Path home;
  private final boolean sudo;

  /** This process's own git identity. */
  public LocalIdentity(ShellExec shell) {
    this(shell, null, false);
  }

  /**
   * The identity of the account whose home is {@code home}: its git config. {@code sudo} when that
   * account is root acting for a {@code sudo} caller, who sets root's git config with {@code sudo}.
   */
  LocalIdentity(ShellExec shell, Path home, boolean sudo) {
    this.shell = shell;
    this.home = home;
    this.sudo = sudo;
  }

  /**
   * This box's identity: the git config of the account that owns it ({@link
   * SailPaths#identityHome()}).
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
    return new LocalIdentity(shell, home, root && !home.equals(invokingHome));
  }

  /** This box's git identity, failing loud with the command that sets whichever is unset. */
  public GitIdentity git() {
    return new GitIdentity(required(GitField.NAME), required(GitField.EMAIL));
  }

  /** This box's value of {@code field}, or empty when unset. Never throws. */
  public Optional<String> gitValue(GitField field) {
    var value = run(gitConfigCommand(field.key()));
    return Strings.isBlank(value) ? Optional.empty() : Optional.of(value);
  }

  /** Writes {@code value} as this box's {@code field}, into the account's global git config. */
  public void adoptGit(GitField field, String value) throws InterruptedException, TimeoutException {
    String failure;
    try {
      var result = shell.exec(withHome(List.of("git", "config", "--global", field.key(), value)));
      if (result.ok()) {
        return;
      }
      failure = result.stderr().strip();
    } catch (IOException unrunnable) {
      failure = unrunnable.getMessage();
    }
    throw new IllegalStateException(
        "Couldn't set this box's git "
            + field.key()
            + ": "
            + failure
            + "\n  Set it yourself and re-run: "
            + gitFix(field.key()));
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

  private String required(GitField field) {
    return gitValue(field)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "This box has no git "
                        + field.key()
                        + " set, so a project here can't take on your identity.\n"
                        + "  Set it once and re-run: "
                        + gitFix(field.key())));
  }

  /** The command that sets {@code key} for the account whose git config this identity reads. */
  private String gitFix(String key) {
    return (sudo ? "sudo " : "") + "git config --global " + key + " \"...\"";
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
