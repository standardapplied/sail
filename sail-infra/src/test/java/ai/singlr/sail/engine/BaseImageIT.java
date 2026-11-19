/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The base image on the runner's Incus, held wrongly as a box that cached the virtual-machine image
 * holds it: an image Incus types {@code virtual-machine} (a tarball of {@code metadata.yaml} and a
 * {@code rootfs.img}, which is how Incus tells one) under the alias. A launch from it is refused
 * from its listed type, and the cache replaces it with the server's container image.
 */
class BaseImageIT extends AbstractIncusIT {

  private static final String ALIAS = "sail-it-base";

  @TempDir Path dir;

  @AfterEach
  void removeTheAlias() {
    try {
      new BaseImage(shell, "ubuntu/24.04", ALIAS)
          .held()
          .filter(held -> !held.container())
          .ifPresent(wrong -> quietly(List.of("incus", "image", "delete", wrong.fingerprint())));
    } catch (Exception ignored) {
    }
    quietly(List.of("incus", "image", "alias", "delete", ALIAS));
  }

  @Test
  void aVirtualMachineImageUnderTheAliasIsRefusedForALaunchAndReplacedByTheContainerImage()
      throws Exception {
    ensureIncusOrSkip();
    removeTheAlias();
    Files.writeString(
        dir.resolve("metadata.yaml"), "architecture: x86_64\ncreation_date: 1700000000\n");
    Files.write(dir.resolve("rootfs.img"), new byte[1024 * 1024]);
    run(
        List.of(
            "tar",
            "-C",
            dir.toString(),
            "-cf",
            dir.resolve("vm.tar").toString(),
            "metadata.yaml",
            "rootfs.img"));
    run(List.of("incus", "image", "import", dir.resolve("vm.tar").toString(), "--alias", ALIAS));
    var image = new BaseImage(shell, "ubuntu/24.04", ALIAS);
    assertEquals("virtual-machine", image.held().orElseThrow().type());

    var refused =
        assertThrows(IOException.class, () -> image.requireContainer(Remedy.of("Heal it.")));
    var said = assertInstanceOf(Remedied.class, refused);
    assertTrue(
        said.reason().startsWith("This runner's image " + ALIAS + " is the virtual-machine image"),
        said.reason());

    assertTrue(image.ensure().startsWith("replaced the virtual-machine image"));
    assertEquals("container", image.held().orElseThrow().type());
    image.requireContainer(Remedy.of("Heal it."));
    assertEquals("already cached", image.ensure());
  }

  private void run(List<String> command) throws Exception {
    var result = shell.exec(command);
    assertTrue(result.ok(), String.join(" ", command) + ": " + result.reason());
  }

  private void quietly(List<String> command) {
    try {
      shell.exec(command);
    } catch (Exception ignored) {
    }
  }
}
