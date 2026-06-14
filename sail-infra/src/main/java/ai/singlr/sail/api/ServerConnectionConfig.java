/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Resolves server connection details. Resolution order for URL: {@code --server} flag → {@code
 * SAIL_SERVER} env → config file → {@code http://localhost:7070}. Resolution order for token:
 * {@code --token} flag → {@code --token-file} → {@code SAIL_TOKEN} env → {@code SAIL_TOKEN_FILE}
 * env → config file. The file/env paths exist so a token need never appear on the command line,
 * where it leaks into the process list ({@code /proc}, {@code ps}) and shell history.
 *
 * <p>The config file lives at {@link SailPaths#clientConfigPath()} in production; tests pass an
 * explicit path via the {@link #resolve(String, String, Path)} overloads.
 */
public record ServerConnectionConfig(String serverUrl, String token) {

  private static final String DEFAULT_URL = "http://localhost:7070";

  public static ServerConnectionConfig resolve() throws IOException {
    return resolve(null, null, SailPaths.clientConfigPath());
  }

  public static ServerConnectionConfig resolve(String serverFlag, String tokenFlag, Path configPath)
      throws IOException {
    return resolve(serverFlag, tokenFlag, null, configPath);
  }

  @SuppressWarnings("unchecked")
  public static ServerConnectionConfig resolve(
      String serverFlag, String tokenFlag, Path tokenFileFlag, Path configPath) throws IOException {
    var url = serverFlag;
    var token = tokenFlag != null ? tokenFlag : readTokenFile(tokenFileFlag);

    if (url == null) url = envOrProperty("SAIL_SERVER");
    if (token == null) token = envOrProperty("SAIL_TOKEN");
    if (token == null) token = readTokenFile(envPath("SAIL_TOKEN_FILE"));

    if ((url == null || token == null) && Files.exists(configPath)) {
      var config = YamlUtil.parseFile(configPath);
      if (url == null) url = (String) config.get("server");
      if (token == null) token = (String) config.get("token");
    }

    if (url == null) url = DEFAULT_URL;
    if (token == null) {
      throw new IOException(
          "No API token configured. Set SAIL_TOKEN, pass --token-file, or run 'sail login'.");
    }
    return new ServerConnectionConfig(url, token);
  }

  /** Reads a token from a file, trimming surrounding whitespace; {@code null} path yields null. */
  private static String readTokenFile(Path path) throws IOException {
    if (path == null) {
      return null;
    }
    var token = Files.readString(path).strip();
    return Strings.isEmpty(token) ? null : token;
  }

  private static Path envPath(String name) {
    var value = envOrProperty(name);
    return Strings.isBlank(value) ? null : Path.of(value);
  }

  public static void saveLocalToken(String token, Path configPath) throws IOException {
    saveLocalConfig(DEFAULT_URL, token, configPath);
  }

  /**
   * Persists a login {@code token} (typically a session minted by {@code sail login}) while
   * preserving the existing configured server URL, so signing in does not repoint the CLI.
   */
  public static void saveSessionToken(String token, Path configPath) throws IOException {
    var serverUrl = DEFAULT_URL;
    if (Files.exists(configPath)) {
      var existing = (String) YamlUtil.parseFile(configPath).get("server");
      if (Strings.isNotBlank(existing)) {
        serverUrl = existing;
      }
    }
    saveLocalConfig(serverUrl, token, configPath);
  }

  /**
   * Writes {@code server} and {@code token} while preserving every other key in the file. The
   * client config ({@code host}, {@code user}) shares this file on an engineer's machine, so a
   * plain overwrite would destroy the client's host pointer and silently flip the CLI out of client
   * mode the first time they sign in.
   */
  public static void saveLocalConfig(String serverUrl, String token, Path configPath)
      throws IOException {
    var config =
        Files.exists(configPath)
            ? new LinkedHashMap<>(YamlUtil.parseFile(configPath))
            : new LinkedHashMap<String, Object>();
    config.put("server", serverUrl);
    config.put("token", token);
    Files.createDirectories(configPath.getParent());
    Files.setPosixFilePermissions(configPath.getParent(), OWNER_ONLY_DIR);
    if (!Files.exists(configPath)) {
      Files.createFile(configPath, PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE));
    }
    YamlUtil.dumpToFile(config, configPath);
    Files.setPosixFilePermissions(configPath, OWNER_ONLY_FILE);
  }

  private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);

  private static String envOrProperty(String name) {
    var env = System.getenv(name);
    return env != null ? env : System.getProperty(name);
  }
}
