/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Everything a workspace is given of the person it belongs to, and nothing of anyone else's (T2):
 * the git tokens they keep on this runner, their name and email as its git identity, and their SSH
 * keys, with the box's workstation key only when they are the box's own person. Read on the host
 * before a run is reserved or a workspace copied, and written into the workspace by {@link
 * Workspaces} alone; its {@link #toString} never shows a token.
 */
public record WorkspaceOwner(
    String handle, String name, String email, Map<String, String> tokens, List<String> keys) {

  public WorkspaceOwner {
    Objects.requireNonNull(handle, "handle");
    tokens = Map.copyOf(tokens);
    keys = List.copyOf(keys);
  }

  /** A person refused as a workspace's owner, saying why and what to do. */
  public static final class Refused extends IllegalStateException implements Remedied {
    private final String reason;
    private final transient Remedy remedy;

    Refused(String reason, Remedy remedy) {
      super(Remedied.message(reason, remedy));
      this.reason = reason;
      this.remedy = remedy;
    }

    @Override
    public String reason() {
      return reason;
    }

    @Override
    public Remedy remedy() {
      return remedy;
    }
  }

  /**
   * Person {@code handle} of this box's roster {@code fdes} as a workspace's owner, with this box's
   * registered workstation key when they are the box's person, {@code boxHandle}: the one lookup
   * every door makes.
   */
  public static WorkspaceOwner onThisBox(FdeStore fdes, String handle, String boxHandle) {
    if (fdes == null) {
      throw new IllegalStateException("This box keeps no roster, so no workspace has an owner.");
    }
    return of(fdes, handle, boxHandle, WorkstationIdentity.registered());
  }

  /**
   * The person {@code handle} of {@code fdes}' roster as a workspace's owner: their display name
   * (else their handle), email, git tokens and keys, and {@code workstation} too when {@code
   * handle} is {@code boxHandle}, the box's own person.
   *
   * @throws IllegalStateException when the box names no person, or the roster does not hold them
   */
  public static WorkspaceOwner of(
      FdeStore fdes, String handle, String boxHandle, Optional<SshPublicKey> workstation) {
    if (Strings.isBlank(handle)) {
      throw Remedied.state(
          "This box names no person of its own, and a workspace belongs to one.",
          Remedy.of("Set it up with ", Remedy.setUp(), "."));
    }
    var fde =
        fdes.byHandle(handle)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        handle + " is not on this box's roster, so no workspace can be theirs."));
    var keys =
        new ArrayList<>(
            fdes.sshKeys().listForFde(fde.id()).stream()
                .map(FdeSshKeyStore.SshKeyInfo::publicKey)
                .toList());
    if (handle.equals(boxHandle)) {
      workstation.map(SshPublicKey::line).filter(line -> !keys.contains(line)).ifPresent(keys::add);
    }
    return new WorkspaceOwner(
        handle,
        Strings.isBlank(fde.displayName()) ? handle : fde.displayName(),
        Objects.toString(fde.email(), ""),
        fdes.logins().gitTokens(fde.id()),
        keys);
  }

  /**
   * Refuses this owner for a workspace of {@code config} before anything is reserved or copied: one
   * with no email, since a workspace commits as its owner, or one who keeps no token for a repo's
   * host that {@code asks} says asks for credentials ({@link ShellExec#asksForCredentials}).
   */
  public void require(ProjectDefinition config, Predicate<String> asks) {
    if (Strings.isBlank(email)) {
      throw new Refused(
          handle + " has no email on this box, and a workspace commits as its owner.",
          Remedy.of("Set it with ", Remedy.sail("fde update", handle, "--email", "<email>"), "."));
    }
    var asking = GitCredentials.asking(config.repos(), this::keepsTokenFor, asks);
    if (!asking.isEmpty()) {
      var repo = asking.getFirst();
      var host = GitCredentials.extractHttpsHosts(List.of(repo)).getFirst();
      throw new Refused(
          host
              + " asks for credentials to fetch "
              + config.name()
              + "'s repo "
              + repo.path()
              + ", and "
              + handle
              + " keeps no token for it.",
          Remedy.of("Store one with ", Remedy.sail("git login", host), "."));
    }
  }

  /**
   * The repos of {@code repos} whose host this owner keeps a token for: all a workspace's token
   * store is written for, so a host without one never reaches a token from the environment.
   */
  List<ProjectDefinition.Repo> tokenRepos(List<ProjectDefinition.Repo> repos) {
    return (repos == null ? List.<ProjectDefinition.Repo>of() : repos)
        .stream()
            .filter(
                repo ->
                    GitCredentials.extractHttpsHosts(List.of(repo)).stream()
                        .anyMatch(this::keepsTokenFor))
            .toList();
  }

  /** This owner's tokens keyed as the hosts of a project's repos are spelled. */
  Map<String, String> tokensFor(List<ProjectDefinition.Repo> repos) {
    var spelled = new LinkedHashMap<String, String>();
    for (var host : GitCredentials.extractHttpsHosts(repos)) {
      var token = tokens.get(host.toLowerCase(Locale.ROOT));
      if (token != null) {
        spelled.put(host, token);
      }
    }
    return spelled;
  }

  private boolean keepsTokenFor(String host) {
    return tokens.containsKey(host.toLowerCase(Locale.ROOT));
  }

  @Override
  public String toString() {
    return "WorkspaceOwner[" + handle + " " + email + " tokens for " + tokens.keySet() + "]";
  }
}
