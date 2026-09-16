/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.SshSyncChannel;
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
import ai.singlr.sail.sync.SyncDatabase;
import java.nio.file.Path;

/** The control-plane database and operations wiring shared by host commands and the server. */
public final class OperationsFactory {
  private OperationsFactory() {}

  public static SailOperations open() {
    return open(SailPaths.controlPlaneDb());
  }

  public static SailOperations open(Path path) {
    var database = SyncDatabase.converge(path, HostInfo.hostname());
    try {
      return create(database.db(), new ShellExecutor(false), SailPaths.PROJECT_DESCRIPTOR,
          null, null, SyncScheduler.disabled(), SessionYield.NONE).openedAtSchema(database.schemaBefore()).closeWith(database::close);
    } catch (RuntimeException e) {
      database.close();
      throw e;
    }
  }

  public static SailOperations open(ShellExec shell, String file, OperationHooks hooks,
      SessionYield sessionYield) {
    var database = SyncDatabase.converge(SailPaths.controlPlaneDb(), HostInfo.hostname());
    try {
      return create(database.db(), shell, file, hooks, sessionYield).openedAtSchema(database.schemaBefore()).closeWith(database::close);
    } catch (RuntimeException e) {
      database.close();
      throw e;
    }
  }

  public static SailOperations create(Sqlite db, ShellExec shell, String file,
      OperationHooks hooks, SessionYield sessionYield) {
    return new SailOperations(shell, file, ai.singlr.sail.engine.WatcherSpawner::spawnProcess,
        null, null, new SpecStore(db), new ReviewStore(db), new RunStore(db), new ProjectStore(db),
        ai.singlr.sail.engine.ConnectEnvironment::detect, SyncScheduler.disabled(), new FdeStore(db),
        sessionYield, hooks)
        .useMessages(new MessageStore(db)).useRooms(new RoomStore(db))
        .useBoxCredentials(new BoxCredentialStore(db)).useEvents(new EventStore(db))
        .useControlPlane(db, SailPaths.projectsDir(), new SyncOperations(db, HostInfo.hostname(),
            SailPaths.projectsDir(), NodeIdentity::config, SshSyncChannel::open));
  }

  public static SailOperations create(Sqlite db, ShellExec shell, String file,
      EventBus bus, EventSubscriber audit, SyncScheduler scheduler, SessionYield sessionYield) {
    return new SailOperations(shell, file, bus, audit, new SpecStore(db), new ReviewStore(db),
        new RunStore(db), new ProjectStore(db), scheduler, new FdeStore(db), sessionYield)
        .useMessages(new MessageStore(db))
        .useRooms(new RoomStore(db))
        .useBoxCredentials(new BoxCredentialStore(db))
        .useEvents(new EventStore(db))
        .useControlPlane(db, SailPaths.projectsDir(), new SyncOperations(db, HostInfo.hostname(),
            SailPaths.projectsDir(), NodeIdentity::config, SshSyncChannel::open));
  }
}
