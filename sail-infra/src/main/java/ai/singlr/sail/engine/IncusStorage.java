/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Incus's storage pools: the one place that builds the command creating one, and that reads them
 * back as Incus has them. Sail hands Incus the whole source and makes nothing inside a pool itself;
 * Incus creates the zpool on a device, a mirror of two or a file ({@code zpool create -m none -O
 * compression=on}, with {@code mirror} before a mirror's two devices; never {@code -f}: Sail never
 * passes {@code source.wipe}) and owns every dataset in it.
 */
public final class IncusStorage {

  private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(3);
  private static final String MIRROR = "mirror=";
  private static final String VDEVS = "storage_zfs_vdev";

  private final ShellExec shell;

  public IncusStorage(ShellExec shell) {
    this.shell = shell;
  }

  /**
   * A pool as Incus has it: its {@code source} is what it was made from, the device or file for a
   * zfs pool, the directory for a dir pool; {@code zpool} is the zpool a zfs pool is, null for
   * another driver; {@code size} is the size Incus made a file-backed pool's file, null for any
   * other; {@code space} is empty when Incus cannot measure it; {@code status} is Incus's own
   * ({@value #CREATED} once Incus finished making it).
   */
  public record Pool(
      String name,
      String driver,
      String source,
      String zpool,
      String size,
      Optional<Space> space,
      String status) {

    /** Incus's status of a pool it finished making ({@code api.StoragePoolStatusCreated}). */
    public static final String CREATED = "Created";

    /**
     * Incus's status of a pool it made that failed to start ({@code StoragePoolStatusUnvailable}).
     */
    static final String UNAVAILABLE = "Unavailable";

    private static final Set<String> UNFINISHED = Set.of("Pending", "Errored");

    /** A pool Incus finished making. */
    public Pool(
        String name,
        String driver,
        String source,
        String zpool,
        String size,
        Optional<Space> space) {
      this(name, driver, source, zpool, size, space, CREATED);
    }

    /**
     * Refuses a pool Incus did not finish making: one whose record incusd left {@code Pending} (it
     * stopped between its driver's create, a zfs pool's {@code zpool create}, and marking the pool
     * created) or {@code Errored}, which is never taken for one that exists, and over which nothing
     * is made. Incus's {@code incus storage delete} of a {@code Pending} pool removes only its
     * record and runs no driver delete ({@code storagePoolDelete}), so a zpool it made stays for
     * the operator to destroy; of an {@code Errored} one it runs the driver's delete, which
     * destroys the zpool its record names when it holds only Incus's own datasets. One Incus
     * reports {@code Unavailable} it has and cannot start ({@link #requireAvailable}). Every other
     * status, {@code Unknown} among them, is kept as it is.
     */
    public void requireFinished() {
      requireAvailable();
      if (!UNFINISHED.contains(status)) {
        return;
      }
      var zfs = HostYaml.BACKEND_ZFS.equals(driver);
      var zpool = Objects.requireNonNullElse(this.zpool, name);
      var pending = "Pending".equals(status);
      throw new IllegalStateException(
          "Incus's pool '"
              + name
              + "' is "
              + status
              + ", not Created: Incus did not finish making it, so it is not taken for a pool that"
              + " exists and nothing is made over it. Remove Incus's record of it with sudo incus"
              + " storage delete "
              + name
              + (zfs && pending
                  ? ", which leaves any zpool "
                      + zpool
                      + " Incus made; see what that holds with sudo zfs list -r "
                      + zpool
                      + " and, if nothing on it is wanted, destroy it yourself with sudo zpool"
                      + " destroy "
                      + zpool
                  : "")
              + (zfs && !pending
                  ? ", which also destroys zpool "
                      + zpool
                      + " if its record names it and it holds only Incus's own datasets"
                  : "")
              + ", then run this again.");
    }

    /**
     * Refuses a pool Incus reports {@code Unavailable}: it has the pool and cannot start it, which
     * no profile and no "ready" may stand on. A zfs pool whose record names no zpool is one incusd
     * stopped making after its {@code zpool create} (Incus 7.6 writes the zpool's name to the
     * record only after mounting it, and reports such a pool {@code Unavailable} once restarted):
     * {@code incus storage delete} removes that record and leaves the zpool, which holds only its
     * own root dataset, for the operator to destroy. Any other was made, and is mended, not
     * removed.
     */
    private void requireAvailable() {
      if (!UNAVAILABLE.equals(status)) {
        return;
      }
      var zfs = HostYaml.BACKEND_ZFS.equals(driver);
      throw new IllegalStateException(
          "Incus's pool '"
              + name
              + "' is Unavailable: Incus has the pool but cannot start it, so it is not taken for a"
              + " pool that exists and nothing is changed. "
              + (zfs && zpool == null
                  ? "Its record names no zpool, as when incusd stopped while making it: remove the"
                      + " half-made pool with sudo incus storage delete "
                      + name
                      + ", then sudo zfs list -r "
                      + name
                      + ", and if it lists only "
                      + name
                      + " itself, sudo zpool destroy "
                      + name
                      + "; then run this again."
                  : "It was made: see why with sudo incus storage show "
                      + name
                      + (zfs ? " and sudo zpool status " + zpool : "")
                      + ", mend that, then run this again."));
    }

    /** Refuses a pool of {@code driver} under this pool's name, which Incus has with another. */
    public void requireDriver(String driver) {
      if (!this.driver.equals(driver)) {
        throw new IllegalArgumentException(
            "Incus already has a "
                + this.driver
                + " pool named '"
                + name
                + "', so no "
                + driver
                + " pool can take that name. Name the new pool with --pool <name>.");
      }
    }

    /** The pool as one line of {@code host status}. */
    public String summary() {
      return summary(said(source));
    }

    /** The pool as one line of {@code host status}, on {@code on}. */
    public String summary(String on) {
      var measured =
          space
              .map(
                  s ->
                      Strings.gibibytes(s.total() - s.used())
                          + " free of "
                          + Strings.gibibytes(s.total()))
              .orElse("free space unknown");
      return name + " (" + driver + ", " + on + "): " + measured;
    }
  }

