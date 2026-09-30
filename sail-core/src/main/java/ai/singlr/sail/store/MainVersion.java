/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.Map;

/**
 * One version of an entity as main holds it: its comparable state ({@code null} for a deletion),
 * the rev main holds it at, and the author main recorded for that rev. What a parked conflict keeps
 * of main's side, so a resolve takes main's version exactly as main minted it.
 */
public record MainVersion(Map<String, Object> snapshot, String rev, String author) {}
