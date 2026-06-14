/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiJsonTest {

  @Test
  void schemaWrapsScalarValuesAsData() {
    var json = ApiJson.withSchema("ok");

    assertEquals(1, json.get("schema_version"));
    assertEquals("ok", json.get("data"));
  }

  @Test
  void encodesPrimitiveNullEnumListAndMapValues() {
    var value =
        Map.of(
            "text",
            "ok",
            "number",
            7,
            "flag",
            true,
            "error",
            ErrorCode.NOT_FOUND,
            "list",
            List.of("one", ErrorCode.CONFLICT));

    var encoded = ApiJson.encode(value);

    assertTrue(encoded instanceof Map<?, ?>);
    var map = (Map<?, ?>) encoded;
    assertEquals("ok", map.get("text"));
    assertEquals(7, map.get("number"));
    assertEquals(true, map.get("flag"));
    assertEquals("not_found", map.get("error"));
    assertEquals(List.of("one", "conflict"), map.get("list"));
    assertNull(ApiJson.encode(null));
  }

  @Test
  void omitsNullMapAndRecordValues() {
    var source = new java.util.LinkedHashMap<String, Object>();
    source.put("keep", "yes");
    source.put("skip", null);
    var encodedMap = ApiJson.encode(source);
    var record = new SampleMappable("hello", null, ErrorCode.INTERNAL);
    var encodedRecord = ApiJson.encode(record);

    assertEquals(Map.of("keep", "yes"), encodedMap);
    assertTrue(encodedRecord instanceof Map<?, ?>);
    var map = (Map<?, ?>) encodedRecord;
    assertEquals("hello", map.get("camel_case"));
    assertEquals("internal", map.get("error_code"));
    assertFalse(map.containsKey("missing_value"), "null values from toMap() must be omitted");
  }

  @Test
  void fallsBackToStringForPlainObjects() {
    var value = new StringBuilder("plain");

    assertEquals("plain", ApiJson.encode(value));
  }

  @Test
  void failsFastOnRecordWithoutMappable() {
    var record = new NotMappable("value");

    var thrown = assertThrows(IllegalArgumentException.class, () -> ApiJson.encode(record));

    assertTrue(
        thrown.getMessage().contains("Mappable"),
        "fail-fast message should name the missing interface: " + thrown.getMessage());
  }

  /** Mappable record whose toMap() returns a null-bearing map; used to verify null-omission. */
  private record SampleMappable(String camelCase, String missingValue, ErrorCode errorCode)
      implements Mappable {
    @Override
    public Map<String, Object> toMap() {
      var m = new java.util.LinkedHashMap<String, Object>();
      m.put("camel_case", camelCase);
      m.put("missing_value", missingValue);
      m.put("error_code", errorCode);
      return m;
    }
  }

  /** Non-Mappable record used to verify the fail-fast path. */
  private record NotMappable(String value) {}
}
