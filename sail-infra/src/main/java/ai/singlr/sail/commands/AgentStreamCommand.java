/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.OperatorCommand;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
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

  @Spec private CommandSpec spec;

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String project;

  @Option(names = "--since", description = "Resume from line number.", defaultValue = "0")
  private int since;

  @Mixin private ConnectionOptions connection;

  @Override
  public void run() {
    CliCommand.run(this::execute);
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
          throw new IllegalStateException(
              formatHttpError(
                  response.statusCode(),
                  new String(body.readAllBytes(), StandardCharsets.UTF_8),
                  OperatorCommand.here()));
        }
      }

      try (var reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        print(reader.lines().iterator(), System.out, RootRequired.typed(spec));
      }
    }
  }

  /**
   * Prints each log line the stream sends: a stream the server ended with an {@code error} event
   * ends in the refusal it names, and one that ended or dropped without it in a failure naming
   * {@code followAgain}, each for the door to render for its reader.
   */
  static void print(Iterator<String> lines, PrintStream out, Remedy.Command followAgain)
      throws SailApiClient.Refused {
    SailApiClient.follow(lines, out::println, followAgain);
  }

  /**
   * A human-readable line for a non-200 stream response, as {@code reader} reads it: the server's
   * structured {@code message} and its remedy (and, for a {@code run_on_other_node} refusal, the
   * box its logs live on) instead of a bare HTTP status. So "logs live on sumesh's box" survives to
   * the terminal rather than being swallowed as "HTTP 409".
   */
  static String formatHttpError(int status, String body, OperatorCommand reader) {
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
    var rendered = String.join(" ", CliCommand.lines(SailApiClient.Refused.of(parsed), reader));
    var node = Objects.toString(parsed.get("node"), "");
    if (Strings.isNotBlank(node)) {
      return rendered + " Connect to " + node + "'s box to stream it there.";
    }
    return rendered;
  }

  @SuppressWarnings("unchecked")
  private String latestRunId(ServerConnectionConfig config) throws Exception {
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var runs = (List<Map<String, Object>>) client.get("/v1/runs?project=" + project).get("runs");
      if (runs == null || runs.isEmpty()) {
        return null;
      }
      return (String) runs.getFirst().get("id");
    }
  }
}
