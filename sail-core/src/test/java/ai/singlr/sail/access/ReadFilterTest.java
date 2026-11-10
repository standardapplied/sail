/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Actors;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * A row is read by where it is filed and nothing else: what a scoped actor is handed is the rows of
 * its projects, in their order, and an unrestricted reader is never asked where a row is filed.
 */
class ReadFilterTest {

  private static final Actor UNRESTRICTED = new Actor("ada", Role.ADMIN, Actor.Lane.API);
  private static final Actor OF_ALPHA =
      new Actor("sam", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.of(List.of("alpha")));
  private static final Actor OF_NOTHING =
      new Actor("nia", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.none());

  /** The project of each row id; a row the map does not hold is of no known project. */
  private static final Function<String, String> PROJECT =
      Map.of("a1", "alpha", "a2", "alpha", "b1", "bravo")::get;

  @Test
  void aScopedActorIsHandedTheRowsOfItsProjectsInTheirOrder() {
    var rows = List.of("b1", "a2", "unplaced", "a1");

    assertEquals(List.of("a2", "a1"), ReadFilter.readable(OF_ALPHA, rows, PROJECT));
    assertEquals(List.of(), ReadFilter.readable(OF_NOTHING, rows, PROJECT));
    assertEquals(rows, ReadFilter.readable(UNRESTRICTED, rows, PROJECT));
  }

  @Test
  void aRowOutsideTheScopeIsARowThatIsNotThere() {
    assertEquals(Optional.of("a1"), ReadFilter.readable(OF_ALPHA, Optional.of("a1"), PROJECT));
    assertEquals(Optional.empty(), ReadFilter.readable(OF_ALPHA, Optional.of("b1"), PROJECT));
    assertEquals(Optional.empty(), ReadFilter.readable(OF_ALPHA, Optional.of("unplaced"), PROJECT));
    assertEquals(Optional.empty(), ReadFilter.readable(OF_ALPHA, Optional.empty(), PROJECT));
    assertEquals(Optional.empty(), ReadFilter.readable(OF_NOTHING, Optional.of("a1"), PROJECT));
  }

  @Test
  void anUnrestrictedReaderAndSailItselfAreNeverAskedWhereARowIsFiled() {
    var asked = new ArrayList<String>();
    Function<String, String> filed =
        row -> {
          asked.add(row);
          return PROJECT.apply(row);
        };
    var rows = List.of("b1", "unplaced");

    assertEquals(rows, ReadFilter.readable(UNRESTRICTED, rows, filed));
    assertEquals(
        rows, ReadFilter.readable(Actors.SYSTEM.withScope(ProjectScope.none()), rows, filed));
    assertEquals(Optional.of("b1"), ReadFilter.readable(Actors.MAIN, Optional.of("b1"), filed));
    assertEquals(List.of(), asked);
    assertEquals(List.of(), ReadFilter.readable(OF_ALPHA, rows, filed));
    assertEquals(rows, asked);
  }

  @Test
  void aReadIsAccessesReadOfWhereTheRowIsFiledForEveryPrincipal() {
    var principals =
        List.of(
            UNRESTRICTED,
            OF_ALPHA,
            OF_NOTHING,
            Actors.VIEWER.withScope(ProjectScope.of(List.of("alpha"))),
            Actors.AGENT.withScope(ProjectScope.of(List.of("alpha"))),
            Actors.ROOM.withScope(ProjectScope.of(List.of("bravo"))),
            Actors.OWNER_SYNC.withScope(ProjectScope.of(List.of("alpha"))),
            Actors.SYSTEM.withScope(ProjectScope.none()));
    for (var actor : principals) {
      for (var project : new String[] {"alpha", "bravo", null}) {
        assertEquals(
            Access.decide(actor, Action.READ, Resources.project(project, null))
                instanceof Decision.Allowed,
            ReadFilter.reads(actor, () -> project),
            actor + " reading " + project);
      }
    }
  }
}
