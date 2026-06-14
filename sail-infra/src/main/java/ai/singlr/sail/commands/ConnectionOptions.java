/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.nio.file.Path;
import picocli.CommandLine.Option;

/**
 * Shared server-connection options for every command that talks to the control-plane API. Mixed
 * into a command with {@code @Mixin} so the {@code --server}/{@code --token} surface — and its
 * resolution order — is defined once rather than copied into a dozen commands.
 *
 * <p>{@code --token} is supported but discouraged: a value on the command line is visible in the
 * process list and shell history. Prefer {@code SAIL_TOKEN}, {@code --token-file}, or {@code sail
 * login}.
 */
public final class ConnectionOptions {

  @Option(names = "--server", description = "Server URL.")
  private String server;

  @Option(
      names = "--token",
      description =
          "API token. Insecure — visible in the process list and shell history; prefer SAIL_TOKEN,"
              + " --token-file, or 'sail login'.")
  private String token;

  @Option(
      names = "--token-file",
      description = "Read the API token from a file (keeps it out of the process list).")
  private Path tokenFile;

  /** Resolves the connection from these options, the environment, and the client config file. */
  public ServerConnectionConfig resolve() throws IOException {
    return ServerConnectionConfig.resolve(server, token, tokenFile, SailPaths.clientConfigPath());
  }
}
