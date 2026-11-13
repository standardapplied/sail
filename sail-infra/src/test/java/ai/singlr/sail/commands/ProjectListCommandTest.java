/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.engine.ContainerManager.ContainerInfo;
import ai.singlr.sail.engine.ContainerManager.ResourceLimits;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectListCommandTest {

  private static ContainerInfo running(String name) {
    return new ContainerInfo(
        name, new ContainerState.Running("10.0.0.5"), new ResourceLimits("4", "8GB"));
  }

  @Test
  void cataloguedButUnprovisionedProjectsSurfaceAsNotCreated() {
    var merged = ProjectListCommand.merge(List.of(running("acme")), List.of("acme", "billing"));

    assertEquals(List.of("acme", "billing"), merged.stream().map(ContainerInfo::name).toList());
    var billing = merged.stream().filter(c -> c.name().equals("billing")).findFirst().orElseThrow();
    assertInstanceOf(ContainerState.NotCreated.class, billing.state());
  }

  @Test
  void aProvisionedContainerWinsOverItsCatalogPlaceholder() {
    var merged = ProjectListCommand.merge(List.of(running("acme")), List.of("acme"));

    assertEquals(1, merged.size());
    assertInstanceOf(ContainerState.Running.class, merged.getFirst().state());
  }

  @Test
  void aLocalContainerWithNoCatalogEntryStillShows() {
    var merged = ProjectListCommand.merge(List.of(running("legacy")), List.of());

    assertEquals(List.of("legacy"), merged.stream().map(ContainerInfo::name).toList());
  }

  @Test
  void resultIsSortedByName() {
    var merged =
        ProjectListCommand.merge(List.of(running("zeta")), List.of("alpha", "zeta", "mid"));

    assertEquals(
        List.of("alpha", "mid", "zeta"), merged.stream().map(ContainerInfo::name).toList());
  }

  @Test
  void aScopedOperatorListsOnlyItsProjectsCataloguedOrNot() {
    var scoped =
        new Actor("mady", Role.MEMBER, Actor.Lane.CLI).withScope(ProjectScope.of(List.of("acme")));

    var merged =
        Actor.call(
            scoped,
            () ->
                ProjectListCommand.merge(
                    List.of(running("acme"), running("legacy")), List.of("acme", "beta")));

    assertEquals(List.of("acme"), merged.stream().map(ContainerInfo::name).toList());
  }

  @Test
  void aBoxWithNoCatalogYetListsItsContainersAlone() {
    try (var db = Sqlite.openMemory()) {
      assertEquals(
          List.of(),
          ProjectListCommand.catalogNames(
              () ->
                  OperationsFactory.create(
                      db,
                      new ScriptedShellExecutor(),
                      null,
                      SyncScheduler.disabled(),
                      SessionYield.NONE)));
    }
  }

  @Test
  void emptyEverywhereIsEmpty() {
    assertEquals(List.of(), ProjectListCommand.merge(List.of(), List.of()));
  }

  @Test
  void jsonRendersStatusAndIpPerProject() {
    var notProvisioned = new ContainerInfo("billing", new ContainerState.NotCreated(), null);
    var json = ProjectListCommand.renderJson(List.of(running("acme"), notProvisioned));

    assertEquals(
        "[{\"name\": \"acme\", \"status\": \"running\", \"ip\": \"10.0.0.5\"}, "
            + "{\"name\": \"billing\", \"status\": \"not_provisioned\"}]",
        json);
  }

  @Test
  void aFrozenContainerListsAsFrozen() {
    var frozen = new ContainerInfo("acme", new ContainerState.Frozen(), null);

    assertEquals(
        "[{\"name\": \"acme\", \"status\": \"frozen\"}]",
        ProjectListCommand.renderJson(List.of(frozen)));
  }

  @Test
  void jsonForNoProjectsIsAnEmptyArray() {
    assertEquals("[]", ProjectListCommand.renderJson(List.of()));
  }
}
