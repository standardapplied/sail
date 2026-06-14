/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DispatchReposTest {

  @Test
  void usesSpecRepoWhenProjectHasMultipleRepos() {
    var targets =
        DispatchRepos.resolve(
            config("sing", "chorus"),
            new Spec("ui", "UI", SpecStatus.PENDING, null, List.of(), List.of("chorus"), "feat/ui"),
            List.of());

    assertEquals(List.of("chorus"), targets.stream().map(SailYaml.Repo::path).toList());
  }

  @Test
  void overrideWinsOverSpecRepo() {
    var targets =
        DispatchRepos.resolve(
            config("sing", "chorus"),
            new Spec("ui", "UI", SpecStatus.PENDING, null, List.of(), List.of("sing"), "feat/ui"),
            List.of("chorus"));

    assertEquals(List.of("chorus"), targets.stream().map(SailYaml.Repo::path).toList());
  }

  @Test
  void fallsBackToSingleConfiguredRepo() {
    var targets =
        DispatchRepos.resolve(
            config("sing"),
            new Spec("ui", "UI", SpecStatus.PENDING, null, List.of(), List.of(), "feat/ui"),
            List.of());

    assertEquals(List.of("sing"), targets.stream().map(SailYaml.Repo::path).toList());
  }

  @Test
  void leavesMultiRepoDispatchUntargetedWhenSpecOmitsRepo() {
    var targets =
        DispatchRepos.resolve(
            config("sing", "chorus"),
            new Spec("ui", "UI", SpecStatus.PENDING, null, List.of(), List.of(), "feat/ui"),
            List.of());

    assertTrue(targets.isEmpty());
  }

  @Test
  void rejectsUnknownRepo() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DispatchRepos.resolve(
                config("sing"),
                new Spec(
                    "ui", "UI", SpecStatus.PENDING, null, List.of(), List.of("chorus"), "feat/ui"),
                List.of()));
  }

  private static SailYaml config(String... paths) {
    return SailYaml.fromMap(
        Map.of(
            "name",
            "workspace",
            "repos",
            Arrays.stream(paths)
                .map(
                    path ->
                        Map.<String, Object>of("url", "https://example.com/" + path, "path", path))
                .toList()));
  }
}
