/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceOwnerTest {

  private static final SshPublicKey WORKSTATION =
      SshPublicKey.parse(TestSshKeys.ed25519("workstation", "uday@mac"));
  private static final SshPublicKey UDAY_KEY =
      SshPublicKey.parse(TestSshKeys.ed25519("uday", "uday@laptop"));
  private static final SshPublicKey ANA_KEY =
      SshPublicKey.parse(TestSshKeys.ed25519("ana", "ana@laptop"));

  @TempDir Path dir;
  private Sqlite db;
  private FdeStore fdes;

  @BeforeEach
  void roster() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
    var uday = fdes.add("uday", "Uday C", "uday@example.com", "admin");
    var ana = fdes.add("ana", null, "ana@example.com", "member");
    fdes.add("nomail", "No Mail", null, "member");
    fdes.sshKeys().add(uday.id(), UDAY_KEY);
    fdes.sshKeys().add(ana.id(), ANA_KEY);
    fdes.logins().storeGit(uday.id(), "github.com", "ghp_uday_0123456789ab");
  }

  @AfterEach
  void close() {
    db.close();
  }

  private static ProjectDefinition acme(String... urls) {
    var repos = new ArrayList<ProjectDefinition.Repo>();
    for (var i = 0; i < urls.length; i++) {
      repos.add(new ProjectDefinition.Repo(urls[i], i == 0 ? "api" : "web" + i, null));
    }
    return new ProjectDefinition(
        "acme", null, null, null, null, null, repos, null, null, null, null);
  }

  /** Scenario 2: the box's person is given their name, email, tokens, keys and the box's key. */
  @Test
  void theBoxsPersonIsTheirNameEmailTokensKeysAndTheWorkstationKey() {
    var owner = WorkspaceOwner.of(fdes, "uday", "uday", Optional.of(WORKSTATION));

    assertEquals(
        new WorkspaceOwner(
            "uday",
            "Uday C",
            "uday@example.com",
            Map.of("github.com", "ghp_uday_0123456789ab"),
            List.of(UDAY_KEY.line(), WORKSTATION.line()),
            fdes.byHandle("uday").orElseThrow().id()),
        owner);
    assertFalse(owner.toString().contains("ghp_uday_0123456789ab"), owner.toString());
  }

  /** Scenario 3: another person is their own alone, named by handle when they have no name. */
  @Test
  void anotherPersonIsTheirOwnAloneAndNamedByTheirHandleWhenTheyHaveNoName() {
    var owner = WorkspaceOwner.of(fdes, "ana", "uday", Optional.of(WORKSTATION));

    assertEquals(
        new WorkspaceOwner(
            "ana",
            "ana",
            "ana@example.com",
            Map.of(),
            List.of(ANA_KEY.line()),
            fdes.byHandle("ana").orElseThrow().id()),
        owner);
  }

  @Test
  void aBoxWithNoPersonAndAHandleOffTheRosterHaveNoOwner() {
    var none =
        assertThrows(
            IllegalStateException.class,
            () -> WorkspaceOwner.of(fdes, "", "", Optional.of(WORKSTATION)));
    var gone =
        assertThrows(
            IllegalStateException.class,
            () -> WorkspaceOwner.of(fdes, "zed", "uday", Optional.empty()));

    assertEquals(
        "This box names no person of its own, and a workspace belongs to one. Set it up with"
            + " "
            + Remedy.setUp().plain()
            + ".",
        none.getMessage());
    assertEquals(
        "zed is not on this box's roster, so no workspace can be theirs.", gone.getMessage());
  }

  /**
   * Scenario 7: an owner with no email is refused. Its remedy is right for whoever reads it: the
   * command for an admin, the same command for an admin to run for anyone else.
   */
  @Test
  void anOwnerWithNoEmailIsRefusedWithTheRemedyItsReaderCanUse() {
    var owner = WorkspaceOwner.of(fdes, "nomail", "uday", Optional.empty());

    var admin =
        assertThrows(
            IllegalStateException.class,
            () ->
                Actor.call(
                    new Actor("uday", Role.ADMIN, Actor.Lane.CLI),
                    () -> {
                      owner.require(acme(), url -> false);
                      return null;
                    }));
    var member =
        assertThrows(
            IllegalStateException.class,
            () ->
                Actor.call(
                    new Actor("nomail", Role.MEMBER, Actor.Lane.CLI),
                    () -> {
                      owner.require(acme(), url -> false);
                      return null;
                    }));

    assertEquals(
        "nomail has no email on this box, and a workspace commits as its owner. Set it on main"
            + " with sail fde update nomail --email <email>.",
        admin.getMessage());
    assertEquals(
        "nomail has no email on this box, and a workspace commits as its owner. Ask an admin to"
            + " run on main: sail fde update nomail --email <email>.",
        member.getMessage());
  }

  /** High 1 of the proof: a person a node marks disabled, as main's removal does, owns nothing. */
  @Test
  void aDisabledPersonIsNoOwner() {
    fdes.replicate("ana", null, null, "ana@example.com", "member", "disabled", null);

    var refused =
        assertThrows(
            WorkspaceOwner.Refused.class,
            () -> WorkspaceOwner.of(fdes, "ana", "uday", Optional.empty()));

    assertEquals(
        "ana is disabled on this box's roster, so no workspace can be theirs.",
        refused.getMessage());
  }

  /**
   * Scenarios 4 and 5: a repo whose host asks for credentials, and for which the owner keeps no
   * token, is refused naming the login that stores one; a public repo, or one whose host the owner
   * keeps a token for, is not, and the latter is never asked.
   */
  @Test
  void aRepoAskingForCredentialsTheOwnerKeepsNoTokenForIsRefusedNamingTheLogin() {
    var ana = WorkspaceOwner.of(fdes, "ana", "uday", Optional.empty());
    var uday = WorkspaceOwner.of(fdes, "uday", "uday", Optional.empty());
    var asked = new ArrayList<String>();

    var refused =
        assertThrows(
            IllegalStateException.class,
            () -> ana.require(acme("https://github.com/acme/api.git"), url -> true));
    ana.require(acme("https://github.com/acme/api.git"), url -> false);
    uday.require(
        acme("https://GitHub.com/acme/api.git"),
        url -> {
          asked.add(url);
          return true;
        });

    assertEquals(
        "github.com asks for credentials to fetch acme's repo api, and ana keeps no token for"
            + " it. Store one with sail git login github.com.",
        refused.getMessage());
    assertEquals(List.of(), asked, "a host the owner keeps a token for is never asked");
  }

  /**
   * The store is written for the repos whose host the owner keeps a token for alone, keyed as the
   * definition spells the host, so no host ever falls back to a token from the environment.
   */
  @Test
  void theStoreIsWrittenOnlyForHostsTheOwnerKeepsATokenForAsTheDefinitionSpellsThem() {
    var uday = WorkspaceOwner.of(fdes, "uday", "uday", Optional.empty());
    var config = acme("https://GitHub.com/acme/api.git", "https://gitlab.com/acme/web.git");

    var repos = uday.tokenRepos(config.repos());

    assertEquals(
        List.of("https://GitHub.com/acme/api.git"), repos.stream().map(r -> r.url()).toList());
    assertEquals(Map.of("GitHub.com", "ghp_uday_0123456789ab"), uday.tokensFor(repos));
    assertEquals(
        "https://oauth2:ghp_uday_0123456789ab@GitHub.com\n",
        GitCredentials.buildCredentialStore(uday.tokensFor(repos), repos));
  }
}