  /**
   * The source Incus is handed for {@code source}, as Incus then records it: the device's path, or
   * {@code mirror=<one>,<other>} ({@code driver_zfs.go} {@code parseSource}); none for any other.
   */
  static String sourceOf(PoolSource source) {
    return switch (source) {
      case PoolSource.Device device -> device.path();
      case PoolSource.Mirror mirror -> MIRROR + mirror.one().path() + "," + mirror.other().path();
      default -> null;
    };
  }

  /**
   * The paths a pool's {@code source} names: a mirror's two devices, the one device or file of any
   * other pool, none for a zpool's name.
   */
  public static List<String> devices(String source) {
    if (source.startsWith(MIRROR)) {
      return List.of(source.substring(MIRROR.length()).split(","));
    }
    return source.startsWith("/") ? List.of(source) : List.of();
  }

  /** A pool's {@code source} as the operator reads it: a mirror of its two devices, by name. */
  public static String said(String source) {
    var devices = devices(source);
    return source.startsWith(MIRROR) && devices.size() == 2
        ? "a mirror of " + devices.getFirst() + " and " + devices.getLast()
        : source;
  }

  /**
   * Refuses, before anything is made, an Incus that cannot make a mirrored pool: one without the
   * {@value #VDEVS} API extension (Incus 6.0.0, as Ubuntu 24.04 packages it), which would read a
   * mirror's source as the name of a dataset. Its remedy is what moves such a box: Zabbly's source,
   * then {@code host incus upgrade}, which moves the packages Sail holds to apt's candidate; apt
   * itself refuses to replace held packages, and without Zabbly's source the candidate is 6.0.0.
   */
  public void requireMirrors() throws IOException, InterruptedException, TimeoutException {
    var server = shell.exec(List.of("incus", "query", "/1.0"));
    var extensions =
        server.ok()
                && YamlUtil.parseMap(server.stdout()).get("api_extensions")
                    instanceof List<?> listed
            ? listed
            : List.of();
    if (!extensions.contains(VDEVS)) {
      throw new IllegalStateException(
          "This box's Incus cannot make a mirrored pool: it lacks the "
              + VDEVS
              + " API extension, which Incus has from 6.0.1 on"
              + (server.ok() ? "" : " (incus query /1.0: " + server.reason() + ")")
              + ". Nothing was written. Add Zabbly's stable apt source as Zabbly's README says"
              + " (https://github.com/zabbly/incus), then move the Incus Sail holds to it with sudo"
              + " sail host incus upgrade; then run this again.");
    }
  }

