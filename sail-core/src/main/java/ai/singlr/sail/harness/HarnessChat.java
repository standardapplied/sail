/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.ChatItem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A harness a person chats with ({@link Harness#chat}): one process that stays up, takes each of a
 * person's messages as a JSON line on its stdin, asks that person before what its mode does not
 * allow, and writes what it does as JSON lines on its stdout; and what of its own names a person is
 * shown in a chat's items, in words no client has to know the harness to read.
 */
public interface HarnessChat {

  /**
   * One chat's launch: the chat mode it starts in, by name ({@link #modes}), an optional model and
   * the recorded conversation to resume ({@code null} for a fresh one). A resumed chat starts in
   * the mode it was last in, as its harness last said, not the one it first started in.
   */
  record Launch(String mode, String model, String resumeSessionId) {

    private static final Pattern SAFE_MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:\\[\\]-]*");

    /**
     * @throws IllegalArgumentException for a blank mode, a model that is not a model's name (one
     *     starting with a dash would be read as an option), or a session id that is not {@link
     *     Harness#requireSafeSessionId safe}
     */
    public Launch {
      Strings.requireNonBlank(mode, "mode");
      if (model != null && !SAFE_MODEL.matcher(model).matches()) {
        throw new IllegalArgumentException(
            "A chat's model is a model's name, such as 'sonnet' or 'claude-sonnet-5-5'.");
      }
      if (resumeSessionId != null) {
        Harness.requireSafeSessionId(resumeSessionId);
      }
    }

    /** The recorded conversation this launch resumes, or empty for a fresh one. */
    public Optional<String> resume() {
      return Optional.ofNullable(resumeSessionId);
    }
  }

  /**
   * The argv of a chat, to be run as a list of arguments, never through a shell: a model's name may
   * hold {@code [} and {@code ]}, which a shell reads as a pattern.
   *
   * @throws IllegalArgumentException naming the chat modes it takes, for a mode it does not
   */
  List<String> argv(Launch launch);

  /**
   * The modes a chat runs in, each with what a person is shown for it ({@code label}) and what it
   * does ({@code about}), the first the one a chat starts in until a person chooses. A mode's
   * {@code name} is the harness's own, as it says which mode it is in.
   */
  List<Harness.Mode> modes();

  /** What a call of the harness's tool {@code tool} does, as a person reads its row. */
  ChatItem.ToolKind kind(String tool);

  /** What a call of {@code tool} with {@code input} works on, clipped. */
  ChatItem.Detail detail(String tool, Map<String, Object> input);

  /**
   * What allowing a call always would change, one short sentence for each of the harness's own
   * {@code suggestions}, in their order: what is allowed from now on, where, and any change of mode
   * it brings. One the harness names that is not known here still has its sentence.
   */
  List<String> always(List<Object> suggestions);
}
