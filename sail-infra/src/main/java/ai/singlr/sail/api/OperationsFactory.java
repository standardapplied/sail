/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.BoxIdentity;
import ai.singlr.sail.engine.ConnectEnvironment;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SshSyncChannel;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/** The control-plane database and operations wiring shared by host commands and the server. */
public final class OperationsFactory {
  private OperationsFactory() {}

  /** The operations over this box's control-plane database. */
  public static HostOperations open() {
    return open(SyncOperations.PLAIN_NOTICES);
  }

  /** As {@link #open()}, a sync round's notices going to {@code notice}. */
  public static HostOperations open(Consumer<Remedy> notice) {
    return opened(SailPaths.controlPlaneDb(), notice);
  }

  public static HostOperations open(Path path) {
    return opened(path, SyncOperations.PLAIN_NOTICES);
  }

  private static HostOperations opened(Path path, Consumer<Remedy> notice) {
    return open(
        path,
        db ->
            create(
                db,
                new ShellExecutor(false),
                null,
                null,
                SyncScheduler.disabled(),
                SessionYield.NONE,
                defaultHooks(new ShellExecutor(false), null),
                notice));
  }

  public static HostOperations open(
      ShellExec shell, OperationHooks hooks, SessionYield sessionYield) {
    return open(SailPaths.controlPlaneDb(), db -> create(db, shell, hooks, sessionYield));
  }

  private static HostOperations open(Path path, Function<Sqlite, SailOperations> create) {
    var database = Sqlite.open(path);
    try {
      return create.apply(database).closeWith(database::close);
    } catch (RuntimeException e) {
      database.close();
      throw e;
    }
  }

  public static SailOperations create(
      Sqlite db, ShellExec shell, OperationHooks hooks, SessionYield sessionYield) {
    return create(
        db,
        shell,
        null,
        null,
        SyncScheduler.disabled(),
        sessionYield,
        hooks,
        SyncOperations.PLAIN_NOTICES);
  }

  public static SailOperations create(
      Sqlite db,
      ShellExec shell,
      EventBus bus,
      EventSubscriber audit,
      SyncScheduler scheduler,
      SessionYield sessionYield) {
    return create(
        db,
        shell,
        bus,
        audit,
        scheduler,
        sessionYield,
        defaultHooks(shell, bus),
        SyncOperations.PLAIN_NOTICES);
  }

  private static OperationHooks defaultHooks(ShellExec shell, EventBus bus) {
    return new OperationHooks(
        event -> {
          if (bus != null) bus.publish(event);
        },
        new WatcherSpawner(shell, WatcherSpawner::spawnProcess),
        DispatchOperations.autoSnapshotter(shell),
        DispatchOperations.shellLauncher(shell),
        DispatchOperations.Listener.NONE,
        StopOperations.Listener.NONE);
  }

  private static SailOperations create(
      Sqlite db,
      ShellExec shell,
      EventBus bus,
      EventSubscriber audit,
      SyncScheduler scheduler,
      SessionYield sessionYield,
      OperationHooks hooks,
      Consumer<Remedy> notice) {
    var projectStore = new ProjectStore(db);
    return new SailOperations(
            shell,
            ProjectReader.ofCatalog(projectStore),
            WatcherSpawner::spawnProcess,
            bus,
            audit instanceof AuditPersister persister ? persister : null,
            new SpecStore(db),
            new ReviewStore(db),
            new RunStore(db),
            projectStore,
            ConnectEnvironment.detector(),
            scheduler,
            new FdeStore(db),
            sessionYield,
            hooks)
        .useMessages(new MessageStore(db))
        .useRooms(new RoomStore(db))
        .useBoxCredentials(new BoxCredentialStore(db))
        .useEvents(new EventStore(db))
        .useControlPlane(
            db,
            SailPaths.projectsDir(),
            new SyncOperations(
                db,
                HostInfo.hostname(),
                SailPaths.projectsDir(),
                BoxIdentity::config,
                SshSyncChannel::open,
                notice));
  }
}
