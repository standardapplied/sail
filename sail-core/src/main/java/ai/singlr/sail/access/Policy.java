/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

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
 * host CLI, a push over sync. An agent reads its role's column too, its person's capped by its
 * lane, and its lane's column narrows it: a lane cell is yes (it narrows nothing), no, or {@link
 * Cell#OWN_WORK}, the lane's own grant whatever the role — what a run needs to report on itself and
 * to converse in its room, read-only as a room agent's role is. Whose the work is, is the role
 * column's to say.
 *
 * <p>Behaviour does not change while every rule moves onto this table, so a cell holds today's
 * answer where the agreed table differs, and the spec that changes it is named here:
 *
 * <ul>
 *   <li>Read: a room agent reads its person's whole scope; its room and project, in
 *       sail-agents-act-on-their-own-work.
 *   <li>Read a definition: a viewer may not, until sail-people-are-managed; a work agent may read
 *       any its person sees, until sail-agents-act-on-their-own-work narrows it to its own project.
 *   <li>Post, Create, Serve: a work agent may wherever its person may, until
 *       sail-agents-act-on-their-own-work narrows it to its own work. A room agent's own work is,
 *       until then, the conversations its person owns.
 *   <li>Change and Approve: a work agent may wherever its person owns the work; Claim, Restore and
 *       Configure: a work agent may as its person may. sail-agents-act-on-their-own-work narrows
 *       Change to its own work and refuses the rest.
 *   <li>Share: a work agent may share files wherever its person may, as the journal decides a file;
 *       the box's socket refuses every run credential the sharing of skills before it asks, until
 *       sail-agents-act-on-their-own-work gives that door this cell.
 *   <li>Every event but a run's narration: refused to an agent by the event rule's own fact of its
 *       lane ({@code EventAuthority}), whatever this table says of the action, until
 *       sail-agents-act-on-their-own-work gives an agent's actor its work.
 *   <li>Configure: any member may, until sail-people-are-managed gives it to a project's creator.
 *   <li>Operate a run: the row decides a stop; a run's log is read by anyone the run works for,
 *       whatever their role ({@link Access#ownsRun}), until sail-every-door-asks-access asks this
 *       row of the log doors too.
 *   <li>Narrate: an admin may narrate any run, where the agreed table gives it the runs this box
 *       executed; the event rule leans on it, so a run another box executed has no narrator here
 *       but an admin ({@code EventAuthority}), until sail-every-door-asks-access narrows it.
 *   <li>Operate the box: a viewer may not, until sail-every-door-asks-access gives a person their
 *       own box.
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
    /** Only on the agent's own work: its run, its spec, its room, its project. */
    OWN_WORK("own work");

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
    ROOM_AGENT("Read-only room agent");

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
     * The column {@code lane} narrows its role by, if any: an agent's lane does; a person's lanes
     * (the API, the CLI, a push over sync) carry their role whole.
     */
    static Optional<Column> lane(Actor.Lane lane) {
      return switch (lane) {
        case AGENT -> Optional.of(WORK_AGENT);
        case ROOM -> Optional.of(ROOM_AGENT);
        case CLI, API, SYNC, MAIN, SYSTEM -> Optional.empty();
      };
    }
  }

  /**
   * One action's cells, a column each. A role column never says {@link Cell#OWN_WORK}: own work is
   * an agent's. A lane column never says {@link Cell#OWNER}: it only narrows a role, whose own cell
   * says whose the work is.
   */
  record Row(Action action, Cell viewer, Cell member, Cell admin, Cell workAgent, Cell roomAgent) {

    Row {
      Stream.of(action, viewer, member, admin, workAgent, roomAgent)
          .forEach(Objects::requireNonNull);
      if (Stream.of(viewer, member, admin).anyMatch(OWN_WORK::equals)) {
        throw new IllegalArgumentException(
            "The row of " + action + " gives a role own work, which only an agent has.");
      }
      if (Stream.of(workAgent, roomAgent).anyMatch(OWNER::equals)) {
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
      };
    }
  }

  private static final List<Row> TABLE =
      List.of(
          new Row(Action.READ, YES, YES, YES, YES, YES),
          new Row(Action.READ_DEFINITION, NO, YES, YES, YES, NO),
          new Row(Action.POST, NO, YES, YES, YES, OWN_WORK),
          new Row(Action.CREATE, NO, YES, YES, YES, NO),
          new Row(Action.CHANGE, NO, OWNER, YES, YES, NO),
          new Row(Action.CLAIM, NO, YES, YES, YES, NO),
          new Row(Action.ASSIGN, NO, NO, YES, NO, NO),
          new Row(Action.APPROVE, NO, OWNER, YES, YES, NO),
          new Row(Action.DISPATCH, NO, OWNER, YES, NO, NO),
          new Row(Action.OPERATE_RUN, NO, OWNER, YES, NO, NO),
          new Row(Action.NARRATE, NO, OWNER, YES, OWN_WORK, OWN_WORK),
          new Row(Action.SHARE, NO, YES, YES, YES, NO),
          new Row(Action.SERVE, NO, YES, YES, YES, NO),
          new Row(Action.RESTORE, NO, YES, YES, YES, NO),
          new Row(Action.CONFIGURE, NO, YES, YES, YES, NO),
          new Row(Action.PRUNE, NO, OWNER, YES, NO, NO),
          new Row(Action.PURGE, NO, NO, YES, NO, NO),
          new Row(Action.MANAGE_PEOPLE, NO, NO, YES, NO, NO),
          new Row(Action.OPERATE_BOX, NO, OWNER, YES, NO, NO));

  private static final Map<Action, Row> ROWS = indexed(TABLE);

  private Policy() {}

  /**
   * Whether {@code reader} reads a conversation only as the voices that steer it say it (A6): every
   * lane the table gives a column of its own, an agent's, reads a room's messages through the
   * steering filter, its owners', its admins' and Sail's words, never a comment another member left
   * for people. The filter itself is the stores' query; which readers it binds is the table's.
   */
  public static boolean hearsOnlySteering(Actor reader) {
    return Column.lane(reader.lane()).isPresent();
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
