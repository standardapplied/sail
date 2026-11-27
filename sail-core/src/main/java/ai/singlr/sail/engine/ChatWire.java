/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link ChatItem} as a chat's log keeps it and a client reads it: its kind, the snake case of
 * its type's name, which a client reads as its {@link #TYPE}, and each of its fields by its
 * snake-case name, read off the record as it is, so a field the vocabulary adds or reshapes is on
 * the wire with no code here. A record nested in an item is a map of its fields the same way, with
 * its own {@link #TYPE} when it is one of a sealed set's alternatives (a call's detail); an enum is
 * its name in lower case. Read through the records' components, which the native image holds for
 * every type a chat item is made of ({@code reflect-config.json}, held whole by {@code
 * ChatWireTest}).
 */
public final class ChatWire {

  /**
   * The key a client reads an item's type by, and a nested record's when it is one of a sealed
   * set's: never one of their fields' names ({@code kind} is a call's).
   */
  public static final String TYPE = "type";

  private ChatWire() {}

  /** The kind {@code item} is logged as. */
  public static String kind(ChatItem item) {
    return snake(item.getClass().getSimpleName());
  }

  /** What {@code item} says: each of its fields by its wire name; a null one left out. */
  public static Map<String, Object> fields(ChatItem item) {
    return fieldsOf((Record) item);
  }

  private static Map<String, Object> fieldsOf(Record record) {
    var fields = new LinkedHashMap<String, Object>();
    for (var component : record.getClass().getRecordComponents()) {
      var value = encoded(valueOf(record, component));
      if (value != null) {
        fields.put(snake(component.getName()), value);
      }
    }
    return fields;
  }

  private static Object encoded(Object value) {
    return switch (value) {
      case null -> null;
      case Enum<?> named -> named.name().toLowerCase(Locale.ROOT);
      case List<?> list -> list.stream().map(ChatWire::encoded).toList();
      case Record record -> nested(record);
      case String text -> text;
      case Number number -> number;
      case Boolean bool -> bool;
      default -> value.toString();
    };
  }

  /** A record within an item: its fields, after its kind when it is one of a sealed set's. */
  private static Map<String, Object> nested(Record record) {
    var type = record.getClass();
    var sealed = Arrays.stream(type.getInterfaces()).anyMatch(Class::isSealed);
    var map = new LinkedHashMap<String, Object>();
    if (sealed) {
      map.put(TYPE, snake(type.getSimpleName()));
    }
    map.putAll(fieldsOf(record));
    return map;
  }

  private static Object valueOf(Record record, RecordComponent component) {
    try {
      return component.getAccessor().invoke(record);
    } catch (ReflectiveOperationException unread) {
      throw new IllegalStateException(
          "A chat item's " + component.getName() + " could not be read.", unread);
    }
  }

  private static String snake(String name) {
    var snake = new StringBuilder();
    for (var index = 0; index < name.length(); index++) {
      var ch = name.charAt(index);
      if (Character.isUpperCase(ch) && index > 0) {
        snake.append('_');
      }
      snake.append(Character.toLowerCase(ch));
    }
    return snake.toString();
  }
}
