/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatWire;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A harness a person chats with ({@link Harness#chat}): how a process of it is started for a chat
 * ({@link #argv}) and spoken with ({@link #wire}), the modes a person may choose, and what of its
 * own names a person is shown in a chat's items, in words no client has to know the harness to
 * read. It is handed a person's messages a turn at a time, asks that person before what its mode
 * does not allow, and says what it does; how it is written to and read, and where a turn begins and
 * ends, are its wire's alone, so a host knows nothing of them.
 */
public interface HarnessChat {

  /**
   * A request the harness asked, as its wire answers it: the line that tells the harness the
   * person's {@code reply}, held to what was asked.
   */
  @FunctionalInterface
  interface Asked {

    /**
     * @throws IllegalArgumentException for a reply the request does not take, which leaves it asked
     */
    String answer(ChatWire.Reply reply);
  }

  /** The conversation a harness named ({@code conversation}) and the mode it said it is in. */
  record Said(String conversation, String mode) {}

  /**
   * What one line a harness wrote says: what a person is shown of it ({@code items}); how a request
   * among them is answered ({@code asked}, null when none asks); the conversation and mode it named
   * ({@code said}, null when it named neither); whether it began a turn ({@code began}); whether
   * the open turn is to be stopped ({@code stop}), since it waits on what no answer reaches; and
   * what is written back to it at once ({@code replies}), in order.
   */
  record Heard(
      List<ChatItem> items,
      Asked asked,
      Said said,
      boolean began,
      boolean stop,
      List<String> replies) {

    /**
     * @throws IllegalArgumentException for a request heard with no way to answer it
     */
    public Heard {
      items = List.copyOf(items);
      replies = List.copyOf(replies);
      if (asked == null && items.stream().anyMatch(ChatItem.Request.class::isInstance)) {
        throw new IllegalArgumentException("A request is heard with how it is answered.");
      }
    }

    /** A line that says {@code items} and asks nothing else of its host. */
    public static Heard of(List<ChatItem> items) {
      return new Heard(items, null, null, false, false, List.of());
    }
  }

  /**
   * One process's side of a chat's pipes, the only way a host speaks to the harness: the lines
   * written before any other, what each line it writes says, and the lines that hand it a message,
   * stop its turn, change its mode and, through {@link Asked}, answer what it asked. A host writes
   * each line whole, in the order it is given them, and calls a wire under its process's guard, one
   * call at a time, so a wire keeps what its exchange needs between lines (an id it sent, a message
   * held until its harness is ready) with no lock of its own.
   */
  interface Wire {

    /** The lines written before any other: none for a harness that needs no opening. */
    List<String> opening();

    /** What {@code line}, one the harness wrote, says. */
    Heard read(String line);

    /**
     * The lines that hand the harness a person's {@code text} as a turn: none while it is not yet
     * ready for one, the line that makes it ready then writing them ({@link Heard#replies}).
     */
    List<String> message(String text);

    /** The line that stops the turn in progress, named {@code id} where the harness names one. */
    String interrupt(String id);

    /** The line that puts the process in {@code mode} between turns, asked as {@code id}. */
    String mode(String id, String mode);
  }

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

  /** The wire of a process started with {@code launch} ({@link #argv}), made for it alone. */
  Wire wire(Launch launch);

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
