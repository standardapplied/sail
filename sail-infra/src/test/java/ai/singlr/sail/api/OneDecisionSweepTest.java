/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.SpecCliHelper;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Capability;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.RevisionJournal;
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
import org.junit.jupiter.api.Test;

/**
 * A1, one decision, swept: outside {@code ai.singlr.sail.access}, {@code RoleRule} (which resolves
 * a role) and {@code FdeStore} (the roster's rules), no main class reads a role, a lane or a
 * capability — {@link Actor#isAdmin}, {@link Actor#canWrite}, {@link Actor#role}, {@link
 * Actor#lane}, {@link Actor#agentLane}, {@link Actor#roomLane}, {@link Role#allows}, or anything of
 * {@link Capability} — but the members {@link #ALLOWED} names, each with the spec that deletes it
 * or the fact, not a permission, that needs the lane or role to know which fact applies. Read from
 * the compiled classes of every module, a member at a time, a lambda counted as the member it is
 * written in. {@code Actor}, {@code Role} and {@code Capability} define what is read, so their own
 * members are not swept. An entry that no longer matches a read fails too, so the list shrinks as
 * the later specs land.
 */
class OneDecisionSweepTest {

  private static final String IDENTITY = "ai/singlr/sail/identity/";
  private static final String ACTOR = IDENTITY + "Actor";
  private static final String ROLE = IDENTITY + "Role";
  private static final String CAPABILITY = IDENTITY + "Capability";
  private static final Set<String> READS =
      Set.of("isAdmin", "canWrite", "role", "lane", "agentLane", "roomLane");
  private static final List<String> EXEMPT =
      List.of(
          "ai/singlr/sail/access/",
          IDENTITY + "RoleRule",
          "ai/singlr/sail/store/FdeStore",
          ACTOR,
          ROLE,
          CAPABILITY);

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
    for (var route : List.of("handleStream", "route", "routeSync", "whoami")) {
      allowed.put(API + "ApiRouter#" + route, EVERY_DOOR);
    }
    allowed.put(API + "Authorizer#require", EVERY_DOOR);
    allowed.put(API + "LocalApiRouter#whoami", EVERY_DOOR);
    allowed.put(API + "Refusals#requireWriter", EVERY_DOOR);
    allowed.put(API + "SailOperations#runActor", EVERY_DOOR);
    allowed.put(API + "WebauthnAuthHandler#requireAdmin", EVERY_DOOR);
    allowed.put("ai/singlr/sail/engine/HostAccess#roleOf", EVERY_DOOR);
    allowed.put(RULES + "RunAuthority#access", EVERY_DOOR);
    for (var content : List.of("result", "serveContent", "unshared")) {
      allowed.put(SYNC_SERVER + "#" + content, EVERY_DOOR);
    }
    allowed.put(RULES + "EventAuthority#decide", OWN_WORK);
    allowed.put(
        API + "Event$Publisher#of", "fact: the role and lane an event's publisher acted on");
    allowed.put(RULES + "Attribution#decide", "fact: a push names only its pusher (attribution)");
    allowed.put(RULES + "MessageAuthority#mayPostAs", "fact: as whom a pusher posts (attribution)");
    allowed.put(STORES + "Snapshots#creator", "fact: whom a write names its creator (attribution)");
    allowed.put(
        STORES + "Snapshots#adoptedCreator", "fact: whom a write names its creator (attribution)");
    allowed.put(STORES + "RoomStore$RoomSchema#apply", "fact: a room main decided is adopted");
    allowed.put(STORES + "RoomStore$RoomSchema#creatorOf", "fact: a room main decided is adopted");
    allowed.put(RULES + "RunAuthority#decide", "fact: which box executed a pushed run");
    allowed.put(RULES + "WriteAuthority#local", "fact: which box decides a write, main a push");
    allowed.put(SYNC_SERVER + "#<init>", "fact: a sync server serves a sync session alone");
    return allowed;
  }

  @Test
  void noMemberOutsideTheAccessPackageReadsARoleALaneOrACapabilityButTheNamedOnes()
      throws Exception {
    var scanned = 0;
    var reads = new TreeSet<String>();
    for (var module : List.of(RevisionJournal.class, SpecCliHelper.class, SailOperations.class)) {
      for (var bytes : OneDecisionPointTest.classesOf(module)) {
        reads.addAll(reads(bytes));
        scanned++;
      }
    }

    assertTrue(scanned > 500, "every module's classes were read: " + scanned);
    assertEquals(
        Set.of(),
        difference(reads, ALLOWED.keySet()),
        "a member reads a role, a lane or a capability the allowlist does not name");
    assertEquals(
        Set.of(),
        difference(ALLOWED.keySet(), reads),
        "an allowlisted member no longer reads one: delete its entry");
  }

  @Test
  void theSweepFlagsEveryReadOfARoleALaneOrACapability() throws IOException {
    try (var bytes = Leaky.class.getResourceAsStream("OneDecisionSweepTest$Leaky.class")) {
      var leaky = "ai/singlr/sail/api/OneDecisionSweepTest$Leaky#";
      assertEquals(
          Set.of(
              leaky + "admin",
              leaky + "writer",
              leaky + "role",
              leaky + "lane",
              leaky + "agent",
              leaky + "room",
              leaky + "allows",
              leaky + "capability",
              leaky + "inALambda"),
          reads(bytes.readAllBytes()));
    }
  }

  /** What no class outside the access package does. */
  private static final class Leaky {
    boolean admin(Actor actor) {
      return actor.isAdmin();
    }

    boolean writer(Actor actor) {
      return actor.canWrite();
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
    return code.isPresent()
        && code.get().elementStream().anyMatch(element -> reads(referencesOf(element)));
  }

  /** The members and types an instruction names, each as {@code owner#name} or {@code owner#}. */
  private static List<String> referencesOf(Object element) {
    return switch (element) {
      case InvokeInstruction invoke ->
          List.of(invoke.owner().asInternalName() + "#" + invoke.name().stringValue());
      case FieldInstruction field ->
          List.of(field.owner().asInternalName() + "#" + field.name().stringValue());
      case TypeCheckInstruction check -> List.of(check.type().asInternalName() + "#");
      case NewReferenceArrayInstruction array ->
          List.of(array.componentType().asInternalName() + "#");
      case ConstantInstruction constant when constant.constantValue() instanceof ClassDesc type ->
          List.of(internal(type) + "#");
      case InvokeDynamicInstruction dynamic ->
          dynamic.bootstrapArgs().stream()
              .filter(DirectMethodHandleDesc.class::isInstance)
              .map(DirectMethodHandleDesc.class::cast)
              .map(handle -> internal(handle.owner()) + "#" + handle.methodName())
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
                  || owner.equals(ACTOR) && READS.contains(member)
                  || owner.equals(ROLE) && member.equals("allows");
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
