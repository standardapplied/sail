/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Ids;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.CheckedTokens;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.TemplateBuild;
import ai.singlr.sail.engine.WorkspaceTemplate;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ChatStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceVerbsTest {

  @TempDir Path tempDir;

  private static ProjectDefinition acme() {
    return new ProjectDefinition(
        "acme",
        null,
        new ProjectDefinition.Resources(2, "4GB", "50GB"),
        null,
        null,
        null,
        List.of(new ProjectDefinition.Repo("https://github.com/acme/app.git", "app", null)),
        null,
        null,
        null,
        null);
  }

  /**
   * Scenario 21: a stop or delete of a spec's workspace is refused while a run of that spec is live
   * in it on this box; a run of another spec, of no spec, or on another box is no reason.
   */
  @Test
  void aWorkspaceIsHeldByALiveRunOfItsOwnSpecOnThisBoxAlone(@TempDir Path dir) throws Exception {
    var dbPath = dir.resolve("control-plane.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.named("acme")));
      var runs = new RunStore(db);
      for (var run :
          List.of(List.of("auth", "eng"), List.of("billing", "other"), List.of("", "eng"))) {
        Acting.system(
            () ->
                runs.reserveDispatch(
                    DateTimeUtils.newId().toString(),
                    "acme",
                    run.get(0).isEmpty() ? null : run.get(0),
                    run.get(1),
                    run.get(0).isEmpty() ? "adhoc" : "build",
                    List.of("app"),
                    "claude-code",
                    null,
                    "t",
                    "l",
                    "u"));
      }
    }

    var live = WorkspaceVerbs.liveRun("acme", () -> OperationsFactory.open(dbPath), "eng");

    assertTrue(live.test("auth"));
    assertFalse(live.test("billing"), "billing's run is another box's");
    assertFalse(live.test("docs"));
  }

  /**
   * I11: a workspace this box deleted takes its chats with it, and a project it destroyed every
   * chat of its; another's stay.
   */
  @Test
  void aDeletedWorkspaceOrDestroyedProjectTakesItsChatsFromTheBox() {
    var run = Ids.newId().toString();
    var dock = "dock-" + run.substring(0, 8);
    var quay = "quay-" + run.substring(0, 8);
    var places = List.of(dock + "/try", dock + "/pier", quay + "/try");
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      new SchemaManager(db).migrate();
      var sam = Acting.system(() -> new FdeStore(db).add("sam-" + run, "Sam", null, "member")).id();
      var chats = new ChatStore(db);
      for (var place : places) {
        var parts = place.split("/");
        chats.open(
            new ChatStore.Chat(
                place,
                sam,
                parts[0],
                parts[1],
                "claude-code",
                "default",
                false,
                null,
                false,
                false,
                0,
                DateTimeUtils.now(),
                null));
      }

      WorkspaceVerbs.forgetChats(dock, "try");
      var afterDelete = places.stream().map(id -> chats.find(id).isPresent()).toList();
      WorkspaceVerbs.forgetProjectChats(dock);

      assertEquals(List.of(false, true, true), afterDelete);
      assertEquals(
          List.of(false, true),
          places.subList(1, 3).stream().map(id -> chats.find(id).isPresent()).toList());
    }
  }

  /**
   * The command line's build refuses what the route refuses in the route's words, from the same
   * checks: a definition without resources, then a runner never set up.
   */
  @Test
  void theCommandLinesBuildRefusesInTheRoutesWords() throws Exception {
    var host = SailPaths.hostConfigPath();
    Files.deleteIfExists(host);
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      new SchemaManager(db).migrate();
      var bare =
          new ProjectDefinition(
              "bare-build", "", null, null, null, null, List.of(), null, null, null, null);
      Acting.system(
          () -> {
            new ProjectStore(db).upsert(bare);
            new ProjectStore(db)
                .upsert(
                    new ProjectDefinition(
                        "set-build",
                        "",
                        new ProjectDefinition.Resources(2, "4GB", "20GB"),
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null));
          });
    }

    var noResources =
        assertThrows(
            IllegalStateException.class,
            () -> WorkspaceVerbs.buildable("bare-build", OperatorCommand.PLAIN));
    var notSetUp =
        assertThrows(
            IllegalStateException.class,
            () -> WorkspaceVerbs.buildable("set-build", OperatorCommand.PLAIN));

    assertEquals(
        assertThrows(
                IllegalStateException.class,
                () ->
                    TemplateBuild.requireResources(
                        new ProjectDefinition(
                            "bare-build",
                            "",
                            null,
                            null,
                            null,
                            null,
                            List.of(),
                            null,
                            null,
                            null,
                            null)))
            .getMessage(),
        noResources.getMessage());
    assertEquals(
        "This runner is not set up, so no template can be built on it. Set it up first: sail host"
            + " init.",
        notSetUp.getMessage());
  }

  @Test
  void aTemplateBuildKeepsItsProgressApartFromTheProjectsOwn() {
    assertEquals(SailPaths.templateProvisionState("acme"), WorkspaceVerbs.progressOf("acme"));
    assertNotEquals(SailPaths.provisionState("acme"), WorkspaceVerbs.progressOf("acme"));
  }

  @Test
  void aTemplateIsBuiltFromTheBaseProfilesPoolAndItsDigestIsTheDefinitionsAlone() throws Exception {
    var named =
        new HostYaml(
            "dir", "devpool", null, "incusbr0", "singlr-base", "ubuntu/24.04", "6.0", null, null);
    var unnamed =
        new HostYaml(
            "dir", null, null, "incusbr0", "singlr-base", "ubuntu/24.04", "6.0", null, null);

    var source = source(named, none());

    assertEquals("devpool", source.basePool());
    assertEquals(ProjectProvisioner.inputs(acme(), Optional.empty()), source.inputs());
    assertEquals(HostYaml.DEFAULT_POOL, source(unnamed, none()).basePool());
  }

  /**
   * sail#421 N1: the built template is searched for the tokens this build was given, the ones
   * already in it, and no other: a stored token for a host the project does not name never enters
   * the template, not even as a search pattern.
   */
  @Test
  void aTemplateIsSearchedForTheTokensItsBuildWasGivenAndNoOther() {
    var host =
        new HostYaml(
            "dir", "devpool", null, "incusbr0", "singlr-base", "ubuntu/24.04", "6.0", null, null);
    var given =
        new WorkspaceVerbs.Keeper(
                Optional.of("eng"),
                Map.of(
                    "github.com", "ghp_stored_for_github", "gitlab.com", "glpat_stored_for_gitlab"),
                url -> true,
                Map.of())
            .check(acme());

    var source = source(host, given);

    assertEquals(List.of("ghp_stored_for_github"), List.copyOf(source.tokens()));
  }

  /**
   * Round 2, item 3: a host command hands the keeper a definition, never a probe; the keeper asks
   * with the real one, which says a host answering 401 to its refs wants credentials and one
   * answering 200 does not.
   */
  @Test
  void theKeeperAsksAReposHostWithTheRealProbe() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(
              exchange.getRequestURI().getPath().startsWith("/private") ? 401 : 200, -1);
          exchange.close();
        });
    server.start();
    try {
      var base = "http://127.0.0.1:" + server.getAddress().getPort();
      var asks = new WorkspaceVerbs.Keeper(Optional.of("eng"), Map.of()).asks();

      assertTrue(asks.test(base + "/private/info/refs?service=git-upload-pack"));
      assertFalse(asks.test(base + "/public/info/refs?service=git-upload-pack"));
    } finally {
      server.stop(0);
    }
  }

  private static CheckedTokens none() {
    return new WorkspaceVerbs.Keeper(Optional.empty(), Map.of(), url -> false, Map.of())
        .check(acme());
  }

  private static WorkspaceTemplate.Source source(HostYaml host, CheckedTokens tokens) {
    return WorkspaceVerbs.source(
        host,
        acme(),
        tokens,
        name -> false,
        "eng",
        ProvisionListener.NOOP,
        (into, tracker, listener) -> {},
        false);
  }

  /** A build from the command line is recorded as its person, else as the account sudo acts for. */
  @Test
  void aCommandLineBuildIsRecordedAsItsPersonElseTheAccountSudoActsFor() {
    var person = new WorkspaceVerbs.Keeper(Optional.of("eng"), Map.of(), url -> false, Map.of());
    var nobody =
        new WorkspaceVerbs.Keeper(
            Optional.empty(), Map.of(), url -> false, Map.of("SUDO_USER", "uday"));

    assertEquals("eng", person.who());
    assertEquals("uday", nobody.who());
  }
}
