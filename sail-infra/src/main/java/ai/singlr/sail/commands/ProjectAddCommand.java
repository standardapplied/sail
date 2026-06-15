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
 * Deprecated shim. Service and repo management moved to the noun-first {@code project service} and
 * {@code project repo} groups; this points there and otherwise does nothing.
 */
@Command(
    name = "add",
    hidden = true,
    description = "(deprecated) Use 'project service add' or 'project repo add'.")
public final class ProjectAddCommand implements Callable<Integer> {

  @Unmatched private List<String> ignored = new ArrayList<>();

  @Override
  public Integer call() {
    System.err.println(
        Banner.errorLine(
            "'sail project add' has moved. Use 'sail project service add' or"
                + " 'sail project repo add'.",
            Ansi.AUTO));
    return 2;
  }
}
