/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ServerConnectionConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Streams agent log output via SSE from the Sail server. Connects to the server's agent stream
 * endpoint and prints output as it arrives. Supports reconnection via {@code --since}.
 */
@Command(
    name = "stream",
    description = "Stream live agent output from the server.",
    mixinStandardHelpOptions = true)
public final class AgentStreamCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String project;

  @Option(names = "--since", description = "Resume from line number.", defaultValue = "0")
  private int since;

  @Option(names = "--server", description = "Server URL.")
  private String server;

  @Option(names = "--token", description = "API token.")
  private String token;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    var config = ServerConnectionConfig.resolve(server, token);
    var path = "/v1/projects/" + project + "/agent/stream" + (since > 0 ? "?since=" + since : "");
    var uri = URI.create(config.serverUrl() + path);

    var request =
        HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer " + config.token())
            .header("Accept", "text/event-stream")
            .GET()
            .build();

    System.out.println(
        Ansi.AUTO.string("  @|faint Streaming agent output for " + project + "...|@"));

    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        System.err.println("Server returned HTTP " + response.statusCode());
        return;
      }

      try (var reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.startsWith("data: ")) {
            System.out.println(line.substring(6));
          }
        }
      }
    }
  }
}
