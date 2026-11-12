/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
  public static final String LSBLK = String.join(" ", BlockTree.LSBLK);

  /** A real GCP VM's tree (the system on sda, blank sdb and sdc) plus hand-written spare disks. */
  public static final String SPARES = "handwritten-gcp-blank-with-spares.lsblk.json";

  /** What {@code wipefs} lists on a device that holds nothing: nothing at all. */
  public static final String NOTHING_HELD = "";

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

  /**
   * What {@code wipefs <device>} printed for each device of the GCP {@code layout}, keyed by
   * device, from the capture's {@code ### wipefs <device>} blocks.
   */
  public static Map<String, String> listings(String layout) {
    var listed = new LinkedHashMap<String, String>();
    blocks(read("gcp/" + layout + ".wipefs-list.txt"), "### wipefs ")
        .forEach((device, block) -> listed.put(device, block.output()));
    return listed;
  }

  /**
   * Each device of the GCP {@code layout} that its {@code wipefs --no-act --all} found busy, with
   * what it printed: the exclusive open fails before any filter or flag counts, so the probe's
   * answer on a busy device is the capture's.
   */
  public static Map<String, String> busy(String layout) {
    var busy = new LinkedHashMap<String, String>();
    blocks(read("gcp/" + layout + ".wipefs-noact-all.txt"), "### wipefs --no-act --all ")
        .forEach(
            (device, block) -> {
              if (block.exit() != 0) {
                busy.put(device, block.output());
              }
            });
    return busy;
  }

  private record Block(String output, int exit) {}

  private static Map<String, Block> blocks(String capture, String header) {
    var blocks = new LinkedHashMap<String, Block>();
    for (var chunk : capture.split("(?m)^" + header)) {
      if (chunk.isBlank()) {
        continue;
      }
      var lines = chunk.lines().toList();
      var exit = lines.getLast();
      var output = String.join("\n", lines.subList(1, lines.size() - 1));
      blocks.put(
          lines.getFirst().strip(),
          new Block(
              output.isEmpty() ? "" : output + "\n",
              Integer.parseInt(exit.substring(exit.indexOf('=') + 1))));
    }
    return blocks;
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
