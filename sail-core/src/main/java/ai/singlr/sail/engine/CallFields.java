/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a person is shown everything a call of Claude Code's holds: allowing a call hands its
 * whole input back, so a card draws a call by its kind (a command, an edit, a plan, a question)
 * only when the input holds nothing but the fields that card draws and the fields that only
 * describe the call, each of its recorded type. Any other field, or one of another type, and the
 * call is drawn whole, as its input's text.
 */
public final class CallFields {

  /**
   * The fields a card does not draw that only describe a call, each recorded: Bash's {@code
   * description} (02b:16), which a card shows as the call's words, and ExitPlanMode's {@code
   * planFilePath} (03b:28), the file Claude Code kept the plan in.
   */
  private static final Map<String, Map<String, Class<?>>> DESCRIBING =
      Map.of(
          "Bash", Map.of("description", String.class),
          "ExitPlanMode", Map.of("planFilePath", String.class));

  /** A plan to approve, as 03b:28 recorded it. */
  private static final Map<String, Class<?>> PLAN = Map.of("plan", String.class);

  /** Questions, one asked, one option and one question's fields, as 03a:21 recorded them. */
  private static final Map<String, Class<?>> QUESTIONS = Map.of("questions", List.class);

  private static final Map<String, Class<?>> QUESTION =
      Map.of(
          "question", String.class,
          "header", String.class,
          "options", List.class,
          "multiSelect", Boolean.class);

  private static final Map<String, Class<?>> OPTION =
      Map.of("label", String.class, "description", String.class);

  private CallFields() {}

  /**
   * Whether {@code input}, of a call of {@code tool}, holds nothing but the fields {@code drawn}
   * and those that only describe a call of it, each of its type.
   */
  public static boolean drawnWhole(
      String tool, Map<String, Object> input, Map<String, Class<?>> drawn) {
    var fields = new HashMap<>(drawn);
    fields.putAll(DESCRIBING.getOrDefault(tool, Map.of()));
    return only(input, fields);
  }

  /** Whether an ExitPlanMode's {@code input} is a plan its card draws whole. */
  static boolean plan(Map<String, Object> input) {
    return drawnWhole("ExitPlanMode", input, PLAN);
  }

  /** Whether an AskUserQuestion's {@code input} is questions their card draws whole. */
  static boolean questions(Map<String, Object> input) {
    return only(input, QUESTIONS)
        && listed(input.get("questions")).stream()
            .allMatch(
                question ->
                    only(question, QUESTION)
                        && listed(((Map<?, ?>) question).get("options")).stream()
                            .allMatch(option -> only(option, OPTION)));
  }

  private static boolean only(Object value, Map<String, Class<?>> fields) {
    return value instanceof Map<?, ?> map
        && map.entrySet().stream()
            .allMatch(
                field ->
                    fields.containsKey(field.getKey())
                        && fields.get(field.getKey()).isInstance(field.getValue()));
  }

  private static List<?> listed(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }
}
