/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.authority.Refusal;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Every kind of refusal a rule gives reaches a client as the code it has always seen. */
class RefusalsTest {

  @Test
  void eachKindMapsToTheCodeClientsSee() {
    assertEquals(
        Map.of(
            Refusal.Kind.READ_ONLY, ErrorCode.READ_ONLY_CREDENTIAL,
            Refusal.Kind.NOT_OWNER, ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
            Refusal.Kind.ADMIN_ONLY, ErrorCode.FORBIDDEN_ADMIN_ONLY,
            Refusal.Kind.NOT_AUTHOR, ErrorCode.FORBIDDEN_NOT_AUTHOR,
            Refusal.Kind.FIXED, ErrorCode.INVALID_REQUEST,
            Refusal.Kind.NOT_PRUNABLE, ErrorCode.SPEC_NOT_PRUNABLE,
            Refusal.Kind.NOT_PUBLISHABLE, ErrorCode.FORBIDDEN),
        Arrays.stream(Refusal.Kind.values())
            .collect(Collectors.toMap(kind -> kind, Refusals::code)));
    assertEquals(403, ErrorCode.FORBIDDEN_NOT_AUTHOR.httpCode());
    assertEquals("forbidden_not_author", ErrorCode.FORBIDDEN_NOT_AUTHOR.code());
  }

  @Test
  void aRefusalCarriesItsMessageAndFixVerbatim() {
    var refusal = new Refusal(Refusal.Kind.NOT_AUTHOR, "'ada' may not write as 'bob'.", "Fix it.");

    var thrown = assertThrows(ApiException.class, () -> Refusals.enforce(Optional.of(refusal)));
    var failure = assertInstanceOf(Result.Failure.class, Refusals.failure(refusal));

    assertEquals(ErrorCode.FORBIDDEN_NOT_AUTHOR, thrown.failure().errorCode());
    assertEquals("'ada' may not write as 'bob'.", thrown.getMessage());
    assertEquals("Fix it.", thrown.failure().action());
    assertEquals(ErrorCode.FORBIDDEN_NOT_AUTHOR, failure.errorCode());
    assertEquals("Fix it.", failure.action());
    Refusals.enforce(Optional.empty());
  }
}
