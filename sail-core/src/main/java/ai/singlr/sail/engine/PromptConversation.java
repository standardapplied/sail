/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Renders the newest conversation entries within a deterministic prompt budget. */
public final class PromptConversation {

  public static final int MAX_CODE_POINTS = 32_000;

  private PromptConversation() {}

  public static <T> String renderNewest(List<T> messages, Function<T, String> renderer) {
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(renderer, "renderer");
    var remaining = MAX_CODE_POINTS;
    var rendered = new ArrayDeque<String>();
    for (var index = messages.size() - 1; index >= 0 && remaining > 0; index--) {
      var message = Objects.requireNonNull(renderer.apply(messages.get(index)), "rendered message");
      var codePoints = message.codePointCount(0, message.length());
      if (codePoints <= remaining) {
        rendered.addFirst(message);
        remaining -= codePoints;
      } else {
        rendered.addFirst(message.substring(0, message.offsetByCodePoints(0, remaining)));
        remaining = 0;
      }
    }
    return String.join("", rendered);
  }
}
