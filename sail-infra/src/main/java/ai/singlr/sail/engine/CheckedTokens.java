/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The git tokens a project's container, or its template, may be given: a person's stored ones for
 * the hosts its repos name, keyed as each repo URL spells its host ({@link
 * GitCredentials#forRepos}). Only {@link #of} makes them, once no repo asks for credentials that
 * none of them answers, and the applier and the provisioner take nothing else: no host command can
 * write a token past the refusal.
 */
public final class CheckedTokens {

  private final List<ProjectDefinition.Repo> repos;
  private final Map<String, String> byHost;

  private CheckedTokens(List<ProjectDefinition.Repo> repos, Map<String, String> byHost) {
    this.repos = listed(repos);
    this.byHost = Map.copyOf(byHost);
  }

  /**
   * {@code config}'s tokens from {@code person}'s {@code stored} ones, or a refusal ({@link
   * ErrorCode#LOGIN_NOT_STORED}) naming the {@code sail git login} that stores the missing one and,
   * when {@code env} sets a token variable, that Sail does not read it; on a box that names no
   * {@code person}, the setup that names one first.
   */
  public static CheckedTokens of(
      ProjectDefinition config,
      Optional<String> person,
      Map<String, String> stored,
      Predicate<String> asks,
      Map<String, String> env) {
    var tokens = GitCredentials.forRepos(stored, config.repos());
    var missing = GitCredentials.missing(config.repos(), tokens, asks);
    if (missing.isEmpty()) {
      return new CheckedTokens(config.repos(), tokens);
    }
    var host = missing.get().host();
    if (person.isEmpty()) {
      throw new ApiException(
          ErrorCode.LOGIN_NOT_STORED,
          host
              + " asks for credentials to fetch "
              + config.name()
              + "'s repo "
              + missing.get().repo().path()
              + ", and this box names no person to keep a token for it.",
          Remedy.of(
              "Name one with ",
              Remedy.setUp(),
              ", then store a token with ",
              Remedy.sail("git login", host),
              "."));
    }
    throw new ApiException(
        ErrorCode.LOGIN_NOT_STORED,
        missing.get().reason(config.name(), person.get()),
        missing.get().remedy(env));
  }

  /**
   * The tokens, keyed as each repo URL spells its host, for writing alongside {@code repos}: the
   * repos they were checked for, or a refusal, so tokens checked for one definition are never
   * written for another.
   */
  Map<String, String> byHostFor(List<ProjectDefinition.Repo> repos) {
    if (!this.repos.equals(listed(repos))) {
      throw new IllegalArgumentException(
          "These git tokens were checked for other repos than the ones being written.");
    }
    return byHost;
  }

  private static List<ProjectDefinition.Repo> listed(List<ProjectDefinition.Repo> repos) {
    return repos == null ? List.of() : List.copyOf(repos);
  }

  /** The tokens' values: what a built template is searched for, the ones already in it. */
  public Collection<String> values() {
    return byHost.values();
  }

  @Override
  public String toString() {
    return "CheckedTokens" + byHost.keySet();
  }
}
