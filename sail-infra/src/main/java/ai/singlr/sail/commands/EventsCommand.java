/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "events",
    description =
        "Show events from the sail API event bus. By default prints recent events and exits; use"
            + " --follow to stream live.",
    mixinStandardHelpOptions = true)
public final class EventsCommand implements Runnable {

  private static final String DATA_PREFIX = "data: ";
  private static final int DEFAULT_RECENT_LIMIT = 100;

  @Option(
      names = {"-f", "--follow"},
      description = "Stream events as they happen; otherwise print last -n and exit.")
  private boolean follow;

  @Option(
      names = {"-n", "--lines"},
      description = "Number of recent events to print (non-follow mode).",
      defaultValue = "100")
  private int lines;

  @Option(names = "--project", description = "Only show events for this project.")
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

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var token = ServerConnectionConfig.resolve().token();
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    if (follow) {
      streamEvents(client, token, System.out);
    } else {
      printRecent(client, token, System.out);
    }
  }

  private void printRecent(HttpClient client, String token, PrintStream out) throws Exception {
    if (lines <= 0) {
      throw new IllegalArgumentException("--lines must be positive");
    }
    var uri = recentUri();
    var request =
        HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET()
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "GET " + uri + " returned " + response.statusCode() + ": " + response.body());
    }
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) YamlUtil.parseMap(response.body());
    @SuppressWarnings("unchecked")
    var events = (List<Map<String, Object>>) body.get("events");
    if (events == null || events.isEmpty()) {
      out.println(Ansi.AUTO.string("  @|faint No events yet.|@"));
      return;
    }
    for (var map : events) {
      printOne(out, Event.fromMap(map));
    }
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
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Stream request returned HTTP " + response.statusCode());
    }
    try (Stream<String> lineStream = response.body()) {
      lineStream.forEach(
          line -> {
            if (line.startsWith(DATA_PREFIX)) {
              try {
                printOne(out, Event.fromJsonLine(line.substring(DATA_PREFIX.length())));
              } catch (Exception parseError) {
                System.err.println("  ⚠ failed to parse event: " + parseError.getMessage());
              }
            }
          });
    }
  }

  private void printOne(PrintStream out, Event event) {
    if (json) {
      out.println(event.toJsonLine());
    } else {
      out.println(EventRenderer.human(event));
    }
  }

  private URI recentUri() {
    var params = new LinkedHashMap<String, String>();
    params.put("limit", Integer.toString(lines > 0 ? lines : DEFAULT_RECENT_LIMIT));
    return URI.create(baseUrl() + "/v1/events/recent" + buildQuery(params));
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
