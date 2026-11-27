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
 * <p>A route's own answer is a few records, each a few lines of {@code toMap} the compiler checks.
 * A family of many records said one way, as a chat's items are ({@code ChatWire}), is read through
 * its record components instead: {@code Class.getRecordComponents()} needs reflection metadata
 * GraalVM native-image does not ship by default, so each such record is listed in {@code
 * reflect-config.json}, and a test holds that list whole ({@code ChatWireReflectionTest}).
 */
public interface Mappable {

  /** Returns a map view of this value for JSON serialization. */
  Map<String, Object> toMap();
}
