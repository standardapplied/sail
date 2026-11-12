/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The device fixtures under {@code devices/}, captured from real tools or hand-written in their
 * format ({@code devices/README.txt}), for every test that drives {@link PoolDevices}.
 */
public final class DeviceFixtures {

  /** The lsblk call {@link PoolDevices} makes, as the scripted shell matches it. */
  public static final String LSBLK = String.join(" ", PoolDevices.LSBLK);

  /** The runner's capture plus spare disks sdb to sdk, zram0 and nbd0. */
  public static final String SPARES = "handwritten-runner-with-spares.lsblk.json";

  /** {@code wipefs --json} of a device that holds nothing, as the runner printed it. */
  public static final String NOTHING_HELD = read("github-runner-sda14.wipefs.json");

  private DeviceFixtures() {}

  /** The fixture {@code name}. */
  public static String read(String name) {
    try (var in =
        Objects.requireNonNull(
            DeviceFixtures.class.getResourceAsStream("/devices/" + name), name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The spares layout with only the disks named {@code kept} at the top. */
  @SuppressWarnings("unchecked")
  public static String spares(Set<String> kept) {
    var layout = YamlUtil.parseMap(read(SPARES));
    var disks = (List<Map<String, Object>>) layout.get("blockdevices");
    return YamlUtil.dumpJson(
        Map.of("blockdevices", disks.stream().filter(d -> kept.contains(d.get("path"))).toList()));
  }

  /** The spares layout with {@code path}, a top-level disk, mounted at {@code mount}. */
  @SuppressWarnings("unchecked")
  public static String sparesWithMount(String path, String mount) {
    var layout = YamlUtil.parseMap(read(SPARES));
    var disks = (List<Map<String, Object>>) layout.get("blockdevices");
    disks.stream()
        .filter(d -> path.equals(d.get("path")))
        .forEach(d -> d.put("mountpoints", List.of(mount)));
    return YamlUtil.dumpJson(layout);
  }
}
