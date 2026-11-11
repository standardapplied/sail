/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Whoami;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.PrincipalHandle;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A1, one decision, swept: outside {@code ai.singlr.sail.access}, {@code RoleRule} (which resolves
 * a credential to its principal, whose resolvers are its own) and {@code FdeStore} (the roster's
 * rules), no main class reads a role, a lane or a scope but the members {@link #ALLOWED} names,
 * each with the spec that deletes it or the fact, not a permission, that needs it to know which
 * fact applies. Read from the compiled classes of every module, a member at a time, a lambda
 * counted as the member it is written in, it sees a call of:
 *
 * <ul>
 *   <li>{@link Actor#isAdmin}, {@link Actor#isSail}, {@link Actor#role}, {@link Actor#lane}, {@link
 *       Actor#agentLane}, {@link Actor#scope}, {@link Actor#actsFor}, an agent's {@link
 *       Actor#work}, and the lane tests {@link Actor#peer} and {@link Actor#owner};
 *   <li>{@link Actor#withScope} and {@link Actor#withWork}, which widen or narrow a scope or an
 *       agent's work without asking one;
 *   <li>{@code PrincipalHandle#namesRun}, {@code #matches} and {@code #runOf}, whether a handle is
 *       a run's principal and which run's, and {@code ChangeLog#bornBy}, who made a spec: what an
 *       agent's own work is made of, so a second test of it beside {@code Access} is seen;
 *   <li>the factories that mint a principal with its role, {@link Actor#agentPrincipal}, {@link
 *       Actor#roomPrincipal} and {@link Actor#main}, so a door that binds one instead of asking
 *       {@code RoleRule} is seen;
 *   <li>{@link Whoami#of}, the role read whoami shows, outside the two whoami handlers;
 *   <li>{@link Role#fromAttribute}, {@link Role#cappedBy}, {@code Role.values()}, {@code
 *       Role.valueOf} or a role's order;
 *   <li>{@code RunRow#readOnlyLane};
 * </ul>
 *
 * and a {@link Role} constant (so an {@code Actor} built with a role literal), a {@code switch}
 * over a role, a type check of one, any call that returns one, a type check of the socket's run or
 * box caller, and an audience asked of a project in Java, each as a call, a field, a type check, a
 * class constant, or what an {@code invokedynamic} hands its bootstrap. It does not see {@link
 * Actor#system}: this box's own machinery binds it in about twenty places, so a door that binds it
 * instead of its caller is a finding for review, not for this sweep (its self-test holds one). Nor
 * does it see a role, a lane or a scope read as data: in SQL, from a map, or through a type that
 * hides it, so a filter inside a query takes its scope from {@code Access.audience}. {@code Actor},
 * {@code Role}, {@code PrincipalHandle} and the socket's callers define what is read, so their own
 * members are not swept, nor their nested classes, matched by exact name (a class that only shares
 * a name's prefix is swept). An entry that no longer matches a read fails too, so the list shrinks
 * as the later specs land.
 */
class OneDecisionSweepTest {

  private static final String IDENTITY = "ai/singlr/sail/identity/";
  private static final String ACTOR = IDENTITY + "Actor";
  private static final String ROLE = IDENTITY + "Role";
  private static final String ROLE_RULE = IDENTITY + "RoleRule";
  private static final String RUN_ROW = "ai/singlr/sail/store/RunStore$RunRow";
  private static final String PRINCIPAL_HANDLE = IDENTITY + "PrincipalHandle";
  private static final String CHANGE_LOG = "ai/singlr/sail/store/ChangeLog";
  private static final String CALLER = "ai/singlr/sail/api/LocalApiRouter$Caller$";
  private static final String SCOPE = IDENTITY + "ProjectScope";
  private static final String WHOAMI = "ai/singlr/sail/access/Whoami";
  private static final String AUDIENCE = "ai/singlr/sail/access/Access#audience";
  private static final Set<String> SCOPE_ASKED = Set.of(SCOPE + "#unrestricted", SCOPE + "#admits");

  /** The members whose call reads a role, a lane, a scope or whom an actor acts for. */
  private static final Map<String, Set<String>> READS =
      Map.of(
          ACTOR,
          Set.of(
              "isAdmin",
              "isSail",
              "role",
              "lane",
              "agentLane",
              "scope",
              "work",
              "withWork",
              "actsFor",
              "agentPrincipal",
              "roomPrincipal",
              "main",
              "withScope",
              "owner",
              "peer"),
          WHOAMI,
          Set.of("of"),
          ROLE,
          Set.of("fromAttribute", "cappedBy", "ordinal", "compareTo", "values", "valueOf"),
          RUN_ROW,
          Set.of("readOnlyLane"),
          PRINCIPAL_HANDLE,
          Set.of("namesRun", "matches", "runOf"),
          CHANGE_LOG,
          Set.of("bornBy"));

  private static final List<String> EXEMPT =
      List.of(
          "ai/singlr/sail/access/",
          ROLE_RULE,
          "ai/singlr/sail/store/FdeStore",
          ACTOR,
          PRINCIPAL_HANDLE,
          ROLE,
          CALLER);

  private static final String RETURNS_ROLE = "<returns a role>";
  private static final String ROLE_SWITCH = "<switch over a role>";

  private static final String API = "ai/singlr/sail/api/";
  private static final String RULES = "ai/singlr/sail/authority/";
  private static final String STORES = "ai/singlr/sail/store/";
  private static final String SYNC_SERVER = "ai/singlr/sail/sync/SyncRpcServer";

  /** Every member that still reads a role, a lane or a scope, and why it may. */
  private static final Map<String, String> ALLOWED = allowed();

  private static Map<String, String> allowed() {
    var allowed = new TreeMap<String, String>();
    allowed.put(
        API + "Event$Publisher#of", "fact: the role and lane an event's publisher acted on");
    allowed.put(API + "LocalApiRouter#events", "fact: only a run credential has a run to narrate");
    allowed.put(
        API + "LocalApiRouter#aRunsOwn", "fact: the box credential has no run to deliver to");
    allowed.put(
        API + "LocalApiRouter#aRoomsOrARunsOwn",
        "fact: the box credential has no session to report but a room's");
    allowed.put(
        API + "LocalApiRouter#runSession",
        "fact: only a run credential has a run to report a session onto; the box reports a room's");
    allowed.put(
        API + "LocalApiRouter#markDeliveredOnSelfRead",
        "fact: a run's read of its own room is a delivery");
    allowed.put(API + "RoomCommitGuard#guardRoomRun", "fact: which runs are a room's wakes");
    allowed.put(
        RULES + "EventAuthority#believed",
        "fact: an agent retells nothing, so no announcement of its is taken at its word");
    allowed.put(RULES + "Attribution#decide", "fact: a push names only its pusher (attribution)");
    allowed.put(
        RULES + "Attribution#made",
        "fact: who made a spec and its own room, the one read of a maker (attribution)");
    for (var named : List.of("mayWriteAs", "principalOfOwnedRun")) {
      allowed.put(
          RULES + "Attribution#" + named,
          "fact: a push names a principal of a run its pusher owns (attribution)");
    }
    allowed.put(
        API + "GlobalSpecOperations#validAssignee",
        "fact: an assignee is an FDE, never a run's principal (the field's shape)");
    allowed.put(
        STORES + "Decidability#actorRun",
        "fact: a revision waits for the run its author names to land (decidability)");
    allowed.put(
        STORES + "Decidability#message",
        "fact: a post waits for the run its author names to land (decidability)");
    allowed.put(
        STORES + "RunStore#principalsOf",
        "fact: the posts a denied run's own principals made leave with it");
    allowed.put(RULES + "MessageAuthority#mayPostAs", "fact: as whom a pusher posts (attribution)");
    allowed.put(
        RULES + "EventAuthority#refused",
        "fact: whose work a refused event's words describe, and an agent's words and kind its"
            + " own");
    allowed.put(STORES + "Snapshots#creator", "fact: whom a write names its creator (attribution)");
    allowed.put(
        STORES + "Snapshots#adoptedCreator", "fact: whom a write names its creator (attribution)");
    allowed.put(STORES + "RoomStore$RoomSchema#apply", "fact: a room main decided is adopted");
    allowed.put(STORES + "RoomStore$RoomSchema#creatorOf", "fact: a room main decided is adopted");
    allowed.put(RULES + "RunAuthority#decide", "fact: which box executed a pushed run");
    allowed.put(RULES + "WriteAuthority#local", "fact: which box decides a write, main a push");
    allowed.put(SYNC_SERVER + "#<init>", "fact: a sync server serves a sync session alone");
    allowed.put(
        SYNC_SERVER + "#serve",
        "fact: only a scoped session is served over main's change heads, which its scope rule"
            + " reads; an unscoped one has none to ask");
    for (var content : List.of("requireServed", "serving", "receiveContent", "unshared")) {
      allowed.put(
          SYNC_SERVER + "#" + content,
          "audience: content named by hash alone is read by a scoped session only as main served"
              + " it rows this session, the Read rule where no row names its project");
    }
    allowed.put(API + "ApiRouter#whoami", "fact: whoami shows the caller its own standing");
    allowed.put(API + "LocalApiRouter#whoami", "fact: whoami shows the caller its own standing");
    for (var journal : List.of("appendAuthored", "appendSynced", "erase")) {
      allowed.put(
          STORES + "ChangeLog#" + journal,
          "fact: the journal records which box a revision came from");
    }
    allowed.put(
        SYNC_SERVER + "#currentOf", "fact: main answers a pusher what it took from that box");
    for (var adopts :
        List.of(
            "ai/singlr/sail/engine/SyncOperations#sync",
            STORES + "LegacyConversion#adopt",
            STORES + "MessageStore#resolveConflict",
            STORES + "RevisionJournal#acknowledge",
            STORES + "RevisionJournal#resolveConflict",
            "ai/singlr/sail/sync/NodeRound#acknowledgeHeld",
            "ai/singlr/sail/sync/NodeRound#acknowledgeOversized",
            "ai/singlr/sail/sync/PagedSyncSession#acknowledgeLostAnswers",
            "ai/singlr/sail/sync/PagedSyncSession#adoptErasure",
            "ai/singlr/sail/sync/PagedSyncSession#withdrawLost",
            "ai/singlr/sail/sync/Settlement#revertToBase",
            "ai/singlr/sail/sync/Settlement#settleGone",
            "ai/singlr/sail/sync/SyncEngine$Round#adopt",
            "ai/singlr/sail/sync/SyncEngine$Round#converged",
            "ai/singlr/sail/sync/SyncEngine$Round#settleAccepted")) {
      allowed.put(adopts, "fact: a node adopts what main decided, as main (Access step 1)");
    }
    return allowed;
  }

  @Test
  void noMemberOutsideTheAccessPackageReadsARoleALaneOrAScopeButTheNamedOnes() throws Exception {
    var reads = new TreeSet<String>();
    for (var bytes : OneDecisionPointTest.everyMainClass()) {
      reads.addAll(reads(bytes));
    }

    assertEquals(List.of(), findings(reads, ALLOWED.keySet()));
  }

  @Test
  void aReadTheListDoesNotNameAndAnEntryThatNamesNoReadAreBothFindings() {
    assertEquals(
        List.of(
            "a member reads a role, a lane or a scope the allowlist does not name: a#leak",
            "an allowlisted member no longer reads one, so its entry goes: a#gone"),
        findings(Set.of("a#leak", "a#kept"), Set.of("a#kept", "a#gone")));
  }

  @Test
  void theSweepFlagsEveryReadOfARoleALaneOrAScope() throws IOException {
    try (var bytes = Leaky.class.getResourceAsStream("OneDecisionSweepTest$Leaky.class")) {
      var leaky = "ai/singlr/sail/api/OneDecisionSweepTest$Leaky#";
      assertEquals(
          Set.of(
              leaky + "admin",
              leaky + "role",
              leaky + "lane",
              leaky + "agent",
              leaky + "work",
              leaky + "worked",
              leaky + "inALambda",
              leaky + "sail",
              leaky + "scope",
              leaky + "actsFor",
              leaky + "elevated",
              leaky + "minted",
              leaky + "viaAttribute",
              leaky + "viaSwitch",
              leaky + "viaPattern",
              leaky + "returnsARole",
              leaky + "roomWake",
              leaky + "audience",
              leaky + "widened",
              leaky + "standing",
              leaky + "peer",
              leaky + "owner",
              leaky + "asMain",
              leaky + "audienceInALambda",
              leaky + "fromTheRoles",
              leaky + "everyRole",
              leaky + "maker",
              leaky + "aRunsOwn"),
          reads(bytes.readAllBytes()),
          "every member but asTheBox: a door binding Actor.system() is not seen");
    }
  }

  @Test
  void onlyTheClassesThatDefineWhatIsReadAreExemptNotOnesThatShareTheirNamesPrefix() {
    assertTrue(exempt("ai/singlr/sail/identity/Actor"));
    assertTrue(exempt("ai/singlr/sail/identity/Actor$Lane"));
    assertTrue(exempt("ai/singlr/sail/access/Policy"));
    assertFalse(exempt("ai/singlr/sail/identity/ActorLookups"));
    assertFalse(exempt("ai/singlr/sail/store/FdeStoreFacade"));
  }

  /** A source of roles that hides where they come from, as a door's resolver might. */
  @FunctionalInterface
  private interface Roles {
    Role of(String handle);
  }

  /** What no class outside the access package does. */
  private static final class Leaky {
    boolean admin(Actor actor) {
      return actor.isAdmin();
    }

    boolean role(Actor actor) {
      return actor.role() == Role.ADMIN;
    }

    boolean lane(Actor actor) {
      return actor.lane() == Actor.Lane.API;
    }

    boolean agent(Actor actor) {
      return actor.agentLane();
    }

    boolean work(Actor actor) {
      return actor.work() != null;
    }

    Actor worked(Actor actor) {
      return actor.withWork(null);
    }

    boolean inALambda(List<Actor> actors) {
      return actors.stream().anyMatch(Actor::isAdmin);
    }

    boolean sail(Actor actor) {
      return actor.isSail();
    }

    boolean scope(Actor actor) {
      return actor.scope().admits("acme");
    }

    boolean actsFor(Actor actor) {
      return actor.actsFor("ada");
    }

    Actor elevated(String handle) {
      return new Actor(handle, Role.ADMIN, Actor.Lane.CLI);
    }

    Actor minted(String handle) {
      return Actor.agentPrincipal(handle, "ada");
    }

    Object viaAttribute(String attribute) {
      return Role.fromAttribute(attribute);
    }

    int viaSwitch(Role role) {
      return switch (role) {
        case VIEWER -> 0;
        default -> 1;
      };
    }

    int viaPattern(Object role) {
      return switch (role) {
        case Role found when found.ordinal() > 0 -> 1;
        default -> 0;
      };
    }

    Object returnsARole(Roles roles) {
      return roles.of("ada");
    }

    boolean roomWake(RunStore.RunRow run) {
      return run.readOnlyLane();
    }

    boolean audience(Actor actor) {
      return Access.audience(actor).unrestricted();
    }

    Actor widened(Actor actor) {
      return actor.withScope(ProjectScope.all());
    }

    boolean standing(Actor actor) {
      return Whoami.of(actor).capabilities().contains("admin");
    }

    boolean peer(Actor actor) {
      return actor.peer() != null;
    }

    boolean owner(Actor actor) {
      return actor.owner() != null;
    }

    Actor asMain(String handle) {
      return Actor.main(handle);
    }

    void asTheBox(Runnable work) {
      Actor.run(Actor.system(), work);
    }

    List<String> audienceInALambda(Actor actor, List<String> projects) {
      var audience = Access.audience(actor);
      return projects.stream().filter(project -> audience.admits(project)).toList();
    }

    Actor fromTheRoles(String handle) {
      return new Actor(handle, Role.valueOf("ADMIN"), Actor.Lane.CLI);
    }

    Role[] everyRole() {
      return Role.values();
    }

    boolean maker(ChangeLog journal, String id) {
      return journal.bornBy(id).isPresent();
    }

    boolean aRunsOwn(Actor actor) {
      return PrincipalHandle.matches(actor.handle());
    }
  }

  /** Each read {@code allowed} does not name, then each entry of it that names no read. */
  private static List<String> findings(Set<String> reads, Set<String> allowed) {
    return Stream.concat(
            difference(reads, allowed).stream()
                .map(
                    read ->
                        "a member reads a role, a lane or a scope the allowlist does not"
                            + " name: "
                            + read),
            difference(allowed, reads).stream()
                .map(
                    entry ->
                        "an allowlisted member no longer reads one, so its entry goes: " + entry))
        .toList();
  }

  private static Set<String> difference(Set<String> of, Set<String> without) {
    var left = new TreeSet<>(of);
    left.removeAll(without);
    return left;
  }

  /** Every member of the class {@code bytes} holds that reads a role, a lane or a scope. */
  private static Set<String> reads(byte[] bytes) {
    var model = ClassFile.of().parse(bytes);
    var name = model.thisClass().asInternalName();
    var found = new TreeSet<String>();
    if (exempt(name)) {
      return found;
    }
    var byMember = new TreeMap<String, List<List<String>>>();
    for (var method : model.methods()) {
      method
          .code()
          .ifPresent(
              code ->
                  byMember
                      .computeIfAbsent(member(method), member -> new ArrayList<>())
                      .addAll(
                          code.elementStream().map(OneDecisionSweepTest::referencesOf).toList()));
    }
    byMember.forEach(
        (member, references) -> {
          if (references.stream().anyMatch(OneDecisionSweepTest::reads)
              || asksAudience(references.stream().flatMap(List::stream).toList())) {
            found.add(name + "#" + member);
          }
        });
    return found;
  }

  /**
   * Whether class {@code name} defines what is read, so its members are not swept: in a package or
   * a nesting {@link #EXEMPT} names (an entry ending in {@code /} or {@code $}), or one of its
   * classes and their nested classes.
   */
  static boolean exempt(String name) {
    return EXEMPT.stream()
        .anyMatch(
            entry ->
                entry.endsWith("/") || entry.endsWith("$")
                    ? name.startsWith(entry)
                    : name.equals(entry) || name.startsWith(entry + "$"));
  }

  /**
   * Whether {@code references} take an actor's audience ({@code Access#audience}) and ask it of a
   * project or of every one in Java, a branch on the scope rather than a filter handed to a query.
   */
  private static boolean asksAudience(List<String> references) {
    return references.contains(AUDIENCE) && references.stream().anyMatch(SCOPE_ASKED::contains);
  }

  /**
   * The members and types an instruction names, each as {@code owner#name} or {@code owner#}: a
   * call (and a call returning a {@link Role}, as {@code owner#}{@value #RETURNS_ROLE}), a field
   * ({@link Role}'s constants among them, and the synthetic table a {@code switch} over a role
   * reads), a type check or class constant, and what an {@code invokedynamic} hands its bootstrap:
   * a method reference, or the types a pattern {@code switch} tests.
   */
  private static List<String> referencesOf(Object element) {
    return switch (element) {
      case InvokeInstruction invoke ->
          invoke.typeSymbol().returnType().descriptorString().contains("L" + ROLE + ";")
              ? List.of(invoke.owner().asInternalName() + "#" + RETURNS_ROLE)
              : List.of(invoke.owner().asInternalName() + "#" + invoke.name().stringValue());
      case FieldInstruction field ->
          List.of(
              field.owner().asInternalName()
                  + "#"
                  + (field.name().stringValue().equals("$SwitchMap$" + ROLE.replace('/', '$'))
                      ? ROLE_SWITCH
                      : field.name().stringValue()));
      case TypeCheckInstruction check -> List.of(check.type().asInternalName() + "#");
      case NewReferenceArrayInstruction array ->
          List.of(array.componentType().asInternalName() + "#");
      case ConstantInstruction constant when constant.constantValue() instanceof ClassDesc type ->
          List.of(internal(type) + "#");
      case InvokeDynamicInstruction dynamic ->
          dynamic.bootstrapArgs().stream()
              .<String>mapMulti(
                  (argument, out) -> {
                    if (argument instanceof DirectMethodHandleDesc handle) {
                      out.accept(internal(handle.owner()) + "#" + handle.methodName());
                    } else if (argument instanceof ClassDesc type) {
                      out.accept(internal(type) + "#");
                    } else if (argument instanceof Enum.EnumDesc<?> constant) {
                      out.accept(internal(constant.constantType()) + "#" + constant.constantName());
                    }
                  })
              .toList();
      default -> List.of();
    };
  }

  private static boolean reads(List<String> references) {
    return references.stream()
        .anyMatch(
            reference -> {
              var hash = reference.indexOf('#');
              var owner = reference.substring(0, hash);
              var member = reference.substring(hash + 1);
              return owner.startsWith(CALLER) && member.isEmpty()
                  || owner.equals(ROLE)
                      && (member.isEmpty() || Character.isUpperCase(member.charAt(0)))
                  || member.equals(ROLE_SWITCH)
                  || member.equals(RETURNS_ROLE)
                  || READS.getOrDefault(owner, Set.of()).contains(member);
            });
  }

  /** {@code method}'s name, a lambda's ({@code lambda$admit$3}) as the member it is written in. */
  private static String member(MethodModel method) {
    var name = method.methodName().stringValue();
    return name.startsWith("lambda$")
        ? name.substring("lambda$".length(), name.lastIndexOf('$'))
        : name;
  }

  private static String internal(ClassDesc type) {
    var descriptor = type.descriptorString();
    return descriptor.startsWith("L")
        ? descriptor.substring(1, descriptor.length() - 1)
        : descriptor;
  }
}
