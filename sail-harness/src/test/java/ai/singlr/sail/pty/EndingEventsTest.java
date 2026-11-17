/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link EndingEvents} forwards every event {@link PtyEvents} has, one added later included. */
class EndingEventsTest {

  private static final PtySession.Origin ORIGIN =
      new PtySession.Origin("lounge", "inst-1", "uday", "acme", "", List.of("bash"), "dock");

  @Test
  void everyEventReachesTheEventsItWrapsAndOnlyAnEndReachesTheEnding() throws Exception {
    var forwarded = new ArrayList<String>();
    var events =
        (PtyEvents)
            Proxy.newProxyInstance(
                PtyEvents.class.getClassLoader(),
                new Class<?>[] {PtyEvents.class},
                (proxy, method, args) -> {
                  forwarded.add(method.getName());
                  return null;
                });
    var ended = new ArrayList<PtySession.Origin>();
    var ending = new EndingEvents(events, ended::add);

    var methods =
        Arrays.stream(PtyEvents.class.getMethods())
            .filter(method -> !method.isSynthetic())
            .sorted((a, b) -> a.getName().compareTo(b.getName()))
            .toList();
    for (var method : methods) {
      method.invoke(ending, arguments(method));
    }

    assertEquals(methods.stream().map(Method::getName).toList(), forwarded);
    assertEquals(List.of(ORIGIN), ended);
  }

  private static Object[] arguments(Method method) {
    return Arrays.stream(method.getParameterTypes())
        .map(type -> type == PtySession.Origin.class ? ORIGIN : (Object) "words")
        .toArray();
  }
}
