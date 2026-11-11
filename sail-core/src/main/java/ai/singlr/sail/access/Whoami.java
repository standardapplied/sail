/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import ai.singlr.sail.access.Policy.Cell;
import ai.singlr.sail.access.Policy.Column;
import ai.singlr.sail.identity.Actor;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * What a door shows a principal of itself ({@code whoami}): its {@code role} and {@code lane} in
 * their lowercase form, and the {@code capabilities} its role's column of {@link Policy} grants, in
 * this order: {@code read} when it allows Read, {@code write} when it may write at all ({@link
 * Access#mayWrite}), {@code admin} when it allows Manage people. Clients read the capabilities
 * until mast-people-and-access reads the actions a role and lane could ever take.
 */
public record Whoami(String role, String lane, List<String> capabilities) {

  public Whoami {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(lane, "lane");
    capabilities = List.copyOf(capabilities);
  }

  /** {@code actor} as it is shown to itself. */
  public static Whoami of(Actor actor) {
    var column = Column.role(actor.role());
    var capabilities =
        Stream.of(
                Map.entry("read", grants(column, Action.READ)),
                Map.entry("write", Access.mayWrite(actor)),
                Map.entry("admin", grants(column, Action.MANAGE_PEOPLE)))
            .filter(Map.Entry::getValue)
            .map(Map.Entry::getKey)
            .toList();
    return new Whoami(
        actor.role().attribute(), actor.lane().name().toLowerCase(Locale.ROOT), capabilities);
  }

  private static boolean grants(Column column, Action action) {
    return Policy.cell(action, column) != Cell.NO;
  }
}
