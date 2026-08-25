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
            config("api", "web"),
            new Spec(
                "ui",
                "test",
                "UI",
                SpecStatus.PENDING,
                null,
                List.of(),
                List.of("web"),
                null,
                null,
                null,
                "feat/ui"),
            List.of());

    assertEquals(List.of("web"), targets.stream().map(SailYaml.Repo::path).toList());
  }

  @Test
  void overrideWinsOverSpecRepo() {
    var targets =
        DispatchRepos.resolve(
            config("api", "web"),
            new Spec(
                "ui",
                "test",
                "UI",
                SpecStatus.PENDING,
                null,
                List.of(),
                List.of("api"),
                null,
                null,
                null,
                "feat/ui"),
            List.of("web"));

    assertEquals(List.of("web"), targets.stream().map(SailYaml.Repo::path).toList());
  }

  @Test
  void fallsBackToSingleConfiguredRepo() {
    var targets =
        DispatchRepos.resolve(
            config("api"),
            new Spec(
                "ui",
                "test",
                "UI",
                SpecStatus.PENDING,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                "feat/ui"),
            List.of());

    assertEquals(List.of("api"), targets.stream().map(SailYaml.Repo::path).toList());
  }

  @Test
  void leavesMultiRepoDispatchUntargetedWhenSpecOmitsRepo() {
    var targets =
        DispatchRepos.resolve(
            config("api", "web"),
            new Spec(
                "ui",
                "test",
                "UI",
                SpecStatus.PENDING,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                "feat/ui"),
            List.of());

    assertTrue(targets.isEmpty());
  }

  @Test
  void rejectsUnknownRepo() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DispatchRepos.resolve(
                config("api"),
                new Spec(
                    "ui",
                    "test",
                    "UI",
                    SpecStatus.PENDING,
                    null,
                    List.of(),
                    List.of("web"),
                    null,
                    null,
                    null,
                    "feat/ui"),
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
