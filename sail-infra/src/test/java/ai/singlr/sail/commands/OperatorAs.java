/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostIdentity;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.identity.Actor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** A box's operations whose host CLI operator is {@code operator}, every other answer theirs. */
final class OperatorAs {

  private OperatorAs() {}

  static HostOperations of(HostOperations operations, Actor operator) {
    var identity =
        proxy(
            HostIdentity.class,
            (self, method, arguments) ->
                method.getName().equals("operator")
                    ? operator
                    : forward(method, operations.identity(), arguments));
    return proxy(
        HostOperations.class,
        (self, method, arguments) ->
            switch (method.getName()) {
              case "identity" -> identity;
              case "asOperator" -> InvocationHandler.invokeDefault(self, method, arguments);
              default -> forward(method, operations, arguments);
            });
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  private static Object forward(Method method, Object target, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException thrown) {
      throw thrown.getCause();
    }
  }
}
