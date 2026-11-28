/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

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
   * One chat's launch: the mode it starts in, by the harness's own name, an optional model and the
   * recorded conversation to resume ({@code null} for a fresh one). A fresh chat starts in one of
   * the chat {@link #modes}, the one a person chose; a resumed chat starts in the mode its harness
   * last said it was in, not the one it first started in, even one that is no chat mode (a
   * workspace's settings may set another default): it is the harness's own word, so it is passed on
   * as it is, once it is known to be a mode's name and no option.
   */
  record Launch(String mode, String model, String resumeSessionId) {

    private static final Pattern SAFE_MODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    private static final Pattern SAFE_MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:\\[\\]-]*");

    /**
     * @throws IllegalArgumentException for a mode that is not a mode's name, a model that is not a
     *     model's name (one starting with a dash would be read as an option), or a session id that
     *     is not {@link Harness#requireSafeSessionId safe}
     */
    public Launch {
      if (mode == null || !SAFE_MODE.matcher(mode).matches()) {
        throw new IllegalArgumentException(
            "A chat's mode is a mode's name, such as 'default' or 'acceptEdits'.");
      }
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
   */
  List<String> argv(Launch launch);

  /**
   * The modes a person may choose for a chat, each with what a person is shown for it ({@code
   * label}) and what it does ({@code about}), the first the one a chat starts in until a person
   * chooses. A mode's {@code name} is the harness's own, as it says which mode it is in. A host
   * takes a person's choice only from these ({@link Harness.Mode#named}).
   */
  List<Harness.Mode> modes();

  /**
   * Whether a chat may use the sign-in a person made inside their workspace ({@link
   * Harness#signIn}), which Sail never holds, copies or relays; a stored key is always taken.
   */
  boolean signsInInside();

  /** What a call of the harness's tool {@code tool} does, as a person reads its row. */
  ChatItem.ToolKind kind(String tool);

  /** What a call of {@code tool} with {@code input} works on. */
  ChatItem.Detail detail(String tool, Map<String, Object> input);

  /**
   * The ways to allow the call of {@code tool} with {@code input} always that the harness's own
   * {@code suggestions} offer, each said whole in plain words: what is allowed or changed, and
   * where. None when any suggestion is a shape that cannot be said so, since every suggestion is
   * kept together, or when the call is one the harness was recorded asking for again whatever is
   * kept: a person is never offered more than holds. The one decision for a request's card and for
   * the answer that takes it.
   */
  List<ChatItem.Always> always(String tool, Map<String, Object> input, List<Object> suggestions);

  /**
   * Why a person asking to allow always the call of {@code tool} with {@code input}, for which
   * {@link #always} offers none, is refused, in words a person reads: what the harness offered, and
   * why Sail withholds it when it did.
   */
  String noAlways(String tool, Map<String, Object> input, List<Object> suggestions);
}
