/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Every record a chat item is made of, each alternative of each sealed set in it included, is in
 * the native image's reflection configuration: {@link ChatWire} reads them through their
 * components, which a native image holds only for a record listed there.
 */
class ChatWireReflectionTest {

  private static final Path CONFIG =
      Path.of("src/main/resources/META-INF/native-image/ai.singlr/sail-cli/reflect-config.json");
  private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

  @Test
  void everyRecordAChatItemIsMadeOfIsInTheReflectionConfiguration() throws IOException {
    var records = new TreeSet<String>();
    reach(ChatItem.class, records, new TreeSet<>());

    records.removeAll(
        NAME.matcher(Files.readString(CONFIG))
            .results()
            .map(match -> match.group(1))
            .collect(Collectors.toSet()));

    assertEquals(
        Set.of(),
        records,
        "add each to " + CONFIG + " with allDeclaredMethods and allRecordComponents");
  }

  private static void reach(Type type, Set<String> records, Set<String> seen) {
    switch (type) {
      case ParameterizedType parameterized -> {
        for (var argument : parameterized.getActualTypeArguments()) {
          reach(argument, records, seen);
        }
      }
      case Class<?> sealed when sealed.isSealed() && seen.add(sealed.getName()) -> {
        for (var permitted : sealed.getPermittedSubclasses()) {
          reach(permitted, records, seen);
        }
      }
      case Class<?> record when record.isRecord() && records.add(record.getName()) -> {
        for (var component : record.getRecordComponents()) {
          reach(component.getGenericType(), records, seen);
        }
      }
      default -> {}
    }
  }
}
