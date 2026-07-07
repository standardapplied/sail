/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.RuntimeMode;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Signs in with a passkey and stores the resulting session token. Opens the control-plane {@code
 * /login} page in a browser (the passkey ceremony must run at the Relying Party origin), passing a
 * loopback {@code /callback} as the redirect target and a one-time {@code state} nonce; the page
 * redirects the minted session token back to that loopback, which this command captures and writes
 * to the client config so subsequent {@code sail} calls authenticate as the FDE.
 *
 * <p>On a thin client (a box with a client config and no host config) there is no local origin to
 * sign in at, so the command opens a supervised {@link SshTunnel} to the configured host and runs
 * the ceremony at the canonical tunnel origin {@link SshTunnel#ORIGIN}, which the box must
 * allowlist. {@code --origin} (a reverse-proxy URL) or {@code --no-tunnel} keeps the direct path.
 */
@Command(
    name = "login",
    description = "Sign in with a passkey and store a session token.",
    mixinStandardHelpOptions = true)
public final class LoginCommand implements Runnable {

  @Option(
      names = "--origin",
      description =
          "Control-plane origin, e.g. https://sail.example.dev. Defaults to an SSH tunnel to the"
              + " configured client host, or to the webauthn origin from host.yaml on a box.")
  private String origin;

  @Option(
      names = "--no-tunnel",
      description = "Do not open an SSH tunnel; sign in at the configured origin directly.")
  private boolean noTunnel;

  @Option(
      names = "--timeout",
      description = "Seconds to wait for the browser sign-in to complete.",
      defaultValue = "180")
  private int timeoutSeconds;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(
        spec,
        () -> {
          if (origin == null
              && !noTunnel
              && RuntimeMode.detect() instanceof RuntimeMode.Client client) {
            tunneledCeremony(client.config().host());
            return;
          }
          var resolvedOrigin = origin != null ? origin : configuredOrigin();
          if (resolvedOrigin == null) {
            throw new IllegalArgumentException(
                "No control-plane origin. Pass --origin or configure the webauthn block in"
                    + " host.yaml.");
          }
          ceremony(resolvedOrigin, () -> {});
        });
  }

  private void tunneledCeremony(String host) throws Exception {
    System.out.println("  Opening an SSH tunnel to " + host + "…");
    try (var tunnel = SshTunnel.open(host)) {
      OriginPreflight.requireCanonicalOrigin(host);
      ceremony(SshTunnel.ORIGIN, tunnel::ensureAlive);
    }
  }

  private void ceremony(String resolvedOrigin, Runnable liveness) throws Exception {
    var state = LoopbackCallbackServer.newState();
    try (var callback = new LoopbackCallbackServer(state)) {
      callback.start();
      var url =
          resolvedOrigin
              + "/login?redirect_uri="
              + URLEncoder.encode(callback.redirectUri(), StandardCharsets.UTF_8)
              + "&state="
              + state;
      System.out.println("  Opening your browser to sign in:");
      System.out.println(Ansi.AUTO.string("    @|cyan " + url + "|@"));
      Browser.open(url);
      System.out.println("  Waiting for sign-in to complete…");
      var token = callback.awaitToken(Duration.ofSeconds(timeoutSeconds), liveness);
      var configPath = SailPaths.clientConfigPath();
      ServerConnectionConfig.saveSessionToken(token, configPath);
      System.out.println(
          Ansi.AUTO.string("  @|green ✓|@ Signed in. Session saved to " + configPath));
    }
  }

  private static String configuredOrigin() throws IOException {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) {
      return null;
    }
    var webauthn = HostYaml.fromMap(YamlUtil.parseFile(path)).webauthn();
    return webauthn.isConfigured() ? webauthn.origins().getFirst() : null;
  }
}
