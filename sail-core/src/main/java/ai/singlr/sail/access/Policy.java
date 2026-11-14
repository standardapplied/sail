/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import static ai.singlr.sail.access.Policy.Cell.ITS_OWN;
import static ai.singlr.sail.access.Policy.Cell.NO;
import static ai.singlr.sail.access.Policy.Cell.OWNER;
import static ai.singlr.sail.access.Policy.Cell.OWN_WORK;
import static ai.singlr.sail.access.Policy.Cell.YES;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The permission table: one row per {@link Action}, one column per kind of principal, held once
 * here and rendered into ARCHITECTURE.md ({@link #table}) so the doc cannot drift from it.
 *
 * <p>A person reads the column of their role, on whichever lane they came in by: the HTTP API, the
 * host CLI, a push over sync. In a project container, where whatever runs can read the box's
 * credential, the person's role is narrowed by the in-container credential's column. An agent reads
 * its role's column too, its person's capped by its lane, and its lane's column narrows it: a lane
 * cell is yes (it narrows nothing), no, {@link Cell#OWN_WORK}, which narrows the role to the
 * agent's own work, or {@link Cell#ITS_OWN}, the lane's own grant on its own work whatever the role
 * — what a run needs to report on itself and to converse in its room, read-only as a room agent's
 * role is. Whose the work is, is the role column's to say. A room agent reads its room's project
 * alone, which the role rule's scope says, not a cell.
 *
 * <p>A cell holds today's answer where the agreed table differs, and the spec that changes it is
 * named here:
 *
 * <ul>
 *   <li>Read a definition: a viewer may not, until sail-people-are-managed.
 *   <li>Configure: any member may, until sail-people-are-managed gives it to a project's creator.
 * </ul>
 */
public final class Policy {

  /** What a column allows for one action. */
  enum Cell {
    /** Never. */
    NO("no"),
    /** Always. */
    YES("yes"),
    /** When the principal is an admin or acts for one of the resource's owners. */
    OWNER("owner"),
    /**
     * Only on the agent's own work ({@code Access.ownWork}): its spec, its room, its project, the
     * specs it made; there, as its role allows.
     */
    OWN_WORK("own work"),
    /**
     * The lane's own grant on its own work, whatever its role: a run narrating itself, a room agent
     * posting in its room.
     */
    ITS_OWN("its own");

    private final String label;

    Cell(String label) {
      this.label = label;
    }

    /** The cell as the permission table shows it. */
    String label() {
      return label;
    }
  }

  /** A kind of principal: a role a person holds, or a lane an agent or a credential comes in by. */
  enum Column {
    /** A person whose role is {@link Role#VIEWER}. */
    VIEWER("Viewer"),
    /** A person whose role is {@link Role#MEMBER}. */
    MEMBER("Member"),
    /** A person whose role is {@link Role#ADMIN}. */
    ADMIN("Admin"),
    /** A build, review or fix run's principal: {@link Actor.Lane#AGENT}. */
    WORK_AGENT("Work agent"),
    /** A read-only room member's principal: {@link Actor.Lane#ROOM}. */
    ROOM_AGENT("Read-only room agent"),
    /** The box's credential presented in a project container: {@link Actor.Lane#CONTAINER}. */
    CONTAINER("In-container credential");

    private final String label;

    Column(String label) {
      this.label = label;
    }

    /** The column's header in the permission table. */
    String label() {
      return label;
    }

    /** The column a person of {@code role} reads. */
    static Column role(Role role) {
      return switch (role) {
        case VIEWER -> VIEWER;
        case MEMBER -> MEMBER;
        case ADMIN -> ADMIN;
      };
    }

    /**
     * The column {@code lane} narrows its role by, if any: an agent's lane and the in-container
     * credential's do; a person's lanes outside a container (the API, the CLI, a push over sync)
     * carry their role whole.
     */
    static Optional<Column> lane(Actor.Lane lane) {
      return switch (lane) {
        case AGENT -> Optional.of(WORK_AGENT);
        case ROOM -> Optional.of(ROOM_AGENT);
        case CONTAINER -> Optional.of(CONTAINER);
        case CLI, API, SYNC, MAIN, SYSTEM -> Optional.empty();
      };
    }
  }

  /**
   * One action's cells, a column each. A role column never says {@link Cell#OWN_WORK} or {@link
   * Cell#ITS_OWN}: own work is an agent's. A lane column never says {@link Cell#OWNER}: it only
   * narrows a role, whose own cell says whose the work is.
   */
  record Row(
      Action action,
      Cell viewer,
      Cell member,
      Cell admin,
      Cell workAgent,
      Cell roomAgent,
      Cell container) {

    Row {
      Stream.of(action, viewer, member, admin, workAgent, roomAgent, container)
          .forEach(Objects::requireNonNull);
      if (Stream.of(viewer, member, admin).anyMatch(cell -> cell == OWN_WORK || cell == ITS_OWN)) {
        throw new IllegalArgumentException(
            "The row of " + action + " gives a role own work, which only an agent has.");
      }
      if (Stream.of(workAgent, roomAgent, container).anyMatch(OWNER::equals)) {
        throw new IllegalArgumentException(
            "The row of " + action + " gives a lane ownership, which only a role decides.");
      }
    }

    Cell cell(Column column) {
      return switch (column) {
        case VIEWER -> viewer;
        case MEMBER -> member;
        case ADMIN -> admin;
        case WORK_AGENT -> workAgent;
        case ROOM_AGENT -> roomAgent;
        case CONTAINER -> container;
      };
    }
  }

  private static final List<Row> TABLE =
      List.of(
          new Row(Action.READ, YES, YES, YES, YES, YES, YES),
          new Row(Action.READ_DEFINITION, NO, YES, YES, OWN_WORK, NO, YES),
          new Row(Action.READ_MEASURES, YES, YES, YES, OWN_WORK, ITS_OWN, NO),
          new Row(Action.POST, NO, YES, YES, OWN_WORK, ITS_OWN, YES),
          new Row(Action.CREATE, NO, YES, YES, OWN_WORK, NO, YES),
          new Row(Action.CHANGE, NO, OWNER, YES, OWN_WORK, NO, YES),
          new Row(Action.CLAIM, NO, YES, YES, NO, NO, YES),
          new Row(Action.ASSIGN, NO, NO, YES, NO, NO, NO),
          new Row(Action.APPROVE, NO, OWNER, YES, NO, NO, NO),
          new Row(Action.DISPATCH, NO, OWNER, YES, NO, NO, NO),
          new Row(Action.OPERATE_RUN, NO, OWNER, YES, NO, NO, NO),
          new Row(Action.NARRATE, NO, OWNER, YES, ITS_OWN, ITS_OWN, NO),
          new Row(Action.SHARE, NO, YES, YES, NO, NO, NO),
          new Row(Action.SERVE, NO, YES, YES, OWN_WORK, NO, YES),
          new Row(Action.RESTORE, NO, YES, YES, NO, NO, NO),
          new Row(Action.CONFIGURE, NO, YES, YES, NO, NO, NO),
          new Row(Action.PRUNE, NO, OWNER, YES, NO, NO, NO),
          new Row(Action.PURGE, NO, NO, YES, NO, NO, NO),
          new Row(Action.KEEP_LOGINS, YES, YES, YES, NO, NO, NO),
          new Row(Action.MANAGE_PEOPLE, NO, NO, YES, NO, NO, NO),
          new Row(Action.OPERATE_BOX, OWNER, OWNER, YES, NO, NO, NO));

  private static final Map<Action, Row> ROWS = indexed(TABLE);

  private Policy() {}

  /**
   * Whether {@code reader} reads a conversation only as the voices that steer it say it (A6): an
   * agent's lanes read a room's messages through the steering filter, its owners', its admins' and
   * Sail's words, never a comment another member left for people. The in-container credential is a
   * person's, who reads every voice. The filter itself is the stores' query; the readers it binds
   * are the agents' lanes, not the in-container credential.
   */
  public static boolean hearsOnlySteering(Actor reader) {
    return reader.agentLane();
  }

  /** What {@code column} allows for {@code action}. */
  static Cell cell(Action action, Column column) {
    return ROWS.get(action).cell(column);
  }

  /** The permission table as Markdown, the section ARCHITECTURE.md carries verbatim. */
  static String table() {
    var lines = new ArrayList<String>();
    var header = new ArrayList<>(List.of("Action", "Covers"));
    Arrays.stream(Column.values()).map(Column::label).forEach(header::add);
    lines.add(line(header));
    lines.add(line(header.stream().map(cell -> "---").toList()));
    for (var row : TABLE) {
      var cells = new ArrayList<>(List.of(row.action().label(), row.action().covers()));
      Arrays.stream(Column.values()).map(column -> row.cell(column).label()).forEach(cells::add);
      lines.add(line(cells));
    }
    return String.join("\n", lines);
  }

  /** {@code rows} by action, refused unless every action has exactly one, in declaration order. */
  static Map<Action, Row> indexed(List<Row> rows) {
    var actions = rows.stream().map(Row::action).toList();
    if (!actions.equals(List.of(Action.values()))) {
      throw new IllegalArgumentException(
          "The permission table must hold one row per action, in order: "
              + List.of(Action.values())
              + ", not "
              + actions
              + ".");
    }
    var indexed = new EnumMap<Action, Row>(Action.class);
    rows.forEach(row -> indexed.put(row.action(), row));
    return indexed;
  }

  private static String line(List<String> cells) {
    return "| " + String.join(" | ", cells) + " |";
  }
}
