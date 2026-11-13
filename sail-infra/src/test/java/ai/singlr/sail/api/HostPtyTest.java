/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The terminal facet's project gate: a session opens only in a project its person holds here. */
class HostPtyTest {

  private Sqlite db;
  private HostLanes.Pty pty;
  private final Incus incus = new Incus();

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    pty =
        new HostLanes.Pty(
            db,
            TestAuth.roles(db, new SyncConfig("main", null, "root", "box")),
            null,
            new BoxInstances(incus));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aTerminalOpensOnlyInAProjectItsPersonHoldsOnThisBoxWhateverTheirRole() throws IOException {
    var absent =
        "Project 'beta' is not in the catalog. Create it with 'sail project create beta', or sync"
            + " it from main with 'sail sync'.";
    var held =
        new Actor("ada", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.of(List.of("acme")));
    var viewer =
        new Actor("vic", Role.VIEWER, Actor.Lane.API).withScope(ProjectScope.of(List.of("acme")));
    var elsewhere =
        new Actor("ada", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.of(List.of("beta")));
    HeldProjects.hold(db, "beta");

    pty.admitProject("acme", new PtyIdentity.Person(held));
    pty.admitProject("acme", new PtyIdentity.Person(viewer));
    assertEquals(
        absent,
        assertThrows(
                Remedied.Io.class, () -> pty.admitProject("beta", new PtyIdentity.Person(held)))
            .getMessage());
    assertEquals(
        "Project 'acme' is not in the catalog. Create it with 'sail project create acme', or sync"
            + " it from main with 'sail sync'.",
        assertThrows(
                Remedied.Io.class,
                () -> pty.admitProject("acme", new PtyIdentity.Person(elsewhere)))
            .getMessage());
    assertEquals(
        "Project 'gone' is not in the catalog. Create it with 'sail project create gone', or sync"
            + " it from main with 'sail sync'.",
        assertThrows(
                Remedied.Io.class,
                () ->
                    pty.admitProject(
                        "gone",
                        new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API))))
            .getMessage());
  }

  @Test
  void anOpensInstanceIsAskedOfIncusByItsNameAloneWithinThreeSecondsAndAProjectCheckAsksNone()
      throws IOException {
    HeldProjects.hold(db, "acme-fix");
    var admin = new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API));

    pty.admitProject("acme-fix", admin);
    assertEquals(List.of(), incus.asked, "attach and tickets ask the project alone");
    pty.admitInstance("acme");
    var refused = assertThrows(Remedied.Io.class, () -> pty.admitInstance("acme-fix"));

    assertEquals(
        "Project acme-fix cannot take instance acme-fix: it is workspace fix of project acme."
            + " Delete the workspace (sail project workspace delete acme fix) or give the project"
            + " another name; the workspace itself is reached with sail project workspace shell"
            + " acme fix.",
        refused.getMessage());
    assertEquals(
        List.of(
            "incus list ^acme$ --format json within PT3S",
            "incus list ^acme-fix$ --format json within PT3S"),
        incus.asked);
  }

  @Test
  void anOpenIsRefusedSayingSoWhenIncusDoesNotAnswerInTimeOrAtAll() {
    var said =
        "Incus did not say within 3 s what instance 'slow' is, so no terminal was opened in it."
            + " Try again once Incus answers.";

    assertEquals(
        said, assertThrows(IOException.class, () -> pty.admitInstance("slow")).getMessage());
    var interrupted = assertThrows(IOException.class, () -> pty.admitInstance("interrupted"));
    assertEquals(said.replace("'slow'", "'interrupted'"), interrupted.getMessage());
    assertTrue(Thread.interrupted(), "the interrupt is kept");
    assertEquals(
        "incus list failed: Error: not authorized",
        assertThrows(IOException.class, () -> pty.admitInstance("denied")).getMessage());
  }

  /**
   * This box's Incus as a one-name lookup asks it: {@code acme} plain, {@code acme-fix} a workspace
   * of acme, {@code slow} never answering, {@code interrupted} interrupting the asker, {@code
   * denied} refusing; each ask recorded with its bound.
   */
  private static final class Incus implements ShellExec {
    final List<String> asked = new ArrayList<>();

    @Override
    public Result exec(List<String> command) {
      throw new AssertionError("asked without a bound: " + command);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws InterruptedException, TimeoutException {
      asked.add(String.join(" ", command) + " within " + timeout);
      return switch (command.get(2)) {
        case "^acme$" -> new Result(0, "[{\"name\": \"acme\", \"config\": {}}]", "");
        case "^acme-fix$" ->
            new Result(
                0,
                "[{\"name\": \"acme-fix\", \"config\": {\"user.sail.project\": \"acme\","
                    + " \"user.sail.workspace\": \"fix\"}}]",
                "");
        case "^slow$" -> throw new TimeoutException("timed out");
        case "^interrupted$" -> throw new InterruptedException();
        default -> new Result(1, "", "Error: not authorized");
      };
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
