/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * HTTP client for the Sail control plane API. Used by CLI commands and in-container agents to
 * interact with the server.
 */
public final class SailApiClient implements AutoCloseable {

  private final String baseUrl;
  private final String token;
  private final boolean noSync;
  private final HttpClient client;

  public SailApiClient(String baseUrl, String token) {
    this(baseUrl, token, false);
  }

  /**
   * As {@link #SailApiClient(String, String)}, with {@code noSync} carrying the CLI's {@code
   * --no-sync} flag: every request then asks the server to act on its local replica without the
   * automatic reconcile with main.
   */
  public SailApiClient(String baseUrl, String token, boolean noSync) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.token = token;
    this.noSync = noSync;
    this.client = HttpClient.newHttpClient();
  }

  public Map<String, Object> get(String path) throws IOException {
    return send(request(path).GET().build());
  }

  public Map<String, Object> post(String path, Map<String, Object> body) throws IOException {
    var json = YamlUtil.dumpJson(new LinkedHashMap<>(body));
    return send(
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build());
  }

  public Map<String, Object> put(String path, Map<String, Object> body) throws IOException {
    var json = YamlUtil.dumpJson(new LinkedHashMap<>(body));
    return send(
        request(path)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build());
  }

  public Map<String, Object> delete(String path) throws IOException {
    return send(request(path).method("DELETE", HttpRequest.BodyPublishers.noBody()).build());
  }

  private HttpRequest.Builder request(String path) {
    var builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Authorization", "Bearer " + token);
    if (noSync) {
      builder.header(SyncControl.NO_SYNC_HEADER, "true");
    }
    return builder;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> send(HttpRequest request) throws IOException {
    try {
      var response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      var body = YamlUtil.parseMap(response.body());
      if (response.statusCode() >= 400) {
        throw Refused.of(response.statusCode(), (Map<String, Object>) body.get("error"));
      }
      return body;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Request interrupted", e);
    }
  }

  @Override
  public void close() {
    client.close();
  }

  /**
   * sail-api's refusal of a request: its message and its remedy, the commands in it kept as data
   * for the door that prints them to render for its reader.
   */
  public static final class Refused extends IOException implements Remedied {
    private final String reason;
    private final transient Remedy remedy;

    private Refused(String reason, Remedy remedy) {
      super(Remedied.message(reason, remedy));
      this.reason = reason;
      this.remedy = remedy;
    }

    /** The refusal an error envelope with {@code status} describes. */
    public static Refused of(int status, Map<String, Object> error) {
      var http = " (HTTP " + status + ")";
      if (error == null) {
        return new Refused("API request failed" + http, Remedy.NONE);
      }
      var refused = of(error);
      return refused.remedy.isEmpty()
          ? new Refused(refused.reason + http, Remedy.NONE)
          : new Refused(refused.reason, refused.remedy.then(http));
    }

    /** The refusal a response with {@code status} and an error envelope as its body describes. */
    @SuppressWarnings("unchecked")
    public static Refused fromBody(int status, String body) {
      return of(status, (Map<String, Object>) YamlUtil.parseMap(body).get("error"));
    }

    /** The refusal {@code error} describes, a stream's closing {@code error} event among them. */
    public static Refused of(Map<String, Object> error) {
      var message = Objects.toString(error.get("message"), "API request failed");
      var remedy =
          error.get("remedy") instanceof List<?> parts
              ? Remedy.fromMaps(parts)
              : Result.words(Objects.toString(error.get("action"), null));
      return new Refused(message, remedy);
    }

    @Override
    public String reason() {
      return reason;
    }

    @Override
    public Remedy remedy() {
      return remedy;
    }
  }

  /**
   * Hands each event's data in an SSE stream to {@code onData} until the stream ends; a stream the
   * server ended with an {@code error} event ends in the refusal it names.
   */
  public static void readStream(Iterator<String> lines, Consumer<String> onData) throws Refused {
    var ending = false;
    while (lines.hasNext()) {
      var line = lines.next();
      if (line.isEmpty()) {
        ending = false;
      } else if (line.equals("event: error")) {
        ending = true;
      } else if (line.startsWith("data: ")) {
        var data = line.substring("data: ".length());
        if (ending) {
          throw Refused.of(YamlUtil.parseMap(data));
        }
        onData.accept(data);
      }
    }
  }

  /**
   * Follows an SSE stream as {@link #readStream} reads it, for as long as the server sends it: a
   * followed stream never ends on its own, so one that ends or drops without an {@code error} event
   * fails, naming {@code followAgain}, the command that follows it again.
   */
  public static void follow(
      Iterator<String> lines, Consumer<String> onData, Remedy.Command followAgain) throws Refused {
    try {
      readStream(lines, onData);
    } catch (UncheckedIOException dropped) {
      throw closed(followAgain, dropped);
    }
    throw closed(followAgain, null);
  }

  private static IllegalStateException closed(Remedy.Command followAgain, Throwable cause) {
    return Remedied.state(
        "The sail API closed the stream (it restarted or stopped).",
        Remedy.of("Follow again: ", followAgain),
        cause);
  }

  public static SailApiClient fromConfig() throws IOException {
    return fromConfig(SailPaths.clientConfigPath());
  }

  public static SailApiClient fromConfig(Path configPath) throws IOException {
    var config = ServerConnectionConfig.resolve(null, null, configPath);
    return new SailApiClient(config.serverUrl(), config.token());
  }
}
