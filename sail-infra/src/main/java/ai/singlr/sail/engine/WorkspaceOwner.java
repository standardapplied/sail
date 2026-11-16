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

/** The workstation key goes only to the box's own person: it is their laptop's (T2). */
public record WorkspaceOwner(
    String handle, String name, String email, Map<String, String> tokens, List<String> keys) {

  public WorkspaceOwner {
    Objects.requireNonNull(handle, "handle");
    tokens = Map.copyOf(tokens);
    keys = List.copyOf(keys);
  }

  /** Its own type so a refusal at the give is told apart from a create that failed. */
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

  /** The one lookup every door makes, so none adds the workstation key by its own rule. */
  public static WorkspaceOwner onThisBox(FdeStore fdes, String handle, String boxHandle) {
    if (fdes == null) {
      throw new IllegalStateException("This box keeps no roster, so no workspace has an owner.");
    }
    return of(fdes, handle, boxHandle, WorkstationIdentity.registered());
  }

  /** A display name may be blank, and a commit still needs a name: the handle is it then. */
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

  /** A workspace commits as its owner, and fetches as them: without either it cannot work. */
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

  /** Only these, so a host without a token never falls back to one from the environment. */
  List<ProjectDefinition.Repo> tokenRepos(List<ProjectDefinition.Repo> repos) {
    return (repos == null ? List.<ProjectDefinition.Repo>of() : repos)
        .stream()
            .filter(
                repo ->
                    GitCredentials.extractHttpsHosts(List.of(repo)).stream()
                        .anyMatch(this::keepsTokenFor))
            .toList();
  }

  /** Keyed as the repo URL spells the host, which is the key the credential store matches. */
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
