/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.ConnectEnvironment;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NodeIdentity;
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
import java.util.function.Function;

/** The control-plane database and operations wiring shared by host commands and the server. */
public final class OperationsFactory {
  private OperationsFactory() {}

  public static HostOperations open() {
    return open(SailPaths.controlPlaneDb());
  }

  public static HostOperations open(Path path) {
    return open(
        path,
        db ->
            create(
                db,
                new ShellExecutor(false),
                SailPaths.PROJECT_DESCRIPTOR,
                null,
                null,
                SyncScheduler.disabled(),
                SessionYield.NONE));
  }

  public static HostOperations open(
      ShellExec shell, String file, OperationHooks hooks, SessionYield sessionYield) {
    return open(SailPaths.controlPlaneDb(), db -> create(db, shell, file, hooks, sessionYield));
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
      Sqlite db, ShellExec shell, String file, OperationHooks hooks, SessionYield sessionYield) {
    return create(db, shell, file, null, null, SyncScheduler.disabled(), sessionYield, hooks);
  }

  public static SailOperations create(
      Sqlite db,
      ShellExec shell,
      String file,
      EventBus bus,
      EventSubscriber audit,
      SyncScheduler scheduler,
      SessionYield sessionYield) {
    return create(
        db,
        shell,
        file,
        bus,
        audit,
        scheduler,
        sessionYield,
        new OperationHooks(
            event -> {
              if (bus != null) bus.publish(event);
            },
            new WatcherSpawner(shell, WatcherSpawner::spawnProcess),
            DispatchOperations.autoSnapshotter(shell),
            DispatchOperations.shellLauncher(shell),
            DispatchOperations.Listener.NONE,
            StopOperations.Listener.NONE));
  }

  private static SailOperations create(
      Sqlite db,
      ShellExec shell,
      String file,
      EventBus bus,
      EventSubscriber audit,
      SyncScheduler scheduler,
      SessionYield sessionYield,
      OperationHooks hooks) {
    return new SailOperations(
            shell,
            file,
            WatcherSpawner::spawnProcess,
            bus,
            audit instanceof AuditPersister persister ? persister : null,
            new SpecStore(db),
            new ReviewStore(db),
            new RunStore(db),
            new ProjectStore(db),
            ConnectEnvironment::detect,
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
                NodeIdentity::config,
                SshSyncChannel::open));
  }
}
