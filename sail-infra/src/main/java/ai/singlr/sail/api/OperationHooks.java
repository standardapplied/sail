/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.engine.Workspaces;

/** What differs between the lanes a box runs operations on, each built whole at its door. */
public record OperationHooks(
    DispatchOperations.EventSink events,
    WatcherSpawner watcher,
    Workspaces workspaces,
    DispatchOperations.AgentLauncher launcher,
    DispatchOperations.Listener dispatchListener,
    StopOperations.Listener stopListener) {}
