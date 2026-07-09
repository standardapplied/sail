/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
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

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String project;

  @Option(names = "--since", description = "Resume from line number.", defaultValue = "0")
  private int since;

  @Mixin private ConnectionOptions connection;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  @Option(
      names = "--run",
      description = "Stream a specific run id (default: the project's latest).")
  private String runId;

  private void execute() throws Exception {
    project = CurrentProject.require(project);
    var config = connection.resolve();
    if (runId == null) {
      runId = latestRunId(config);
      if (runId == null) {
        System.out.println(Ansi.AUTO.string("  @|faint No runs found for " + project + ".|@"));
        return;
      }
    }
    var path = "/v1/runs/" + runId + "/stream" + (since > 0 ? "?since=" + since : "");
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
        try (var body = response.body()) {
          System.err.println(
              formatHttpError(
                  response.statusCode(), new String(body.readAllBytes(), StandardCharsets.UTF_8)));
        }
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

  /**
   * A human-readable line for a non-200 stream response: the server's structured {@code message}
   * (and, for a {@code run_on_other_node} refusal, the box its logs live on) instead of a bare HTTP
   * status. So "logs live on sumesh's box" survives to the terminal rather than being swallowed as
   * "HTTP 409".
   */
  static String formatHttpError(int status, String body) {
    Map<String, Object> parsed;
    try {
      parsed = YamlUtil.parseMap(body);
    } catch (RuntimeException e) {
      parsed = Map.of();
    }
    var message = Objects.toString(parsed.get("message"), "");
    if (Strings.isBlank(message)) {
      return "Server returned HTTP " + status + ".";
    }
    var node = Objects.toString(parsed.get("node"), "");
    if (Strings.isNotBlank(node)) {
      return message + " Connect to " + node + "'s box to stream it there.";
    }
    return message;
  }

  @SuppressWarnings("unchecked")
  private String latestRunId(ai.singlr.sail.api.ServerConnectionConfig config) throws Exception {
    try (var client = new ai.singlr.sail.api.SailApiClient(config.serverUrl(), config.token())) {
      var runs =
          (java.util.List<java.util.Map<String, Object>>)
              client.get("/v1/runs?project=" + project).get("runs");
      if (runs == null || runs.isEmpty()) {
        return null;
      }
      return (String) runs.getFirst().get("id");
    }
  }
}
