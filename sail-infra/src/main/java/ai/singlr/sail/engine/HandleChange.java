/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.NodeRound;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a change of this box's sync identity does to the runs it executed. A box's runs carry its
 * handle, and main takes a run only from the box whose handle it carries, so a handle change is
 * refused while a run main holds under the old handle would be stranded by it, and once the new
 * identity is written every run main has not taken is stamped as the box's ({@link
 * NodeRound#stampUnheld}); a box that becomes main stamps each run it made that carries no node
 * ({@link RunStore#stampUnstamped}). Called where the box's identity is written: {@code sail host
 * config set}, {@code sail join} and {@code sail host sync}.
 */
public final class HandleChange {

  /** Writes the new identity; the one step between the refusal and the stamps. */
  @FunctionalInterface
  public interface Write {
    void run() throws Exception;
  }

  private HandleChange() {}

  /**
   * Writes {@code updated} over {@code current} at {@code hostYamlPath} as a change of this box's
   * identity in its database at {@code dbPath} ({@link #apply}). Returns the runs stamped for the
   * new identity.
   */
  public static List<String> write(
      Path hostYamlPath,
      Path dbPath,
      HostYaml current,
      HostYaml updated,
      SyncOperations.Channels channels)
      throws Exception {
    return apply(
        dbPath,
        current.sync(),
        updated.sync(),
        channels,
        () -> YamlUtil.dumpToFile(updated.toMap(), hostYamlPath));
  }

  /**
   * Moves this box, whose database is {@code dbPath}, from {@code before} to {@code after} through
   * {@code write}. Refused, before anything is written, while a run main holds under the old handle
   * is live here or carries a change main has not taken — on main, while any run this box executed
   * is live — naming each and the fix. A node first asks its main, over {@code channels}, which of
   * the runs it never heard acknowledged main holds; when main cannot be asked, it is refused too.
   * Every sync round of the box is held off from the ask until the stamps are written ({@link
   * SyncOperations#holdRounds}), so none can offer a run main would then hold under the old handle.
   * Returns the runs stamped for the new identity.
   */
  public static List<String> apply(
      Path dbPath,
      SyncConfig before,
      SyncConfig after,
      SyncOperations.Channels channels,
      Write write)
      throws Exception {
    var renamed = !Objects.equals(before.handle(), after.handle());
    var promoted = after.isMain() && !before.isMain();
    if (!(renamed || promoted) || !Files.exists(dbPath)) {
      write.run();
      return List.of();
    }
    try (var db = Sqlite.open(dbPath);
        var rounds = SyncOperations.holdRounds(db)) {
      var runs = new RunStore(db);
      var held = renamed ? heldByMain(db, before, after, channels) : Set.<String>of();
      if (renamed) {
        var stranded =
            before.isMain()
                ? runs.liveUnder(before.handle())
                : runs.heldUnder(before.handle(), held);
        if (!stranded.isEmpty()) {
          throw new IllegalStateException(refusal(before, after, stranded));
        }
      }
      write.run();
      return after.isMain()
          ? runs.stampUnstamped(after.handle())
          : NodeRound.stampUnheld(db, after.handle(), held);
    }
  }

  /**
   * The runs main says it took whose answer this box never heard. A missing acknowledgement does
   * not prove main never took a run: its answer may have been lost. So a node asks main which it
   * holds ({@link SyncOperations#acknowledgeHeld}), and those are weighed as held under the old
   * handle and never re-stamped. When main cannot be asked, nothing is changed.
   */
  private static Set<String> heldByMain(
      Sqlite db, SyncConfig before, SyncConfig after, SyncOperations.Channels channels) {
    if (!before.isNode() || new RunStore(db).unacknowledged().isEmpty()) {
      return Set.of();
    }
    try {
      return SyncOperations.acknowledgeHeld(db, BoxIdentity.resolved(before), channels);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot change this box's sync handle from '"
              + named(before.handle())
              + "' to '"
              + named(after.handle())
              + "': main ("
              + before.main()
              + ") must say which of this box's runs it took before any is re-stamped, and asking"
              + " failed: "
              + e.getMessage()
              + ". Change the handle once main is reachable.",
          e);
    }
  }

  private static String refusal(
      SyncConfig before, SyncConfig after, List<RunStore.RunRow> stranded) {
    var named =
        stranded.stream()
            .map(
                run ->
                    run.id()
                        + " ("
                        + (Strings.isBlank(run.specId()) ? run.role() : "spec " + run.specId())
                        + ", "
                        + run.status()
                        + ")")
            .collect(Collectors.joining(", "));
    var from = named(before.handle());
    var to = named(after.handle());
    var why =
        before.isMain()
            ? "these runs this box executed as '" + from + "' are still live: "
            : "main holds these runs under '"
                + from
                + "', and they are still running here or have changes main has not taken: ";
    return "Cannot change this box's sync handle from '"
        + from
        + "' to '"
        + to
        + "': "
        + why
        + named
        + ". Stop them or let them finish"
        + (before.isMain()
            ? ""
            : ", run 'sail sync' while main still knows this box as '" + from + "'")
        + ", then change the handle.";
  }

  private static String named(String handle) {
    return Objects.toString(handle, "");
  }
}
