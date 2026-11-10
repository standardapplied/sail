/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Capability;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A1, one decision, swept: outside {@code ai.singlr.sail.access}, {@code RoleRule} (which resolves
 * a role) and {@code FdeStore} (the roster's rules), no main class reads a role, a lane, a scope or
 * a capability but the members {@link #ALLOWED} names, each with the spec that deletes it or the
 * fact, not a permission, that needs it to know which fact applies. Read from the compiled classes
 * of every module, a member at a time, a lambda counted as the member it is written in, it sees: a
 * call of {@link Actor#isAdmin}, {@link Actor#isSail}, {@link Actor#role}, {@link Actor#lane},
 * {@link Actor#agentLane}, {@link Actor#roomLane}, {@link Actor#scope} or {@link Actor#actsFor}; of
 * {@code RoleRule}'s resolvers, {@link Role#fromAttribute}, {@link Role#allows}, {@link
 * Role#cappedBy} or a role's order; a {@link Role} constant (so an {@code Actor} built with a role
 * literal), a {@code switch} over a role, a type check of one, and any call that returns one;
 * {@code RunRow#readOnlyLane}; a type check of the socket's run or box caller; and anything of
 * {@link Capability}, as a call, a field, a type check, a class constant, or what an {@code
 * invokedynamic} hands its bootstrap (a method reference, the types and constants a pattern {@code
 * switch} tests). It cannot see a role, a lane or a scope read as data: in SQL, from a map, or
 * through a type that hides it, so a filter inside a query takes its scope from {@code
 * Access.audience}. {@code Actor}, {@code Role}, {@code Capability} and the socket's callers define
 * what is read, so their own members are not swept. An entry that no longer matches a read fails
 * too, so the list shrinks as the later specs land.
 */
class OneDecisionSweepTest {

  private static final String IDENTITY = "ai/singlr/sail/identity/";
  private static final String ACTOR = IDENTITY + "Actor";
  private static final String ROLE = IDENTITY + "Role";
  private static final String CAPABILITY = IDENTITY + "Capability";
  private static final String ROLE_RULE = IDENTITY + "RoleRule";
  private static final String RUN_ROW = "ai/singlr/sail/store/RunStore$RunRow";
  private static final String CALLER = "ai/singlr/sail/api/LocalApiRouter$Caller$";
  private static final String SCOPE = IDENTITY + "ProjectScope";
  private static final String AUDIENCE = "ai/singlr/sail/access/Access#audience";
  private static final Set<String> SCOPE_ASKED = Set.of(SCOPE + "#unrestricted", SCOPE + "#admits");

  /** The members whose call reads a role, a lane, a scope or whom an actor acts for. */
  private static final Map<String, Set<String>> READS =
      Map.of(
          ACTOR,
          Set.of("isAdmin", "isSail", "role", "lane", "agentLane", "roomLane", "scope", "actsFor"),
          ROLE,
          Set.of("allows", "fromAttribute", "cappedBy", "ordinal", "compareTo"),
          ROLE_RULE,
          Set.of("roleOf", "roleOfUnbound", "actorOf"),
          RUN_ROW,
          Set.of("readOnlyLane"));

  private static final List<String> EXEMPT =
      List.of(
          "ai/singlr/sail/access/",
          ROLE_RULE,
          "ai/singlr/sail/store/FdeStore",
          ACTOR,
          ROLE,
          CAPABILITY,
          CALLER);

  private static final String RETURNS_ROLE = "<returns a role>";
  private static final String ROLE_SWITCH = "<switch over a role>";

  private static final String API = "ai/singlr/sail/api/";
  private static final String RULES = "ai/singlr/sail/authority/";
  private static final String STORES = "ai/singlr/sail/store/";
  private static final String SYNC_SERVER = "ai/singlr/sail/sync/SyncRpcServer";
  private static final String EVERY_DOOR = "sail-every-door-asks-access";
  private static final String OWN_WORK = "sail-agents-act-on-their-own-work";

  /** Every member that still reads a role, a lane or a capability, and why it may. */
  private static final Map<String, String> ALLOWED = allowed();

  private static Map<String, String> allowed() {
    var allowed = new TreeMap<String, String>();
    for (var route : List.of("handleStream", "route", "routeSync", "whoami", "actorOf")) {
      allowed.put(API + "ApiRouter#" + route, EVERY_DOOR);
    }
    for (var resolves :
        List.of(
            "Authorizer#require",
            "LocalApiRouter#whoami",
            "LocalApiRouter#requirePerson",
            "LocalApiRouter#runSession",
            "SailOperations#runActor",
            "SailOperations#boxActorForCredential",
            "SessionAwareAuth#authenticateSession",
            "TokenAuth#require",
            "TokenAuth#roleOf",
            "WebauthnAuthHandler#requireAdmin")) {
      allowed.put(API + resolves, EVERY_DOOR);
    }
    for (var resolves :
        List.of("engine/CliOperator#of", "engine/HostAccess#actorOf", "ssh/SshGateway#authorize")) {
      allowed.put("ai/singlr/sail/" + resolves, EVERY_DOOR);
    }
    allowed.put(RULES + "EventAuthority#decide", OWN_WORK);
    allowed.put(API + "LocalApiRouter#messages", OWN_WORK);
    allowed.put(API + "LocalApiRouter#project", OWN_WORK);
    allowed.put(
        API + "Event$Publisher#of", "fact: the role and lane an event's publisher acted on");
    allowed.put(API + "LocalApiRouter#events", "fact: only a run credential has a run to narrate");
    allowed.put(
        API + "LocalApiRouter#runMessages", "fact: only a run credential has an inbox to read");
    allowed.put(
        API + "LocalApiRouter#markDeliveredOnSelfRead",
        "fact: a run's read of its own room is a delivery");
    allowed.put(API + "RoomCommitGuard#guardRoomRun", "fact: which runs are a room's wakes");
    allowed.put(RULES + "Attribution#decide", "fact: a push names only its pusher (attribution)");
    allowed.put(RULES + "MessageAuthority#mayPostAs", "fact: as whom a pusher posts (attribution)");
    allowed.put(
        RULES + "EventAuthority#refused", "fact: whose work a refused event's words describe");
    allowed.put(STORES + "Snapshots#creator", "fact: whom a write names its creator (attribution)");
    allowed.put(
        STORES + "Snapshots#adoptedCreator", "fact: whom a write names its creator (attribution)");
    allowed.put(STORES + "RoomStore$RoomSchema#apply", "fact: a room main decided is adopted");
    allowed.put(STORES + "RoomStore$RoomSchema#creatorOf", "fact: a room main decided is adopted");
    allowed.put(
        STORES + "RunStore$RunRow#principalActor",
        "fact: which principal a run's credential stands for, by its lane");
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
    return allowed;
  }

  @Test
  void noMemberOutsideTheAccessPackageReadsARoleALaneOrACapabilityButTheNamedOnes()
      throws Exception {
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
            "a member reads a role, a lane or a capability the allowlist does not name: a#leak",
            "an allowlisted member no longer reads one, so its entry goes: a#gone"),
        findings(Set.of("a#leak", "a#kept"), Set.of("a#kept", "a#gone")));
  }

  @Test
  void theSweepFlagsEveryReadOfARoleALaneOrACapability() throws IOException {
    try (var bytes = Leaky.class.getResourceAsStream("OneDecisionSweepTest$Leaky.class")) {
      var leaky = "ai/singlr/sail/api/OneDecisionSweepTest$Leaky#";
      assertEquals(
          Set.of(
              leaky + "admin",
              leaky + "role",
              leaky + "lane",
              leaky + "agent",
              leaky + "room",
              leaky + "allows",
              leaky + "capability",
              leaky + "inALambda",
              leaky + "sail",
              leaky + "scope",
              leaky + "actsFor",
              leaky + "elevated",
              leaky + "viaRule",
              leaky + "viaAttribute",
              leaky + "viaSwitch",
              leaky + "viaPattern",
              leaky + "returnsARole",
              leaky + "roomWake",
              leaky + "audience"),
          reads(bytes.readAllBytes()));
    }
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

    boolean room(Actor actor) {
      return actor.roomLane();
    }

    boolean allows(Role role) {
      return role.allows(null);
    }

    Object capability() {
      return Capability.values();
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

    Object viaRule(RoleRule roles) {
      return roles.roleOf("ada", null);
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
  }

  /** Each read {@code allowed} does not name, then each entry of it that names no read. */
  private static List<String> findings(Set<String> reads, Set<String> allowed) {
    return Stream.concat(
            difference(reads, allowed).stream()
                .map(
                    read ->
                        "a member reads a role, a lane or a capability the allowlist does not"
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

  /** Every member of the class {@code bytes} holds that reads a role, a lane or a capability. */
  private static Set<String> reads(byte[] bytes) {
    var model = ClassFile.of().parse(bytes);
    var name = model.thisClass().asInternalName();
    var found = new TreeSet<String>();
    if (EXEMPT.stream().anyMatch(name::startsWith)) {
      return found;
    }
    for (var method : model.methods()) {
      if (reads(method)) {
        found.add(name + "#" + member(method));
      }
    }
    return found;
  }

  private static boolean reads(MethodModel method) {
    if (method.methodTypeSymbol().descriptorString().contains("L" + CAPABILITY + ";")) {
      return true;
    }
    var code = method.code();
    if (code.isEmpty()) {
      return false;
    }
    var references = code.get().elementStream().map(OneDecisionSweepTest::referencesOf).toList();
    return references.stream().anyMatch(OneDecisionSweepTest::reads)
        || asksAudience(references.stream().flatMap(List::stream).toList());
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
          invoke.typeSymbol().returnType().descriptorString().equals("L" + ROLE + ";")
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
              return owner.equals(CAPABILITY)
                  || owner.startsWith(CALLER) && member.isEmpty()
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
