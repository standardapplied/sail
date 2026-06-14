/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliJsonTest {

  @Test
  void serializesRecordFieldsAsSnakeCase() {
    var json = CliJson.stringify(new ExampleResponse("demo", "spec-a", true, List.of("one")));

    assertEquals(
        "{\"project_name\": \"demo\", \"spec_id\": \"spec-a\", \"dry_run\": true, \"lines\": [\"one\"]}",
        json);
  }

  @Test
  void omitsNullRecordFields() {
    var json = CliJson.stringify(new OptionalResponse("demo", null));

    assertEquals("{\"name\": \"demo\"}", json);
  }

  @Test
  void escapesStrings() {
    var json = CliJson.stringify(new OptionalResponse("demo\n\"quoted\"", "tab\tvalue"));

    assertEquals("{\"name\": \"demo\\n\\\"quoted\\\"\", \"value\": \"tab\\tvalue\"}", json);
  }

  @Test
  void serializesPrimitiveTopLevelValues() {
    assertEquals("null", CliJson.stringify(null));
    assertEquals("42", CliJson.stringify(42));
    assertEquals("false", CliJson.stringify(false));
    assertEquals("\"ready\"", CliJson.stringify(State.READY));
  }

  @Test
  void serializesMapsAndSkipsNullValues() {
    var values = new LinkedHashMap<String, Object>();
    values.put("name", "demo");
    values.put("missing", null);
    values.put("count", 2);

    assertEquals("{\"name\": \"demo\", \"count\": 2}", CliJson.stringify(values));
  }

  @Test
  void serializesEmptyListsAndMaps() {
    assertEquals("[]", CliJson.stringify(List.of()));
    assertEquals("{}", CliJson.stringify(new LinkedHashMap<>()));
  }

  @Test
  void fallsBackToStringForUnknownObjects() {
    var value =
        new Object() {
          @Override
          public String toString() {
            return "custom";
          }
        };

    assertEquals("\"custom\"", CliJson.stringify(value));
  }

  @Test
  void escapesAllJsonControlCharacters() {
    var json = CliJson.stringify("backspace\b formfeed\f carriage\r control\u0001 slash\\");

    assertEquals("\"backspace\\b formfeed\\f carriage\\r control\\u0001 slash\\\\\"", json);
  }

  private enum State {
    READY
  }

  private record ExampleResponse(
      String projectName, String specId, boolean dryRun, List<String> lines) {}

  private record OptionalResponse(String name, String value) {}
}
