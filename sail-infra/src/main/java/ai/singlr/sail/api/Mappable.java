/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.Map;

/**
 * Marker for values that {@link ApiJson} can serialize. Each implementor returns a {@link
 * java.util.LinkedHashMap} of its public fields keyed by their wire names (snake_case). Null values
 * may be included — {@code ApiJson} strips them at encode time so each record's {@code toMap} stays
 * small and field-order-driven.
 *
 * <p>Why not reflection: {@code Class.getRecordComponents()} requires reflection metadata that
 * GraalVM native-image does not ship by default. Avoiding the reflection call eliminates the whole
 * class of {@code UnsupportedFeatureError: Record components not available} crashes that surface
 * only in the native binary, never on the JVM. Adding {@code toMap} per record is a few lines of
 * compile-time-safe boilerplate that the compiler enforces — far cheaper than a hand-maintained
 * {@code reflect-config.json}.
 */
public interface Mappable {

  /** Returns a map view of this value for JSON serialization. */
  Map<String, Object> toMap();
}
