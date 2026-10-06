/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Strings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/**
 * A harness's file of hooks, as data: where it lives inside the container, the entries written
 * before {@code hooks} in their order, and which of the harness's events run which of sail's {@link
 * SailHook hooks}. A file whose groups do not, between them, name every {@link SailHook#required
 * required} hook cannot be built, nor one whose path is not absolute inside a directory.
 *
 * @param path the container-side absolute path of the file
 * @param settings the top-level entries written before {@code hooks}, in order
 * @param groups the event groups, in the order they are written
 */
public record HookFile(String path, SequencedMap<String, Object> settings, List<Group> groups) {

  /**
   * One group of hooks a harness runs at {@code event}, for the tools {@code matcher} selects or
   * for every tool when {@code matcher} is {@code null}.
   */
  public record Group(String event, String matcher, List<SailHook> hooks) {

    public Group {
      Strings.requireNonBlank(event, "event");
      hooks = List.copyOf(hooks);
      if (hooks.isEmpty()) {
        throw new IllegalArgumentException("Hook group for " + event + " runs nothing.");
      }
    }
  }

  public HookFile {
    Strings.requireNonBlank(path, "path");
    if (!path.startsWith("/")
        || path.endsWith("/")
        || path.contains("//")
        || path.lastIndexOf('/') == 0) {
      throw new IllegalArgumentException(
          "Hook file path " + path + " is not an absolute path inside a directory.");
    }
    settings = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(settings));
    groups = List.copyOf(groups);
    requireEveryRequiredHook(path, groups);
  }

  /** The directory the file lives in. */
  public String directory() {
    return path.substring(0, path.lastIndexOf('/'));
  }

  private static void requireEveryRequiredHook(String path, List<Group> groups) {
    var declared =
        groups.stream().flatMap(group -> group.hooks().stream()).collect(Collectors.toSet());
    SailHook.required().stream()
        .filter(hook -> !declared.contains(hook))
        .findFirst()
        .ifPresent(
            missing -> {
              throw new IllegalArgumentException(
                  "Hook file "
                      + path
                      + " names no event that runs "
                      + missing
                      + ", which every harness must run.");
            });
  }
}
