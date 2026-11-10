/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

/**
 * What a principal asks to do: the nineteen actions that cover every door and sync lane, each a row
 * of {@link Policy}. A type rule names the action, or actions, a revision takes, and every one of
 * them must be allowed. {@code label} and {@code covers} are the row as ARCHITECTURE.md shows it;
 * {@code verb} and {@code object} are how a refusal words it ({@link #act}).
 */
public enum Action {
  /** Reading a spec, room, message, run, file or project: what scope and every list admit by. */
  READ("Read", "specs, rooms, messages, runs, files, projects", "read"),
  /** Reading a project's definition, which holds its services' settings and secrets. */
  READ_DEFINITION(
      "Read a definition",
      "a project's definition",
      "read",
      "a project's definition, which holds its services' secrets"),
  /** Posting a message in a conversation: a room, or a spec's own room. */
  POST("Post", "a message in a conversation", "post in"),
  /**
   * Creating a spec, a room or a project. A spec born in another room also takes that room's owners
   * ({@link Resource#bornIn}).
   */
  CREATE("Create", "a spec, a room, a project", "create"),
  /**
   * Editing, deleting or restoring a spec or a room, and settling a conflict a sync parked: keeping
   * the local version, merging, or adopting main's side.
   */
  CHANGE("Change", "edit, delete or restore a spec or room; settle a parked conflict", "change"),
  /**
   * Taking an unassigned spec for the FDE the principal acts as. One born in a room also takes that
   * room's owners, since its owner gains a voice there. Any other move of the assignee is {@link
   * #ASSIGN}.
   */
  CLAIM(
      "Claim",
      "take an unassigned spec; one born in a room also needs that room's owners",
      "claim"),
  /** Giving a spec to someone, or moving it from one FDE to another: work changes hands. */
  ASSIGN("Assign", "give or move a spec to someone", "assign"),
  /** The human gates on work: promoting a draft, approving or dismissing findings, marking done. */
  APPROVE("Approve", "promote a draft to ready; approve or dismiss findings; mark done", "approve"),
  /**
   * Starting work on this box: dispatching or resuming a spec, seating or removing a room's agent.
   * The roster a seating writes is the journal's to decide, as a change of the room. Where the work
   * runs (its assignee's box, its room owner's box) is placement, decided after this, by the
   * dispatch rules.
   */
  DISPATCH("Dispatch", "dispatch or resume a spec; seat or remove a room agent", "dispatch"),
  /** Stopping a run, or reading its log: what the people the run works for may do with it. */
  OPERATE_RUN("Operate a run", "stop it; read its log", "stop"),
  /**
   * A run's reports about itself: its lifecycle events, its session, the acknowledgements of its
   * inbox. Whether the run executed on this box stays the event rule's own fact.
   */
  NARRATE("Narrate", "a run's reports about itself: events, session, inbox acks", "narrate"),
  /** Adding, changing or removing a project's shared files and skills. */
  SHARE(
      "Share",
      "add, change or remove project files and skills",
      "share",
      "files and skills in projects"),
  /** Starting and stopping a project's processes. */
  SERVE(
      "Serve", "start and stop the project's processes", "start or stop", "a project's processes"),
  /** Restoring or deleting one of a project's snapshots. */
  RESTORE("Restore", "restore or delete a snapshot", "restore or delete", "snapshots"),
  /** Changing a project's definition: its repos, runtimes, agent, processes and resources. */
  CONFIGURE("Configure", "change a project's definition", "configure"),
  /** Erasing a spec everywhere, once it is archived, cancelled or deleted: it cannot come back. */
  PRUNE("Prune", "erase a spec everywhere, once archived, cancelled or deleted", "prune"),
  /**
   * Erasing a whole project everywhere, or sweeping by a retention policy: whoever's work it is.
   */
  PURGE("Purge", "erase a project everywhere; a retention sweep", "purge"),
  /** Changing the roster: adding, re-roling, granting, removing, pairing, minting tokens. */
  MANAGE_PEOPLE("Manage people", "add, change role, grant, remove, pair, mint tokens", "manage"),
  /** Acting for a box: a sync round, its sync health and bus stats, the main it points at. */
  OPERATE_BOX(
      "Operate the box",
      "sync now; read sync health and bus stats; point the box at another main",
      "operate");

  private final String label;
  private final String covers;
  private final String verb;
  private final String object;

  Action(String label, String covers, String verb) {
    this(label, covers, verb, null);
  }

  Action(String label, String covers, String verb, String object) {
    this.label = label;
    this.covers = covers;
    this.verb = verb;
    this.object = object;
  }

  /** The action's name in the permission table. */
  String label() {
    return label;
  }

  /** What the action covers, as the permission table says it. */
  String covers() {
    return covers;
  }

  /** The verb a refusal of this action uses: "change". */
  String verb() {
    return verb;
  }

  /**
   * What a refusal says a principal cannot do, taking this action on resources of {@code kind}: the
   * verb before what it acts on, which is the kind's plural unless the action names its own
   * ("change specs", "restore or delete snapshots"). Taking agents in and out of a room is
   * dispatching there, said as managing its membership; changing a conflict is resolving it.
   */
  public String act(Resource.Kind kind) {
    if (this == DISPATCH && kind == Resource.Kind.ROOM) {
      return "manage room membership";
    }
    if (this == CHANGE && kind == Resource.Kind.CONFLICT) {
      return "resolve conflicts";
    }
    return verb + " " + (object != null ? object : kind.plural());
  }
}
