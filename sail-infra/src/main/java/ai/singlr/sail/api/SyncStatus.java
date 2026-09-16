/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.sync.SyncEngine;

public record SyncStatus(String role, String main, SyncEngine.Report lastReport) {}
