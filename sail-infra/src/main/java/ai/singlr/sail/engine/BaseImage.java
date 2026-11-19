/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * An image Sail launches containers from: {@code images:<image>} on the image server, held here
 * under the local alias {@code <image>} with {@code /} as {@code -}. It is only ever a container
 * image, decided from the type Incus lists. {@code incus image copy} without {@code --vm} names no
 * type: Incus looks for the container image of the alias and, on any error of that lookup, takes
 * the virtual-machine image of the same alias, which a box without KVM cannot run. So the container
 * image is found by its type in the server's listing and copied by fingerprint.
 */
public final class BaseImage {

  static final String CONTAINER = "container";
  private static final List<String> LOCAL =
      List.of("incus", "image", "list", "local:", "--format=json");
  private static final String REMOTE = "images:";
  private static final Duration TIMEOUT = Duration.ofMinutes(5);

  private final ShellExec shell;
  private final String image;
  private final String alias;

  /** {@code image}, as a project or host.yaml names it ({@code ubuntu/24.04}), on {@code shell}. */
  public BaseImage(ShellExec shell, String image) {
    this(shell, image, image.replace('/', '-'));
  }

  BaseImage(ShellExec shell, String image, String alias) {
    this.shell = shell;
    this.image = image;
    this.alias = alias;
  }

  /** The image held under this alias: its fingerprint and type. */
  public record Held(String fingerprint, String type) {
    boolean container() {
      return CONTAINER.equals(type);
    }

    String said() {
      return "the "
          + type
          + " image "
          + fingerprint.substring(0, Math.min(12, fingerprint.length()));
    }
  }

  /** The local alias a launch names. */
  public String alias() {
    return alias;
  }

  /** The image held under the alias, if any. */
  public Optional<Held> held() throws IOException, InterruptedException, TimeoutException {
    return held(run(LOCAL));
  }

  private Optional<Held> held(ShellExec.Result listed) {
    return named(YamlUtil.parseList(listed.stdout()), alias).findFirst();
  }

  /**
   * Holds the container image under the alias, replacing an image of another type, and says what it
   * did; refused, naming what was copied, when Incus does not hold a container image after the
   * copy.
   */
  public String ensure() throws IOException, InterruptedException, TimeoutException {
    var before = held();
    if (before.isPresent() && before.get().container()) {
      return "already cached";
    }
    var fingerprint = remoteContainer();
    if (before.isPresent()) {
      run(List.of("incus", "image", "delete", before.get().fingerprint()));
    }
    run(List.of("incus", "image", "copy", REMOTE + fingerprint, "local:", "--alias", alias));
    var after = held();
    if (after.isEmpty() || !after.get().container()) {
      throw new IOException(
          "Copied "
              + REMOTE
              + fingerprint
              + " as "
              + alias
              + ", but Incus holds "
              + after.map(Held::said).orElse("no image")
              + " under it, not a container image.");
    }
    return before
        .map(wrong -> "replaced " + wrong.said() + " with the container image")
        .orElse("cached");
  }

  /**
   * Refuses, saying so and how {@code heal} mends it, a launch from an alias whose image is not a
   * container image. An alias with no image, or a listing Incus refuses, is left to the launch,
   * which says why it cannot.
   */
  public void requireContainer(Remedy heal)
      throws IOException, InterruptedException, TimeoutException {
    var listed = shell.exec(LOCAL, null, TIMEOUT);
    if (!listed.ok()) {
      return;
    }
    var held = held(listed);
    if (held.isPresent() && !held.get().container()) {
      throw Remedied.io(
          "This runner's image "
              + alias
              + " is "
              + held.get().said()
              + ", which it cannot run as a container.",
          heal);
    }
  }

  private String remoteContainer() throws IOException, InterruptedException, TimeoutException {
    var listed =
        named(
                listed(
                    List.of(
                        "incus",
                        "image",
                        "list",
                        REMOTE,
                        image,
                        "type=" + CONTAINER,
                        "--format=json")),
                image)
            .filter(Held::container)
            .toList();
    if (listed.size() != 1) {
      throw new IOException(
          "The image server lists "
              + (listed.isEmpty() ? "no" : listed.size())
              + " container image named "
              + image
              + " for this box ("
              + REMOTE
              + image
              + "), so none was cached.");
    }
    return listed.getFirst().fingerprint();
  }

  private static Stream<Held> named(List<Map<String, Object>> images, String name) {
    return images.stream()
        .filter(
            entry ->
                entry.get("aliases") instanceof List<?> aliases
                    && aliases.stream()
                        .anyMatch(a -> a instanceof Map<?, ?> m && name.equals(m.get("name"))))
        .map(
            entry ->
                new Held(
                    String.valueOf(entry.get("fingerprint")), String.valueOf(entry.get("type"))));
  }

  private List<Map<String, Object>> listed(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    return YamlUtil.parseList(run(command).stdout());
  }

  private ShellExec.Result run(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(command, null, TIMEOUT);
    if (!result.ok()) {
      throw new IOException(String.join(" ", command) + " failed: " + result.reason());
    }
    return result;
  }
}
