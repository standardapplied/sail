/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.Child;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ci/images runner-packages}, the incus lanes' own packages, over a runner's apt pointed at
 * a local mirror of stand-ins named as the real ones are, with no network: a fetch keeps every
 * package incus and the ZFS tools need whatever the fetching runner holds, and an install on a
 * runner holding other packages takes what it lacks from those files alone, or stops naming the one
 * they lack. Apt simulates each install, as no test runs as root.
 */
@EnabledOnOs(OS.LINUX)
class CiImagesTest {

  private static final Path SCRIPT = Path.of("..", "ci", "images").toAbsolutePath();
  private static final Map<String, String> MIRROR =
      Map.of(
          "incus", "incus-client (>= 0.5), librados2, librbd1",
          "incus-client", "",
          "librados2", "libboost-thread",
          "librbd1", "librados2",
          "libboost-thread", "",
          "zfsutils-linux", "libzfs",
          "libzfs", "");

  @TempDir Path dir;
  private Path cache;

  @BeforeEach
  void aMirrorOfStandIns() throws Exception {
    var mirror = Files.createDirectories(dir.resolve("mirror"));
    for (var stand : MIRROR.entrySet()) {
      var name = stand.getKey();
      var control = dir.resolve("build").resolve(name).resolve("DEBIAN").resolve("control");
      Files.createDirectories(control.getParent());
      Files.writeString(
          control,
          stanza(name, "1.0", stand.getValue().isEmpty() ? "" : "Depends: " + stand.getValue()));
      run(
          Map.of(),
          "dpkg-deb",
          "--build",
          control.getParent().getParent().toString(),
          mirror.resolve(name + "_1.0_all.deb").toString());
    }
    Files.writeString(mirror.resolve("Packages"), run(Map.of(), "dpkg-scanpackages", ".").stdout());
    cache = dir.resolve("runner-packages");
  }

  @Test
  void aFetchOnARunnerHoldingSomeOfThePackagesKeepsEveryPackageTheyNeed() throws Exception {
    fetchOnARunnerHolding("librados2=1.0", "libboost-thread=1.0");

    try (var files = Files.list(cache)) {
      assertEquals(
          MIRROR.keySet(),
          files
              .map(file -> file.getFileName().toString())
              .filter(name -> name.endsWith(".deb"))
              .map(name -> name.substring(0, name.indexOf('_')))
              .collect(Collectors.toSet()));
    }
  }

  @Test
  void anInstallOnARunnerLackingSomeTakesThemFromTheFilesAndKeepsWhatItHolds() throws Exception {
    fetchOnARunnerHolding("librados2=1.0", "libboost-thread=1.0");

    var installed = installOnARunnerHolding("incus-client=0.9", "libboost-thread=2.0");

    assertEquals(0, installed.exit(), installed.stdout() + installed.stderr());
    assertEquals(Set.of("incus", "librados2", "librbd1"), installs(installed.stdout()));
  }

  @Test
  void anInstallFromFilesThatLackAPackageStopsNamingItBeforeDpkgRuns() throws Exception {
    fetchOnARunnerHolding("librados2=1.0", "libboost-thread=1.0");
    Files.delete(cache.resolve("librbd1_1.0_all.deb"));

    var installed = installOnARunnerHolding();

    assertNotEquals(0, installed.exit(), installed.stdout());
    assertTrue(
        installed.stdout().contains("incus : Depends: librbd1 but it is not installable"),
        installed.stdout());
    assertEquals(Set.of(), installs(installed.stdout()));
  }

  private void fetchOnARunnerHolding(String... held) throws Exception {
    var apt = Map.of("APT_CONFIG", runner("fetcher", mirrorSource(), "", held).toString());
    run(apt, "apt-get", "-qq", "update");
    run(apt, SCRIPT.toString(), "runner-packages", "fetch", cache.toString());
  }

  private Child.Ended installOnARunnerHolding(String... held) throws Exception {
    var apt = runner("restorer", "", "APT::Get::Simulate \"true\";", held);
    return Child.run(
        builder(
            Map.of("APT_CONFIG", apt.toString()),
            SCRIPT.toString(),
            "runner-packages",
            "install",
            cache.toString(),
            "incus"));
  }

  /**
   * The mirror as a runner's source: {@code copy:} keeps each fetched file in apt's archives
   * directory, as a download from a mirror does, where {@code file:} uses it in place.
   */
  private String mirrorSource() {
    return "deb [trusted=yes] copy:" + dir.resolve("mirror") + " ./\n";
  }

  /**
   * A runner's apt: its sources, its dpkg status holding {@code held} ({@code name=version}), and
   * none of the host's configuration, preferences or lists.
   */
  private Path runner(String name, String sources, String setting, String... held)
      throws IOException {
    var root = Files.createDirectories(dir.resolve(name));
    Files.createDirectories(root.resolve("lists").resolve("partial"));
    Files.createDirectories(root.resolve("parts"));
    Files.writeString(root.resolve("sources.list"), sources);
    var status = new StringBuilder();
    for (var version : held) {
      var named = version.split("=");
      status.append(stanza(named[0], named[1], "Status: install ok installed"));
    }
    Files.writeString(root.resolve("status"), status.toString());
    var config = root.resolve("apt.conf");
    Files.writeString(
        config,
        Stream.of(
                "Dir::Etc::SourceList \"" + root.resolve("sources.list") + "\";",
                "Dir::Etc::SourceParts \"" + root.resolve("parts") + "\";",
                "Dir::Etc::Parts \"" + root.resolve("parts") + "\";",
                "Dir::Etc::Preferences \"" + root.resolve("preferences") + "\";",
                "Dir::Etc::PreferencesParts \"" + root.resolve("parts") + "\";",
                "Dir::State::Lists \"" + root.resolve("lists") + "\";",
                "Dir::State::status \"" + root.resolve("status") + "\";",
                "Dir::Cache \"" + root.resolve("cache") + "\";",
                setting)
            .collect(Collectors.joining("\n", "", "\n")));
    return config;
  }

  private static String stanza(String name, String version, String field) {
    return "Package: "
        + name
        + "\nVersion: "
        + version
        + "\nArchitecture: all\nMaintainer: CI <ci@example.com>\n"
        + (field.isEmpty() ? "" : field + "\n")
        + "Description: a stand-in\n\n";
  }

  private static Set<String> installs(String simulated) {
    return simulated
        .lines()
        .filter(line -> line.startsWith("Inst "))
        .map(line -> line.split(" ")[1])
        .collect(Collectors.toSet());
  }

  private Child.Ended run(Map<String, String> env, String... command) throws Exception {
    var ran = Child.run(builder(env, command));
    assertEquals(
        0, ran.exit(), String.join(" ", command) + " failed: " + ran.stdout() + ran.stderr());
    return ran;
  }

  private ProcessBuilder builder(Map<String, String> env, String... command) throws IOException {
    var builder = new ProcessBuilder(new ArrayList<>(List.of(command)));
    builder.directory(dir.resolve("mirror").toFile());
    builder.environment().putAll(env);
    builder.environment().put("TMPDIR", Files.createDirectories(dir.resolve("tmp")).toString());
    return builder;
  }
}
