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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Every record a chat's log entry is made of, an item's or Sail's own, each alternative of each
 * sealed set in it included, is in the native image's reflection configuration, and no other of
 * theirs: {@link ChatWire} reads them through their components, which a native image holds only for
 * a record listed there.
 */
class ChatWireReflectionTest {

  private static final Path CONFIG =
      Path.of("src/main/resources/META-INF/native-image/ai.singlr/sail-cli/reflect-config.json");
  private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

  @Test
  void everyRecordAChatEntryIsMadeOfIsInTheReflectionConfiguration() throws IOException {
    var records = new TreeSet<String>();
    var seen = new TreeSet<String>();
    reach(ChatItem.class, records, seen);
    reach(ChatWire.Own.class, records, seen);
    var listed =
        NAME.matcher(Files.readString(CONFIG))
            .results()
            .map(match -> match.group(1))
            .collect(Collectors.toCollection(TreeSet::new));

    var missing = new TreeSet<>(records);
    missing.removeAll(listed);
    var stale =
        listed.stream()
            .filter(
                name ->
                    name.startsWith(ChatItem.class.getName() + "$")
                        || name.startsWith(ChatWire.class.getName() + "$"))
            .filter(name -> !records.contains(name))
            .toList();

    assertEquals(
        Set.of(),
        missing,
        "add each to " + CONFIG + " with allDeclaredMethods and allRecordComponents");
    assertEquals(List.of(), stale, "no record a chat entry is no longer made of is listed");
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
