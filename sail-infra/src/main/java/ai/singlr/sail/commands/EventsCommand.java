/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "events",
    description =
        "Stream events from the sail API event bus as they happen, until the stream ends. What"
            + " changed while nothing was streaming is read with GET /v1/changes.",
    mixinStandardHelpOptions = true)
public final class EventsCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Option(
      names = {"-p", "--project"},
      description = "Only show events for this project.")
  private String projectFilter;

  @Option(
      names = "--type",
      description = "Comma-separated list of event types to include.",
      split = ",")
  private List<String> typeFilter;

  @Option(names = "--host", description = "API host.", defaultValue = "127.0.0.1")
  private String host;

  @Option(names = "--port", description = "API port.", defaultValue = "7070")
  private int port;

  @Option(names = "--json", description = "Print one JSON event per line (raw bus payload).")
  private boolean json;

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var token = ServerConnectionConfig.resolve().token();
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    streamEvents(client, token, System.out);
  }

  private void streamEvents(HttpClient client, String token, PrintStream out) throws Exception {
    var request =
        HttpRequest.newBuilder(streamUri())
            .header("Authorization", "Bearer " + token)
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofHours(24))
            .GET()
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofLines());
    try (Stream<String> lineStream = response.body()) {
      if (response.statusCode() != 200) {
        throw SailApiClient.Refused.fromBody(
            response.statusCode(), lineStream.collect(Collectors.joining("\n")));
      }
      print(lineStream.iterator(), out, json, RootRequired.typed(spec));
    }
  }

  /**
   * Prints each event the stream sends: a stream the server ended with an {@code error} event ends
   * in the refusal it names, and one that ended or dropped without it in a failure naming {@code
   * followAgain}.
   */
  static void print(
      Iterator<String> lines, PrintStream out, boolean json, Remedy.Command followAgain)
      throws SailApiClient.Refused {
    SailApiClient.follow(
        lines,
        data -> {
          try {
            printOne(out, Event.fromJsonLine(data), json);
          } catch (Exception parseError) {
            System.err.println("  ⚠ failed to parse event: " + parseError.getMessage());
          }
        },
        followAgain);
  }

  private static void printOne(PrintStream out, Event event, boolean json) {
    if (json) {
      out.println(event.toJsonLine());
    } else {
      out.println(EventRenderer.human(event));
    }
  }

  private URI streamUri() {
    var params = new LinkedHashMap<String, String>();
    if (Strings.isNotBlank(projectFilter)) {
      params.put("project", projectFilter);
    }
    if (typeFilter != null && !typeFilter.isEmpty()) {
      params.put("type", String.join(",", typeFilter));
    }
    return URI.create(baseUrl() + "/v1/events/stream" + buildQuery(params));
  }

  private String baseUrl() {
    return "http://" + host + ":" + port;
  }

  private static String buildQuery(Map<String, String> params) {
    if (params.isEmpty()) {
      return "";
    }
    var sb = new StringBuilder("?");
    var first = true;
    for (var entry : params.entrySet()) {
      if (!first) {
        sb.append('&');
      }
      sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
      first = false;
    }
    return sb.toString();
  }
}
