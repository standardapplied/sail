/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.WatcherSpawner;

public record OperationHooks(
    DispatchOperations.EventSink events,
    WatcherSpawner watcher,
    DispatchOperations.Snapshotter snapshotter,
    DispatchOperations.AgentLauncher launcher,
    DispatchOperations.Listener dispatchListener,
    StopOperations.Listener stopListener) {}
