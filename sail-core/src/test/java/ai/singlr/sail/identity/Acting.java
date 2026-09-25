/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

/**
 * Binds an {@link Actor} around a test's own fixture writes, narrowly, so a test of an entry point
 * can seed state without binding the entry point it proves.
 */
public final class Acting {

  private Acting() {}

  /** Fixture work that returns nothing and may throw. */
  @FunctionalInterface
  public interface Work<X extends Throwable> {
    void run() throws X;
  }

  /** Runs {@code work} as the operator {@code handle}; a null handle is a machine credential. */
  public static <X extends Throwable> void as(String handle, Work<X> work) throws X {
    by(operator(handle), work);
  }

  /** As {@link #as(String, Work)} for work that returns a value. */
  public static <T, X extends Throwable> T as(String handle, ScopedValue.CallableOp<T, X> work)
      throws X {
    return Actor.call(operator(handle), work);
  }

  /** Runs {@code work} as {@code actor}. */
  public static <X extends Throwable> void by(Actor actor, Work<X> work) throws X {
    Actor.call(
        actor,
        () -> {
          work.run();
          return null;
        });
  }

  /** As {@link #by(Actor, Work)} for work that returns a value. */
  public static <T, X extends Throwable> T by(Actor actor, ScopedValue.CallableOp<T, X> work)
      throws X {
    return Actor.call(actor, work);
  }

  /** Runs {@code work} as this box's machinery. */
  public static <X extends Throwable> void system(Work<X> work) throws X {
    by(Actor.system(), work);
  }

  /** As {@link #system(Work)} for work that returns a value. */
  public static <T, X extends Throwable> T system(ScopedValue.CallableOp<T, X> work) throws X {
    return Actor.call(Actor.system(), work);
  }

  private static Actor operator(String handle) {
    return handle == null
        ? new Actor(null, Role.MEMBER, Actor.Lane.API)
        : Actor.cliOperator(handle);
  }
}
