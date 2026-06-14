/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guard test: every record class in {@code ai.singlr.sail.api} that is reachable from {@link
 * ApiJson#encode} as a response value must implement {@link Mappable}. Without this guard, a future
 * contributor could add a new response record, exercise it only on the JVM (where it works via the
 * fail-fast {@code IllegalArgumentException} on the native binary that would surface as a runtime
 * crash), and ship a broken native build.
 *
 * <p>Records explicitly excluded from the requirement: request payloads ({@code *Request}) — they
 * are only ever parsed, never serialized — and the sealed {@code Result} type with its {@code
 * Success}/{@code Failure} variants, which are unwrapped by {@link ApiResponse#from} before
 * encoding.
 */
class ApiJsonMappableGuardTest {

  private static final String API_PACKAGE = "ai.singlr.sail.api";

  private static final Set<String> EXCLUDED =
      Set.of(
          "ai.singlr.sail.api.Result",
          "ai.singlr.sail.api.Result$Success",
          "ai.singlr.sail.api.Result$Failure",
          "ai.singlr.sail.api.DispatchRequest",
          "ai.singlr.sail.api.ApiResponse",
          "ai.singlr.sail.api.Event",
          "ai.singlr.sail.api.EventBus$Stats",
          "ai.singlr.sail.api.EventBus$SubscriberStats",
          "ai.singlr.sail.api.WebhookMessage");

  @Test
  void everyResponseRecordImplementsMappable() throws Exception {
    var records = recordsInApiPackage();
    var violators = new ArrayList<String>();
    for (var clazz : records) {
      if (EXCLUDED.contains(clazz.getName())) {
        continue;
      }
      if (!Mappable.class.isAssignableFrom(clazz)) {
        violators.add(clazz.getName());
      }
    }
    assertTrue(
        violators.isEmpty(),
        () ->
            "These API records must implement Mappable (or be added to the excluded set if they"
                + " are request payloads that never flow through ApiJson.encode):\n  "
                + String.join("\n  ", violators));
  }

  @Test
  void mappableImplementationsRoundTripThroughEncode() {
    // Sanity: a representative response record encodes without throwing.
    var resp = new HealthResponse("ok");
    var encoded = ApiJson.encode(resp);
    assertNotNull(encoded);
    assertEquals("ok", ((java.util.Map<?, ?>) encoded).get("status"));
  }

  @Test
  void leafMappablesEncodeWithExpectedFieldNames() {
    var sub = ApiJson.encode(new SubscriberStatsView("audit", 64, 3, 7L));
    assertTrue(sub instanceof java.util.Map<?, ?>);
    var subMap = (java.util.Map<?, ?>) sub;
    assertEquals("audit", subMap.get("name"));
    assertEquals(64, subMap.get("capacity"));
    assertEquals(3, subMap.get("depth"));
    assertEquals(7L, subMap.get("dropped"));

    var fe = ApiJson.encode(FieldError.of("project", "is required"));
    assertTrue(fe instanceof java.util.Map<?, ?>);
    var feMap = (java.util.Map<?, ?>) fe;
    assertEquals("project", feMap.get("field"));
    assertEquals("is required", feMap.get("message"));
  }

  @Test
  void nonMappableRecordHitsFailFast() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> ApiJson.encode(new NonMappableSample("x")));
    assertTrue(
        ex.getMessage().contains("Mappable"),
        () -> "expected the message to point at Mappable, got: " + ex.getMessage());
  }

  /** Non-API record used only by {@link #nonMappableRecordHitsFailFast}. */
  private record NonMappableSample(String value) {}

  private static List<Class<?>> recordsInApiPackage() throws Exception {
    var resource = API_PACKAGE.replace('.', '/');
    var url = ApiJsonMappableGuardTest.class.getClassLoader().getResource(resource);
    assertNotNull(url, "ai.singlr.sail.api package not found on the test classpath");
    var dir = Path.of(URI.create(url.toString().replaceFirst("^file:", "file:")));
    return walkRecords(dir, API_PACKAGE);
  }

  private static List<Class<?>> walkRecords(Path dir, String pkg) throws Exception {
    var classes = new ArrayList<Class<?>>();
    try (Stream<Path> stream = Files.walk(dir)) {
      var paths = stream.filter(p -> p.toString().endsWith(".class")).toList();
      for (var path : paths) {
        var relative = dir.relativize(path).toString();
        var name = pkg + "." + relative.replace(File.separatorChar, '.');
        name = name.substring(0, name.length() - ".class".length());
        try {
          var clazz = Class.forName(name);
          // Skip test-only inner records: they're inner classes of *Test fixtures and have
          // 'Test$' in their FQN. This keeps the guard focused on production API records.
          if (clazz.isRecord() && !clazz.getName().contains("Test$")) {
            classes.add(clazz);
          }
        } catch (NoClassDefFoundError | ClassNotFoundException ignored) {
          // Skip classes we cannot load (e.g., synthetic test helpers).
        }
      }
    }
    return classes;
  }
}
