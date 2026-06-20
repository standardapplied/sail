/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.engine.ContainerManager.ContainerInfo;
import ai.singlr.sail.engine.ContainerManager.ResourceLimits;
import ai.singlr.sail.engine.ContainerState;
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
  void jsonForNoProjectsIsAnEmptyArray() {
    assertEquals("[]", ProjectListCommand.renderJson(List.of()));
  }
}
