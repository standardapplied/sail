/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resource;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Every decision reaches a client as its door's answer: a refusal as the code it has always seen,
 * and a resource it may not read as the door's not-found.
 */
class RefusalsTest {

  private static final Actor OF_ALPHA =
      new Actor("sam", Role.ADMIN, Actor.Lane.API).withScope(ProjectScope.of(List.of("alpha")));
  private static final Actor VIEWER_OF_ALPHA =
      new Actor("vic", Role.VIEWER, Actor.Lane.API).withScope(ProjectScope.of(List.of("alpha")));

  private static final Resource BRAVO = Resources.project("bravo", null);
  private static final Resource ALPHA = Resources.project("alpha", null);

  @Test
  void eachKindMapsToTheCodeClientsSee() {
    assertEquals(
        Map.ofEntries(
            Map.entry(Refusal.Kind.READ_ONLY, ErrorCode.READ_ONLY_CREDENTIAL),
            Map.entry(Refusal.Kind.NOT_OWNER, ErrorCode.FORBIDDEN_NOT_ASSIGNEE),
            Map.entry(Refusal.Kind.NO_CONVERSATION, ErrorCode.ROOM_NOT_FOUND),
            Map.entry(Refusal.Kind.ADMIN_ONLY, ErrorCode.FORBIDDEN_ADMIN_ONLY),
            Map.entry(Refusal.Kind.LANE_FORBIDDEN, ErrorCode.AGENT_LANE_FORBIDDEN),
            Map.entry(Refusal.Kind.NOT_AUTHOR, ErrorCode.FORBIDDEN_NOT_AUTHOR),
            Map.entry(Refusal.Kind.FIXED, ErrorCode.INVALID_REQUEST),
            Map.entry(Refusal.Kind.NOT_PRUNABLE, ErrorCode.SPEC_NOT_PRUNABLE),
            Map.entry(Refusal.Kind.NOT_PUBLISHABLE, ErrorCode.FORBIDDEN),
            Map.entry(Refusal.Kind.OUT_OF_SCOPE, ErrorCode.NOT_FOUND),
            Map.entry(Refusal.Kind.UNHELD_PROJECT, ErrorCode.NOT_FOUND)),
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
    assertEquals("Fix it.", thrown.failure().remedy().plain());
    assertEquals(ErrorCode.FORBIDDEN_NOT_AUTHOR, failure.errorCode());
    assertEquals("Fix it.", failure.remedy().plain());
    Refusals.enforce(Optional.empty());
  }

  @Test
  void anActionOnWhatTheCallerMayNotReadIsTheDoorsOwnNotFound() {
    var absent = new ApiException(ErrorCode.PROJECT_NOT_IN_CATALOG, "Project 'bravo' is gone.");

    var thrown =
        assertThrows(
            ApiException.class,
            () -> Refusals.require(VIEWER_OF_ALPHA, Action.SERVE, BRAVO, () -> absent));

    assertEquals(absent, thrown);
  }

  @Test
  void anActionTheCallerMayNotTakeIsItsRefusalAndOneItMayTakePasses() {
    var thrown =
        assertThrows(
            ApiException.class,
            () -> Refusals.require(VIEWER_OF_ALPHA, Action.SERVE, ALPHA, () -> null));

    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, thrown.failure().errorCode());
    assertEquals("Your credential is read-only and cannot serve projects.", thrown.getMessage());
    Refusals.require(OF_ALPHA, Action.SERVE, ALPHA, () -> null);
  }
}
