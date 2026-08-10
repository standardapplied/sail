/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Renders the newest conversation entries within a deterministic prompt budget, and reports exactly
 * which entries made it in whole. Delivery derives from presentation: a caller that acknowledges
 * rendered messages as delivered must acknowledge only {@link Rendered#fullyRendered} — the
 * truncated boundary entry and the omitted tail were not presented, so they stay owed a full
 * delivery through the relay or the stop gate.
 */
public final class PromptConversation {

  public static final int MAX_CODE_POINTS = 32_000;

  private PromptConversation() {}

  /** The rendered conversation block and the entries it contains in full, oldest first. */
  public record Rendered<T>(String text, List<T> fullyRendered) {}

  public static <T> Rendered<T> renderNewest(List<T> messages, Function<T, String> renderer) {
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(renderer, "renderer");
    var remaining = MAX_CODE_POINTS;
    var rendered = new ArrayDeque<String>();
    var whole = new ArrayDeque<T>();
    for (var index = messages.size() - 1; index >= 0 && remaining > 0; index--) {
      var message = messages.get(index);
      var text = Objects.requireNonNull(renderer.apply(message), "rendered message");
      var codePoints = text.codePointCount(0, text.length());
      if (codePoints <= remaining) {
        rendered.addFirst(text);
        whole.addFirst(message);
        remaining -= codePoints;
      } else {
        rendered.addFirst(text.substring(0, text.offsetByCodePoints(0, remaining)));
        remaining = 0;
      }
    }
    return new Rendered<>(String.join("", rendered), List.copyOf(whole));
  }
}
