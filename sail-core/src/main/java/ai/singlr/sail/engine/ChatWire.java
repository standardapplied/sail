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
 * An entry of a chat's log as the log keeps it and a client reads it: a {@link ChatItem} the
 * harness said, or one Sail itself logs ({@link Own}). Its kind is the snake case of its type's
 * name, which a client reads as its {@link #TYPE}, and each of its fields is there by its
 * snake-case name, read off the record as it is, so a field the vocabulary adds or reshapes is on
 * the wire with no code here. A record nested in an entry is a map of its fields the same way, with
 * its own {@link #TYPE} when it is one of a sealed set's alternatives (a call's detail); an enum is
 * its name in lower case. Read through the records' components, which the native image holds for
 * every type an entry is made of ({@code reflect-config.json}, held whole by {@code
 * ChatWireReflectionTest}).
 */
public final class ChatWire {

  /**
   * The key a client reads an item's type by, and a nested record's when it is one of a sealed
   * set's: never one of their fields' names ({@code kind} is a call's).
   */
  public static final String TYPE = "type";

  /** What Sail itself logs in a chat, beside what the harness said: no harness says these. */
  public sealed interface Own {}

  /**
   * What the person said, {@code text}; {@code queued} when it was said while a turn was open, so
   * it waits for the turn's end.
   */
  public record Message(String text, boolean queued) implements Own {}

  /** The person's message logged as {@code message}, which waited for a turn's end, went in. */
  public record Delivered(long message) implements Own {}

  /** What Sail tells the person of the chat, in its own {@code words}. */
  public record Told(String words) implements Own {}

  /**
   * The person {@code by} answered the agent's request {@code request} with {@code decision}: a
   * denial's own words ({@code message}, null for none), or a question's {@code answers} (null for
   * none). Logged before the agent is told, and never with the request's input or suggestions.
   */
  public record Answered(
      String request,
      LiveChat.Decision decision,
      String message,
      List<LiveChat.Answer> answers,
      String by)
      implements Own {
    public Answered {
      answers = answers == null || answers.isEmpty() ? null : List.copyOf(answers);
    }
  }

  /**
   * The person {@code by} put the chat in {@code mode}, asked of its process as {@code request},
   * or, with no process to ask (null), kept as the mode the next one starts in.
   */
  public record ModeSet(String mode, String request, String by) implements Own {}

  private ChatWire() {}

  /** The kind an entry of {@code type}, a {@link ChatItem} or one of Sail's {@link Own}, is. */
  public static String kind(Class<? extends Record> type) {
    return snake(type.getSimpleName());
  }

  /**
   * What {@code entry}, a {@link ChatItem} or one of Sail's {@link Own}, says: each of its fields
   * by its wire name; a null one left out.
   */
  public static Map<String, Object> fields(Record entry) {
    return fieldsOf(entry);
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
