/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Who is acting has one channel: the {@link Actor} bound at the entry point, read with {@link
 * Actor#current()}. No operation surface takes an actor argument — not an {@code Actor}, an array
 * of them, nor a generic that carries one — so no caller can hand an operation someone else. The
 * surfaces are found from {@link HostOperations}: its super-interfaces and the facets its accessors
 * return, transitively, and the implementation behind them, so a new facet cannot escape the guard.
 */
class OperationsTakeNoActorTest {

  private static final String ACTOR = "Lai/singlr/sail/identity/Actor;";

  @Test
  void noOperationSurfaceTakesAnActor() throws IOException {
    var surfaces = surfaces();
    assertTrue(surfaces.contains(HostDispatching.class), surfaces.toString());
    assertTrue(surfaces.contains(LocalLaneOperations.class), surfaces.toString());
    var violations = new ArrayList<String>();
    for (var type : surfaces) {
      violations.addAll(violations(type));
    }
    violations.addAll(violations(SailOperations.class));
    assertEquals(List.of(), violations);
  }

  @Test
  void theGuardFlagsEveryWayAnOperationCanTakeAnActor() throws IOException {
    assertEquals(
        List.of(
            "ai/singlr/sail/api/OperationsTakeNoActorTest$Leaky.act",
            "ai/singlr/sail/api/OperationsTakeNoActorTest$Leaky.actAll",
            "ai/singlr/sail/api/OperationsTakeNoActorTest$Leaky.actLater"),
        violations(Leaky.class));
  }

  private interface Leaky {
    void act(String id, Actor actor);

    void actAll(Actor... actors);

    void actLater(Supplier<Actor> actor);

    Actor operator();
  }

  /** Every operation interface reachable from {@link HostOperations}. */
  private static Set<Class<?>> surfaces() {
    var found = new LinkedHashSet<Class<?>>();
    var pending = new ArrayDeque<Class<?>>(List.of(HostOperations.class));
    while (!pending.isEmpty()) {
      var type = pending.pop();
      if (!type.isInterface()
          || !type.getPackageName().equals(HostOperations.class.getPackageName())
          || !found.add(type)) {
        continue;
      }
      pending.addAll(List.of(type.getInterfaces()));
      for (var method : type.getMethods()) {
        pending.add(method.getReturnType());
      }
    }
    return found;
  }

  private static List<String> violations(Class<?> type) throws IOException {
    var name = type.getName();
    try (var bytes =
        type.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")) {
      var model = ClassFile.of().parse(bytes.readAllBytes());
      return model.methods().stream()
          .filter(method -> (method.flags().flagsMask() & ClassFile.ACC_PRIVATE) == 0)
          .filter(method -> parameters(method).contains(ACTOR))
          .map(method -> model.thisClass().asInternalName() + "." + method.methodName())
          .toList();
    }
  }

  /** A method's parameters as its generic signature spells them, else its descriptor. */
  private static String parameters(MethodModel method) {
    var signature =
        method
            .findAttribute(Attributes.signature())
            .map(attribute -> attribute.signature().stringValue())
            .orElse(method.methodType().stringValue());
    return signature.substring(signature.indexOf('('), signature.indexOf(')') + 1);
  }
}
