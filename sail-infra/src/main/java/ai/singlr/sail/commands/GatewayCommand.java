/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Sqlite;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/**
 * Internal entry point for the {@code sail} user's SSH forced command. It is not meant to be run by
 * hand: each registered key's {@code authorized_keys} line is {@code command="sail _gateway --fde
 * <handle>"}, so sshd invokes this with the engineer's real command in {@code
 * SSH_ORIGINAL_COMMAND}. It authorizes the request through {@link SshGateway} — resolving the FDE,
 * classifying the command by kind and role, minting a short-lived session — then re-execs {@code
 * sail} with {@code SAIL_TOKEN} set so the command runs as that FDE under role-based authorization.
 * Refused commands mint nothing.
 */
@Command(name = "_gateway", description = "Internal SSH forced-command entry point.", hidden = true)
public final class GatewayCommand implements Callable<Integer> {

  @Option(names = "--fde", required = true, description = "FDE handle bound to the calling key.")
  private String fde;

  @Override
  public Integer call() throws Exception {
    var original = System.getenv("SSH_ORIGINAL_COMMAND");
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      var decision =
          SshGateway.authorize(original, fde, new FdeStore(db), new AuthSessionStore(db));
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
