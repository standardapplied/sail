/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.ModuleClasses;
import ai.singlr.sail.pty.Account;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L3, by construction: from the local wiring ({@link LocalPtyHost}, {@link LocalPtyAccess}, the
 * {@link Account} and {@code Locales} they read, and {@code PtyHostCommand}'s local branch), every
 * member of Sail's own reached by a call, a field, a lambda or a method reference, transitively,
 * names no store, no control plane, no sync, no Incus and no network type, as a type, the owner of
 * what it calls or reads, a class constant or a method's descriptor, but for the edges {@link
 * #ALLOWED} names with why. The sweep stops at the shared terminal host and pty layer ({@link
 * #SHARED}), the server's own (L1): it reaches Incus only for a project session and the network
 * only through a TCP address, and the local wiring refuses the one and gives none of the other.
 * {@code LocalPtyHostProcessTest} proves the same of a running host.
 */
class LocalWiringSweepTest {

  private static final String COMMANDS = "ai/singlr/sail/commands/";
  private static final String PTY = "ai/singlr/sail/pty/";
  private static final String SAIL = "ai/singlr/sail/";
  private static final String ROLE_RULE = "ai/singlr/sail/identity/RoleRule";

  private static final List<String> LOCAL_ONLY = List.of(PTY + "Account", PTY + "Locales");

  /** Followed no further: the shared host and pty layer, and what may not be reached at all. */
  private static final String SHARED = PTY;

  private static final List<String> UNREACHABLE =
      List.of(
          "ai/singlr/sail/store/",
          "ai/singlr/sail/api/",
          "ai/singlr/sail/sync/",
          "ai/singlr/sail/config/ListenConfig",
          "ai/singlr/sail/engine/ContainerExec",
          "ai/singlr/sail/engine/Incus",
          "ai/singlr/sail/engine/ShellExec",
          COMMANDS + "PtyHostAccess",
          COMMANDS + "PtyHostEvents",
          COMMANDS + "SystemdStop",
          "java/net/Socket",
          "java/net/ServerSocket",
          "java/net/Inet",
          "java/net/URL",
          "java/net/http/");

  /**
   * The local door resolves its person through {@code RoleRule.principal(Credential.box())}, the
   * one way a box's own person is made, with no roster: what {@code RoleRule} names of the store is
   * the roster it is given as none, and a run's credential the local door never presents.
   */
  private static final Map<String, Set<String>> ALLOWED =
      Map.of(
          COMMANDS + "LocalPtyAccess#<init>",
          Set.of("ai/singlr/sail/store/FdeStore"),
          ROLE_RULE + "#<init>",
          Set.of("ai/singlr/sail/store/FdeStore", "ai/singlr/sail/store/SpecStore"),
          ROLE_RULE + "#principal",
          Set.of("ai/singlr/sail/store/RunStore$RunRow"),
          ROLE_RULE + "#workOf",
          Set.of(
              "ai/singlr/sail/store/RunStore$RunRow",
              "ai/singlr/sail/store/SpecStore",
              "ai/singlr/sail/store/SpecStore$SpecRow"),
          ROLE_RULE + "#roleOf",
          Set.of("ai/singlr/sail/store/FdeStore", "ai/singlr/sail/store/FdeStore$Fde"),
          ROLE_RULE + "#scopeOf",
          Set.of("ai/singlr/sail/store/FdeStore"));

  @Test
  void theLocalWiringReachesNoStoreIncusOrNetworkTypeButWhatItsPersonsRuleNames() throws Exception {
    var classes = classesOf(Account.class, PtySessionHost.class, LocalPtyHost.class);
    var roots = new ArrayList<String>(List.of(COMMANDS + "PtyHostCommand#serveThisMachine"));
    for (var name : classes.keySet()) {
      if (name.startsWith(COMMANDS + "LocalPty") || LOCAL_ONLY.contains(name)) {
        classes.get(name).methods().forEach(m -> roots.add(name + "#" + m.methodName()));
      }
    }

    var sweep = sweep(classes, roots);

    assertTrue(
        sweep
            .reached()
            .containsAll(
                Set.of(
                    COMMANDS + "PtyHostCommand#startHost",
                    COMMANDS + "PtyHostCommand#shimCommand",
                    ROLE_RULE + "#principal",
                    "ai/singlr/sail/access/Access#decide")),
        "the sweep followed the local path: " + sweep.reached());
    assertEquals(new TreeMap<>(ALLOWED), sweep.named());
  }

  @Test
  void theSweepFollowsACallToWhatItNames() throws IOException {
    var planted = new HashMap<String, ClassModel>();
    for (var type : List.of(Reads.class, Helper.class, Dials.class)) {
      var model = ClassFile.of().parse(bytesOf(type));
      planted.put(model.thisClass().asInternalName(), model);
    }
    var reads = ClassFile.of().parse(bytesOf(Reads.class)).thisClass().asInternalName();
    var helper = ClassFile.of().parse(bytesOf(Helper.class)).thisClass().asInternalName();
    var dials = ClassFile.of().parse(bytesOf(Dials.class)).thisClass().asInternalName();

    var sweep = sweep(planted, List.of(reads + "#store", dials + "#dial"));

    assertEquals(
        Map.of(
            helper + "#open", Set.of("ai/singlr/sail/store/SpecStore"),
            dials + "#dial", Set.of("java/net/Socket")),
        sweep.named());
  }

  /** What no local member may reach: a store, through a helper. */
  private static final class Reads {
    Class<?> store() {
      return Helper.open();
    }
  }

  private static final class Helper {
    static Class<?> open() {
      return SpecStore.class;
    }
  }

  /** Nor the network. */
  private static final class Dials {
    Object dial() throws IOException {
      return new Socket();
    }
  }

  private static byte[] bytesOf(Class<?> type) throws IOException {
    var name = type.getName().substring(type.getPackageName().length() + 1);
    try (var bytes = type.getResourceAsStream(name + ".class")) {
      return bytes.readAllBytes();
    }
  }

  private static Map<String, ClassModel> classesOf(Class<?>... anchors) throws Exception {
    var classes = new HashMap<String, ClassModel>();
    for (var anchor : anchors) {
      for (var bytes : ModuleClasses.of(anchor)) {
        var model = ClassFile.of().parse(bytes);
        classes.put(model.thisClass().asInternalName(), model);
      }
    }
    return classes;
  }

  /** The members reached from {@code roots}, and what unreachable types each of them names. */
  private record Sweep(Set<String> reached, Map<String, Set<String>> named) {}

  private static Sweep sweep(Map<String, ClassModel> classes, List<String> roots) {
    var reached = new TreeSet<String>();
    var named = new TreeMap<String, Set<String>>();
    var queue = new ArrayDeque<>(roots);
    while (!queue.isEmpty()) {
      var member = queue.poll();
      if (!reached.add(member)) {
        continue;
      }
      var owner = member.substring(0, member.indexOf('#'));
      var name = member.substring(member.indexOf('#') + 1);
      var model = classes.get(owner);
      if (model == null) {
        continue;
      }
      var written = owner + "#" + enclosing(name);
      for (var method : model.methods()) {
        if (method.methodName().stringValue().equals(name)) {
          visit(method, classes, queue, named.computeIfAbsent(written, any -> new TreeSet<>()));
        }
      }
      if (named.containsKey(written) && named.get(written).isEmpty()) {
        named.remove(written);
      }
    }
    return new Sweep(reached, named);
  }

  /** The member a lambda is written in, which it is counted as; any other member itself. */
  private static String enclosing(String name) {
    return name.startsWith("lambda$") ? name.split("\\$")[1] : name;
  }

  private static void visit(
      MethodModel method,
      Map<String, ClassModel> classes,
      ArrayDeque<String> queue,
      Set<String> named) {
    var code = method.code();
    if (code.isEmpty()) {
      return;
    }
    for (var element : code.get()) {
      switch (element) {
        case InvokeInstruction call -> {
          note(call.owner().asInternalName(), named);
          described(call.typeSymbol(), named);
          follow(call.owner().asInternalName(), call.name().stringValue(), classes, queue);
        }
        case FieldInstruction field -> note(field.owner().asInternalName(), named);
        case NewObjectInstruction made -> note(made.className().asInternalName(), named);
        case TypeCheckInstruction check -> note(check.type().asInternalName(), named);
        case NewReferenceArrayInstruction array ->
            note(array.componentType().asInternalName(), named);
        case ConstantInstruction constant when constant.constantValue() instanceof ClassDesc type ->
            noteDesc(type, named);
        case InvokeDynamicInstruction dynamic -> {
          described(dynamic.typeSymbol(), named);
          for (var argument : dynamic.bootstrapArgs()) {
            if (argument instanceof DirectMethodHandleDesc handle) {
              var target = internal(handle.owner());
              note(target, named);
              follow(target, handle.methodName(), classes, queue);
            }
          }
        }
        default -> {}
      }
    }
  }

  private static void follow(
      String owner, String name, Map<String, ClassModel> classes, ArrayDeque<String> queue) {
    var followed =
        owner.startsWith(SAIL)
            && (!owner.startsWith(SHARED) || LOCAL_ONLY.contains(owner))
            && UNREACHABLE.stream().noneMatch(owner::startsWith)
            && classes.containsKey(owner);
    if (followed) {
      queue.add(owner + "#" + name);
    }
  }

  private static void described(MethodTypeDesc type, Set<String> named) {
    Stream.concat(Stream.of(type.returnType()), type.parameterList().stream())
        .forEach(desc -> noteDesc(desc, named));
  }

  private static void noteDesc(ClassDesc desc, Set<String> named) {
    var element = desc;
    while (element.isArray()) {
      element = element.componentType();
    }
    if (!element.isPrimitive()) {
      note(internal(element), named);
    }
  }

  private static String internal(ClassDesc desc) {
    var descriptor = desc.descriptorString();
    return descriptor.substring(1, descriptor.length() - 1);
  }

  private static void note(String type, Set<String> named) {
    if (UNREACHABLE.stream().anyMatch(type::startsWith)) {
      named.add(type);
    }
  }
}
