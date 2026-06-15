/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.Banner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Unmatched;

/**
 * Deprecated shim. Service removal moved to the noun-first {@code project service remove} group;
 * this points there and otherwise does nothing.
 */
@Command(name = "remove", hidden = true, description = "(deprecated) Use 'project service remove'.")
public final class ProjectRemoveCommand implements Callable<Integer> {

  @Unmatched private List<String> ignored = new ArrayList<>();

  @Override
  public Integer call() {
    System.err.println(
        Banner.errorLine(
            "'sail project remove' has moved. Use 'sail project service remove'.", Ansi.AUTO));
    return 2;
  }
}