  /** A pool's size and what is used of it, in bytes. */
  public record Space(long used, long total) {}

  /** The command that creates pool {@code name} from {@code source}. */
  static List<String> createCommand(String name, PoolSource source) {
    var command = new ArrayList<>(List.of("incus", "storage", "create", name, source.driver()));
    switch (source) {
      case PoolSource.Dir dir -> {}
      case PoolSource.Device device -> command.add("source=" + sourceOf(device));
      case PoolSource.Mirror mirror -> command.add("source=" + sourceOf(mirror));
      case PoolSource.File file -> command.add("size=" + file.sizeMb() + "MiB");
      case PoolSource.Zpool zpool ->
          throw new IllegalStateException(
              "Incus's pool '"
                  + name
                  + "' on zpool "
                  + zpool.name()
                  + " is gone since this run began, and Sail makes no pool from a zpool it did"
                  + " not make: run this command again.");
    }
    return List.copyOf(command);
  }

  /** Creates pool {@code name} from {@code source}, answering as Incus did. */
  public ShellExec.Result create(String name, PoolSource source)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(createCommand(name, source), null, CREATE_TIMEOUT);
  }

  /**
   * Why a create failed, as Incus said, except that zpool's offer to force it is never passed on:
   * where zpool refused the device, what it found there, and that Sail erases nothing.
   */
  static String refused(ShellExec.Result failed) {
    var reason = failed.reason();
    var forced = "use '-f' to override the following errors:";
    var at = reason.indexOf(forced);
    if (at < 0) {
      return reason;
    }
    var found = reason.substring(at + forced.length()).strip().replaceAll("\\)$", "").strip();
    return "zpool refused the device: "
        + found
        + ". Sail erases nothing, so nothing was written: see what the device holds, then run this"
        + " again.";
  }

  /** Whether {@code failed}, a create's answer, says incusd does not see the ZFS tools. */
  static boolean zfsUnseen(ShellExec.Result failed) {
    return failed.stderr().contains("Required tool '");
  }

  /** Every pool Incus has, or why it could not say. */
  public List<Pool> pools() throws IOException, InterruptedException, TimeoutException {
    var listed = shell.exec(List.of("incus", "storage", "list", "--format", "json"));
    if (!listed.ok()) {
      throw new IOException("incus storage list failed: " + listed.reason());
    }
    var pools = new ArrayList<Pool>();
    for (var entry : YamlUtil.parseList(listed.stdout())) {
      var name = String.valueOf(entry.get("name"));
      var config = entry.get("config") instanceof Map<?, ?> map ? map : Map.of();
      pools.add(
          new Pool(
              name,
              String.valueOf(entry.get("driver")),
              source(config),
              (String) config.get("zfs.pool_name"),
              Strings.isBlank((String) config.get("size")) ? null : (String) config.get("size"),
              space(name),
              Objects.toString(entry.get("status"), Pool.CREATED)));
    }
    return pools;
  }

  /** Every pool Incus has; none where Incus does not answer, as on a box it is not on yet. */
  public List<Pool> poolsOrNone() throws InterruptedException {
    try {
      return pools();
    } catch (IOException | TimeoutException e) {
      return List.of();
    }
  }

  /** The pool named {@code name}, when Incus has one. */
  public Optional<Pool> find(String name)
      throws IOException, InterruptedException, TimeoutException {
    return pools().stream().filter(pool -> pool.name().equals(name)).findFirst();
  }

  private static String source(Map<?, ?> config) {
    var initial = config.get("volatile.initial_source");
    return Objects.toString(
        initial != null && Strings.isNotBlank(initial.toString()) ? initial : config.get("source"),
        "unknown");
  }

  private Optional<Space> space(String name)
      throws IOException, InterruptedException, TimeoutException {
    var queried =
        shell.exec(List.of("incus", "query", "/1.0/storage-pools/" + name + "/resources"));
    var space = queried.ok() ? YamlUtil.parseMap(queried.stdout()).get("space") : null;
    if (space instanceof Map<?, ?> measured
        && measured.get("used") instanceof Number used
        && measured.get("total") instanceof Number total) {
      return Optional.of(new Space(used.longValue(), total.longValue()));
    }
    return Optional.empty();
  }
}
