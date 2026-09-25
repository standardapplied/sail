/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.platform.commons.support.AnnotationSupport;

/** Runs every invocation of an {@link ActingAs} test as the actor the annotation names. */
public final class ActingAsExtension implements InvocationInterceptor {

  @Override
  public <T> T interceptTestClassConstructor(
      Invocation<T> invocation,
      ReflectiveInvocationContext<Constructor<T>> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    return proceed(invocation, invocationContext, extensionContext);
  }

  @Override
  public void interceptBeforeAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceed(invocation, invocationContext, extensionContext);
  }

  @Override
  public void interceptBeforeEachMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceed(invocation, invocationContext, extensionContext);
  }

  @Override
  public void interceptTestMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceed(invocation, invocationContext, extensionContext);
  }

  @Override
  public void interceptTestTemplateMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceed(invocation, invocationContext, extensionContext);
  }

  @Override
  public void interceptAfterEachMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceed(invocation, invocationContext, extensionContext);
  }

  @Override
  public void interceptAfterAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    proceed(invocation, invocationContext, extensionContext);
  }

  private static <T> T proceed(
      Invocation<T> invocation,
      ReflectiveInvocationContext<?> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    var actingAs = actingAs(invocationContext.getExecutable(), extensionContext);
    return actingAs.isEmpty()
        ? invocation.proceed()
        : Actor.call(actorOf(actingAs.get()), invocation::proceed);
  }

  private static Optional<ActingAs> actingAs(
      AnnotatedElement executable, ExtensionContext context) {
    var onMethod = AnnotationSupport.findAnnotation(executable, ActingAs.class);
    if (onMethod.isPresent()) {
      return onMethod;
    }
    for (var current = Optional.of(context);
        current.isPresent();
        current = current.get().getParent()) {
      var onClass =
          current
              .get()
              .getTestClass()
              .flatMap(type -> AnnotationSupport.findAnnotation(type, ActingAs.class));
      if (onClass.isPresent()) {
        return onClass;
      }
    }
    return Optional.empty();
  }

  static Actor actorOf(ActingAs actingAs) {
    return switch (actingAs.value()) {
      case SYSTEM -> Actor.system();
      case MAIN -> Actor.main();
      default ->
          new Actor(
              actingAs.handle().isBlank() ? null : actingAs.handle(),
              actingAs.role(),
              actingAs.value());
    };
  }
}
