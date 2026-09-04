/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.pty.Pty;
import ai.singlr.sail.pty.PtyMessage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * A native-image self-check for the layers that only fail in the GraalVM binary, never in the JVM
 * unit tests, so the release smoke test catches them rather than a user.
 *
 * <p>The FFM pty layer: opens a real pty, spawns a trivial child, and resizes it. Opening the pty
 * forces {@link Pty}'s static initializer, which builds every libc downcall handle — a missing
 * foreign registration surfaces here. A no-op on non-Linux, where the pty host never runs.
 *
 * <p>The CLI JSON layer: {@link CliJson} reads record components reflectively, and a record it
 * serializes that is missing from the reflection configuration dies in the native binary with an
 * unsupported-feature error (the {@code sail session ls --json} field failure). Every record a
 * {@code --json} verb prints is stringified here on every platform.
 */
@Command(
    name = "_pty-selftest",
    description = "Internal: verify the pty FFM layer and CLI JSON reflection.",
    hidden = true)
public final class PtySelfTestCommand implements Callable<Integer> {

  static final List<String> JSON_KEYS =
      List.of("host_boot_id", "instance_id", "event_drops", "spec_title", "dispatched");

  @Override
  public Integer call() {
    try {
      var json = jsonProbe();
      for (var key : JSON_KEYS) {
        if (!json.contains("\"" + key + "\"")) {
          throw new IllegalStateException("CLI JSON lost the key '" + key + "': " + json);
        }
      }
      System.out.println("json-selftest: ok");
    } catch (Throwable e) {
      System.err.println("json-selftest: FAILED: " + e);
      return 1;
    }
    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
      System.out.println("pty-selftest: skipped (non-Linux)");
      return 0;
    }
    try (var pty = Pty.open(80, 24)) {
      var child = pty.spawn(List.of("true"), Map.of("TERM", "dumb"), Path.of("/"));
      pty.resize(100, 40);
      child.destroyForcibly();
      System.out.println("pty-selftest: ok");
      return 0;
    } catch (Exception e) {
      System.err.println("pty-selftest: FAILED: " + e);
      return 1;
    }
  }

  /** Stringifies one instance of every record a {@code --json} verb prints. */
  static String jsonProbe() {
    var info =
        new PtyMessage.SessionInfo("probe", "instance", true, 0, "fde", "", List.of("bash", "-l"));
    var listing = new SessionCommand.Ls.Listing(1, "boot", List.of(info), PtyEventDrops.Drops.NONE);
    var preview = new DispatchCommand.DispatchPreview("box", "spec", "title", "foreground", "task");
    var idle = new DispatchCommand.NoDispatch("box", false, "no_pending_specs");
    return CliJson.stringify(List.of(listing, preview, idle));
  }
}
