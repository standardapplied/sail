/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.pty.Pty;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * A native-image self-check for the FFM pty layer: opens a real pty, spawns a trivial child, and
 * resizes it. Opening the pty forces {@link Pty}'s static initializer, which builds every libc
 * downcall handle — so a missing foreign registration (which fails only in the GraalVM native
 * binary, never the JVM unit tests) is caught by the release smoke test rather than by a user. A
 * no-op on non-Linux, where the pty host never runs.
 */
@Command(name = "_pty-selftest", description = "Internal: verify the pty FFM layer.", hidden = true)
public final class PtySelfTestCommand implements Callable<Integer> {

  @Override
  public Integer call() {
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
}
