/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP client for the Sail control plane API. Used by CLI commands and in-container agents to
 * interact with the server.
 */
public final class SailApiClient implements AutoCloseable {

  private final String baseUrl;
  private final String token;
  private final HttpClient client;

  public SailApiClient(String baseUrl, String token) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.token = token;
    this.client = HttpClient.newHttpClient();
  }

  public Map<String, Object> get(String path) throws IOException {
    var request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    return send(request);
  }

  public Map<String, Object> post(String path, Map<String, Object> body) throws IOException {
    var json = YamlUtil.dumpJson(new LinkedHashMap<>(body));
    var request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    return send(request);
  }

  public Map<String, Object> put(String path, Map<String, Object> body) throws IOException {
    var json = YamlUtil.dumpJson(new LinkedHashMap<>(body));
    var request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build();
    return send(request);
  }

  public Map<String, Object> delete(String path) throws IOException {
    var request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Authorization", "Bearer " + token)
            .method("DELETE", HttpRequest.BodyPublishers.noBody())
            .build();
    return send(request);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> send(HttpRequest request) throws IOException {
    try {
      var response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      var body = YamlUtil.parseMap(response.body());
      if (response.statusCode() >= 400) {
        var error = (Map<String, Object>) body.get("error");
        var message = error != null ? (String) error.get("message") : "API request failed";
        var action = error != null ? (String) error.get("action") : null;
        if (action != null && !action.isBlank()) {
          message = message + " " + action;
        }
        throw new IOException(message + " (HTTP " + response.statusCode() + ")");
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

  public static SailApiClient fromConfig() throws IOException {
    return fromConfig(SailPaths.clientConfigPath());
  }

  public static SailApiClient fromConfig(Path configPath) throws IOException {
    var config = ServerConnectionConfig.resolve(null, null, configPath);
    return new SailApiClient(config.serverUrl(), config.token());
  }
}
