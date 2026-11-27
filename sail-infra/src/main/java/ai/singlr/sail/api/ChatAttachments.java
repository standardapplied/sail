/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.pty.FileScripts;
import ai.singlr.sail.store.ChatStore;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * The images a person's message attaches: paths the files door wrote in their workspace, each read
 * as the dev user by the files door's own rules ({@link FileScripts#IMAGE_COMMAND}): a file within
 * the workspace, a link's target included, of a type the model reads and at most {@link
 * #IMAGE_BYTES}. A path reaches the workspace on the read's input, never a command line.
 */
final class ChatAttachments {

  /** The most files a message attaches. */
  static final int MOST = 8;

  /** The largest image a message attaches: what Claude takes, 5 MiB once in base64. */
  static final long IMAGE_BYTES = 3_932_160;

  private static final Duration READ = Duration.ofSeconds(30);
  private static final List<String> COMMAND =
      Stream.concat(
              FileScripts.IMAGE_COMMAND.stream(),
              Stream.of(ChatLaunch.WORKSPACE, Long.toString(IMAGE_BYTES)))
          .toList();
  private static final Map<String, String> TYPES =
      Map.of(
          "png", "image/png",
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "gif", "image/gif",
          "webp", "image/webp");

  private final ShellExec shell;

  ChatAttachments(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * Refuses more attachments than a message takes, before anything is read or logged.
   *
   * @throws IllegalArgumentException when there are more than {@link #MOST}
   */
  static void requireFew(List<String> attachments) {
    if (attachments.size() > MOST) {
      throw new IllegalArgumentException("A message attaches at most " + MOST + " files.");
    }
  }

  /** The images {@code attachments} names in {@code chat}'s workspace, read now. */
  List<LiveStream.Image> images(ChatStore.Chat chat, List<String> attachments) {
    var instance = chat.project() + "-" + chat.workspace();
    return attachments.stream().map(path -> image(instance, chat.workspace(), path)).toList();
  }

  private LiveStream.Image image(String instance, String workspace, String path) {
    if (Strings.isBlank(path) || path.indexOf('\0') >= 0 || path.length() > 4096) {
      throw new IllegalArgumentException("An attachment is a path in the workspace.");
    }
    var type = TYPES.get(path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
    if (type == null) {
      throw new IllegalArgumentException(
          "Attachment " + path + " is not an image: a chat attaches png, jpeg, gif or webp.");
    }
    ShellExec.Result read;
    try {
      read =
          shell.execWithInput(
              ContainerExec.asDevUser(instance, ChatLaunch.WORKSPACE, Map.of(), COMMAND),
              path + "\0",
              READ);
    } catch (IOException | TimeoutException | InterruptedException failed) {
      if (failed instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ApiException(
          ErrorCode.CONTAINER_ERROR,
          "Attachment " + path + " could not be read in workspace " + workspace + ".",
          failed);
    }
    if (!read.ok()) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST, unreadable(path, workspace, read.exitCode()));
    }
    return new LiveStream.Image(type, read.stdout().strip());
  }

  private static String unreadable(String path, String workspace, int exit) {
    return switch (exit) {
      case FileScripts.OUTSIDE_HOME ->
          "Attachment " + path + " is outside workspace " + workspace + ".";
      case FileScripts.TOO_LARGE ->
          "Attachment " + path + " is larger than " + IMAGE_BYTES / 1024 + " KiB.";
      default ->
          "Attachment " + path + " is not a file Sail may read in workspace " + workspace + ".";
    };
  }
}
