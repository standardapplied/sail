/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.util.Objects;

/**
 * The authenticated principal behind a pty-host connection. A person is resolved from a verifiable
 * credential by the host's {@link Resolver} — never from anything the client claims: a session
 * token minted by the gateway, or the box's ambient owner when the connection carries none — and
 * carries the {@link Actor} it acts as. The one principal that is no person is {@link #DISPATCH}:
 * the host's own dispatch authority, admitted by the credential the host mints at start, allowed
 * two verbs — yielding a session a reservation displaced, and listing which sessions are live,
 * whoever opened them — and nothing else a person may do.
 */
public sealed interface PtyIdentity {

  PtyIdentity DISPATCH = new Dispatch();

  /** Whom the host names this principal as: a session's owner, and its log's principal. */
  String fde();

  /** A person, acting as {@code actor}. */
  record Person(Actor actor) implements PtyIdentity {

    public Person {
      Objects.requireNonNull(actor, "actor");
    }

    @Override
    public String fde() {
      return actor.handle();
    }
  }

  /** The host's own dispatch authority. */
  record Dispatch() implements PtyIdentity {

    @Override
    public String fde() {
      return "dispatch";
    }
  }

  /** Resolves the person a connection's token stands for, or throws why it is refused. */
  interface Resolver {
    Person resolve(String token) throws IOException;
  }
}
