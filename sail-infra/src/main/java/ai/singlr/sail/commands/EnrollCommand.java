/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ClientConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.RuntimeMode;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Self-enrolls a passkey from a thin client with no admin round-trip. The command mints an
 * enrollment ticket for the caller's own handle by sending {@code fde enroll --json} down the
 * existing SSH gateway lane — the gateway pins the caller's identity from their key, so a member
 * may mint for themself while minting for others stays admin-only — then opens a supervised {@link
 * SshTunnel} and drives the box's {@code /enroll} page at the canonical origin {@link
 * SshTunnel#ORIGIN}, where the browser prompts for a credential label and creates the passkey. A
 * loopback callback signals completion so the tunnel is torn down the moment the ceremony ends.
 */
@Command(
    name = "enroll",
    description = "Enroll a passkey for yourself over an SSH tunnel to your Sail box.",
    mixinStandardHelpOptions = true)
public final class EnrollCommand implements Runnable {

  @Option(
      names = "--timeout",
      description = "Seconds to wait for the browser enrollment to complete.",
      defaultValue = "300")
  private int timeoutSeconds;

  @Spec private CommandSpec spec;

  private final ShellExec shell;

  public EnrollCommand() {
    this(new ShellExecutor(false, Duration.ofSeconds(30)));
  }

  EnrollCommand(ShellExec shell) {
    this.shell = shell;
  }

  record Ticket(String ticket, String fde) {}

  @Override
  public void run() {
    CliCommand.run(
        spec,
        () -> {
          var client = requireThinClient();
          var ticket = mintTicket(client);
          System.out.println("  Opening an SSH tunnel to " + client.host() + "…");
          try (var tunnel = SshTunnel.open(client.host())) {
            OriginPreflight.requireCanonicalOrigin(client.host());
            browserCeremony(ticket, tunnel);
          }
        });
  }

  private void browserCeremony(Ticket ticket, SshTunnel tunnel) throws Exception {
    var state = LoopbackCallbackServer.newState();
    try (var callback = LoopbackCallbackServer.forEnrollment(state)) {
      callback.start();
      var url = enrollUrl(ticket.ticket(), callback.redirectUri(), state);
      System.out.println("  Opening your browser to enroll a passkey for " + ticket.fde() + ":");
      System.out.println(Ansi.AUTO.string("    @|cyan " + url + "|@"));
      Browser.open(url);
      System.out.println("  Waiting for the browser enrollment to complete…");
      callback.awaitToken(Duration.ofSeconds(timeoutSeconds), tunnel::ensureAlive);
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Passkey enrolled for " + ticket.fde() + ". Sign in with: sail login"));
    }
  }

  private Ticket mintTicket(ClientConfig client) throws Exception {
    System.out.println("  Minting an enrollment ticket via " + client.gatewayTarget() + "…");
    var result = shell.exec(mintCommand(client));
    if (!result.ok()) {
      throw new IllegalStateException(mintFailure(client.gatewayTarget(), result));
    }
    return parseTicket(result.stdout());
  }

  static List<String> mintCommand(ClientConfig client) {
    return List.of(
        "ssh",
        "-o",
        "BatchMode=yes",
        "-o",
        "PasswordAuthentication=no",
        "-o",
        "KbdInteractiveAuthentication=no",
        "--",
        client.gatewayTarget(),
        "sail",
        "fde",
        "enroll",
        "--json");
  }

  static Ticket parseTicket(String stdout) {
    Map<String, Object> body;
    try {
      body = YamlUtil.parseMap(stdout);
    } catch (Exception e) {
      throw new IllegalStateException(unexpectedTicket(stdout), e);
    }
    var ticket = Objects.toString(body.get("ticket"), "");
    var fde = Objects.toString(body.get("fde"), "");
    if (Strings.isBlank(ticket) || Strings.isBlank(fde)) {
      throw new IllegalStateException(unexpectedTicket(stdout));
    }
    return new Ticket(ticket, fde);
  }

  static String enrollUrl(String ticket, String redirectUri, String state) {
    return SshTunnel.ORIGIN
        + "/enroll?ticket="
        + URLEncoder.encode(ticket, StandardCharsets.UTF_8)
        + "&redirect_uri="
        + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
        + "&state="
        + state;
  }

  static String mintFailure(String gatewayTarget, ShellExec.Result result) {
    var stderr = result.stderr().strip();
    return "Could not mint an enrollment ticket via "
        + gatewayTarget
        + " (ssh exit "
        + result.exitCode()
        + ")."
        + (stderr.isEmpty() ? "" : "\n  ssh said: " + stderr)
        + "\n  Not registered yet? An admin runs: sail fde add <handle> --key \"<your pubkey>\"";
  }

  private static String unexpectedTicket(String stdout) {
    return "The box returned an unexpected enrollment ticket response:\n"
        + stdout.strip()
        + "\n  The box may be running an older sail — upgrade it with: sail host update";
  }

  private static ClientConfig requireThinClient() throws Exception {
    if (!(RuntimeMode.detect() instanceof RuntimeMode.Client client)) {
      throw new IllegalStateException(
          "'sail enroll' is the thin-client flow, and this box is not configured as one."
              + "\n  On the box itself, mint a ticket with: sail fde enroll <handle>"
              + "\n  On your laptop, point the CLI at the box first: sail init <host>");
    }
    if (!client.config().gatewayEnabled()) {
      throw new IllegalStateException(
          "Self-enrollment needs the FDE gateway — it is how the box knows who you are."
              + "\n  Set 'user: sail' in "
              + SailPaths.clientConfigPath());
    }
    return client.config();
  }
}
