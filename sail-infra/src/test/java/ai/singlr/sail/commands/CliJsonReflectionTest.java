/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Every record {@link CliJson} prints has its entry in the native image's reflection configuration:
 * {@code CliJson} reads record components reflectively, and an unregistered record dies only in the
 * native binary. The records are read from the JSON probe's values and from their components'
 * declared types, so a component that is null in the probe is still held to it.
 */
class CliJsonReflectionTest {

  private static final Path CONFIG =
      Path.of("src/main/resources/META-INF/native-image/ai.singlr/sail-cli/reflect-config.json");
  private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

  private record Listed(String name, Optional<Unlisted> inner) {}

  private record Unlisted(List<Nested> nested) {}

  private record Nested(int value) {}

  @Test
  void everyRecordTheJsonProbePrintsIsInTheReflectionConfiguration() throws IOException {
    var unregistered = unregistered(PtySelfTestCommand.jsonProbeValues(), registered());

    assertEquals(
        Set.of(),
        unregistered,
        "add each to " + CONFIG + " with allDeclaredMethods and allRecordComponents");
  }

  @Test
  void aRecordReachedOnlyThroughAComponentsTypeIsFoundUnregistered() {
    var names = Set.of(Listed.class.getName());

    assertEquals(
        Set.of(Unlisted.class.getName(), Nested.class.getName()),
        unregistered(List.of(new Listed("probe", Optional.empty())), names));
  }

  private static Set<String> registered() throws IOException {
    return NAME.matcher(Files.readString(CONFIG))
        .results()
        .map(match -> match.group(1))
        .collect(Collectors.toSet());
  }

  private static Set<String> unregistered(List<Object> values, Set<String> names) {
    var records = new TreeSet<String>();
    values.forEach(value -> reach(value.getClass(), records));
    records.removeAll(names);
    return records;
  }

  private static void reach(Type type, Set<String> records) {
    switch (type) {
      case ParameterizedType parameterized -> {
        for (var argument : parameterized.getActualTypeArguments()) {
          reach(argument, records);
        }
      }
      case Class<?> recordClass
          when recordClass.isRecord() && records.add(recordClass.getName()) -> {
        for (var component : recordClass.getRecordComponents()) {
          reach(component.getGenericType(), records);
        }
      }
      default -> {}
    }
  }
}
