/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.ssh.SshGateway;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/**
 * Internal entry point for the {@code sail} user's SSH forced command. It is not meant to be run by
 * hand: each registered key's {@code authorized_keys} line is {@code command="sail _gateway
 * --fde-id <id>"}, the key's person by this box's id for them, so sshd invokes this with the
 * engineer's real command in {@code SSH_ORIGINAL_COMMAND}. A line written before keys were pinned
 * by id names a handle ({@code --fde <handle>}); this is where it is still read, by handle, until
 * {@code migrate} or any key change regenerates the file. It authorizes the request through {@link
 * SshGateway} — resolving the FDE, classifying the command, minting a short-lived session — then
 * re-execs {@code sail} with {@code SAIL_TOKEN} set. A loopback-API command then acts as that FDE,
 * each request decided by {@code Access}; an administration command, which the gateway admitted
 * only as Manage people, acts on the roster directly. Refused commands mint nothing.
 */
@Command(name = "_gateway", description = "Internal SSH forced-command entry point.", hidden = true)
public final class GatewayCommand implements Callable<Integer> {

  @ArgGroup(multiplicity = "1")
  private Pinned pinned;

  static final class Pinned {
    @Option(names = "--fde-id", description = "This box's id for the person the key is pinned to.")
    String id;

    @Option(names = "--fde", description = "The handle a line written before ids pinned the key.")
    String handle;
  }

  @Override
  public Integer call() throws Exception {
    var original = System.getenv("SSH_ORIGINAL_COMMAND");
    try (var operations = OperationsFactory.open()) {
      var identity = operations.identity();
      var decision =
          pinned.id != null
              ? identity.authorizeGatewayById(original, pinned.id)
              : identity.authorizeGateway(original, pinned.handle);
      return switch (decision) {
        case SshGateway.Rejected rejected -> {
          System.err.println(Banner.errorLine(rejected.reason(), Ansi.AUTO));
          yield 1;
        }
        case SshGateway.Authorized authorized -> exec(authorized);
      };
    }
  }

  private static int exec(SshGateway.Authorized authorized) throws Exception {
    var command = new ArrayList<String>();
    command.add(SailPaths.binaryPath().toString());
    command.addAll(authorized.args());
    var builder = new ProcessBuilder(command);
    builder.environment().put("SAIL_TOKEN", authorized.sessionToken());
    builder.inheritIO();
    return builder.start().waitFor();
  }
}
