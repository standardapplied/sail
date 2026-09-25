/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Binds an {@link Actor} around a store-level test class or method — its constructor, lifecycle
 * methods and the test itself — so a test of a store's own behaviour need not say who is acting.
 * Tests of an entry point (a command, a router, an operation, a reactor, a sync session) leave it
 * off and run unbound, so each proves its entry point binds. A method's annotation wins over its
 * class's, and a nested class's over its enclosing one's.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Inherited
@ExtendWith(ActingAsExtension.class)
public @interface ActingAs {

  /** The lane acted on; {@link Actor.Lane#SYSTEM} by default. */
  Actor.Lane value() default Actor.Lane.SYSTEM;

  /** The acting handle; the lane's own for {@code SYSTEM} and {@code MAIN} when blank. */
  String handle() default "";

  /** The acting role. */
  Role role() default Role.ADMIN;
}
