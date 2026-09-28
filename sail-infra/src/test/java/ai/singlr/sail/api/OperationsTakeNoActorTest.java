/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Who is acting has one channel: the {@link Actor} bound at the entry point, read with {@link
 * Actor#current()}. No operation interface takes an actor argument, so no caller can hand an
 * operation someone else.
 */
class OperationsTakeNoActorTest {

  private static final String ACTOR = "Lai/singlr/sail/identity/Actor;";

  private static final List<Class<?>> OPERATIONS =
      List.of(
          Operations.class,
          LocalLaneOperations.class,
          HostOperations.class,
          HostDispatching.class,
          HostCatalog.class,
          HostIdentity.class,
          HostPty.class,
          HostSchema.class);

  @Test
  void noOperationInterfaceTakesAnActor() throws IOException {
    var violations = new ArrayList<String>();
    for (var type : OPERATIONS) {
      violations.addAll(violations(type));
    }
    assertEquals(List.of(), violations);
  }

  @Test
  void theGuardFlagsAnOperationThatTakesAnActor() throws IOException {
    assertEquals(
        List.of("ai/singlr/sail/api/OperationsTakeNoActorTest$Leaky.act"), violations(Leaky.class));
  }

  private interface Leaky {
    void act(String id, Actor actor);

    Actor operator();
  }

  private static List<String> violations(Class<?> type) throws IOException {
    var name = type.getName();
    try (var bytes =
        type.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")) {
      var model = ClassFile.of().parse(bytes.readAllBytes());
      return model.methods().stream()
          .filter(
              method ->
                  method.methodTypeSymbol().parameterList().stream()
                      .anyMatch(parameter -> ACTOR.equals(parameter.descriptorString())))
          .map(method -> model.thisClass().asInternalName() + "." + method.methodName())
          .toList();
    }
  }
}
