/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Command;

@Command(
    name = "agent",
    description = "Manage AI coding agent sessions.",
    mixinStandardHelpOptions = true,
    subcommands = {
      AgentLaunchCommand.class,
      RunCommand.class,
      AgentAttachCommand.class,
      AgentStreamCommand.class,
      AgentSessionsCommand.class,
      AgentStatusCommand.class,
      AgentStopCommand.class,
      AgentLogCommand.class,
      AgentReviewCommand.class,
      AgentSweepCommand.class,
      AgentContextCommand.class,
      AgentWatchCommand.class,
      AgentReportCommand.class,
    })
public final class AgentCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
