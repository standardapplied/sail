/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * This host's Incus, as the Incus lane's tests reach it, keeping evidence when a workspace's make
 * fails for want of an address, and only then. The make's prepare says "<instance> got no address
 * on eth0"; before that answer goes back, and so before the half-made workspace is deleted, this
 * saves what the box saw at that moment: the host's load, every managed bridge's leases, the
 * instance's {@code incus info} and expanded config, and the bridge's dnsmasq lines in the journal
 * since this JVM started. It goes to {@code target/incus-evidence/<instance>-<millis>.txt}, which
 * the lane keeps with the test reports of a failed job.
 */
final class NoAddressEvidence implements ShellExec {

  private static final Pattern NO_ADDRESS = Pattern.compile("(\\S+) got no address on eth0");
  private static final Instant SINCE = Instant.now();
  private static final Path DIR = Path.of("target", "incus-evidence");

  private final ShellExec incus;

  NoAddressEvidence(ShellExec incus) {
    this.incus = incus;
  }

  @Override
  public Result exec(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    return kept(incus.exec(command));
  }

  @Override
  public Result exec(List<String> command, Path workDir, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return kept(incus.exec(command, workDir, timeout));
  }

  @Override
  public boolean isDryRun() {
    return incus.isDryRun();
  }

  @Override
  public Result run(List<String> command, Consumer<String> lines)
      throws IOException, InterruptedException {
    return incus.run(command, lines);
  }

  @Override
  public InputStream stream(List<String> command) throws IOException {
    return incus.stream(command);
  }

  @Override
  public Result execWithInput(List<String> command, String input, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return incus.execWithInput(command, input, timeout);
  }

  @Override
  public boolean asksForCredentials(String url) {
    return incus.asksForCredentials(url);
  }

  @Override
  public ShellExec observer() {
    return incus.isDryRun() ? incus.observer() : this;
  }

  private Result kept(Result result) throws InterruptedException {
    var refused = NO_ADDRESS.matcher(result.stderr());
    if (!result.ok() && refused.find()) {
      save(refused.group(1), result.stderr());
    }
    return result;
  }

  private void save(String instance, String refusal) throws InterruptedException {
    var said = new StringBuilder("== the refusal\n").append(refusal).append('\n');
    said.append("== /proc/loadavg\n").append(read(Path.of("/proc/loadavg")));
    for (var network : bridges()) {
      said.append(asked(List.of("incus", "network", "list-leases", network)));
    }
    said.append(asked(List.of("incus", "info", instance)));
    said.append(asked(List.of("incus", "config", "show", instance, "--expanded")));
    var journal =
        List.of(
            "journalctl",
            "--no-pager",
            "-o",
            "short-iso-precise",
            "--since",
            "@" + SINCE.getEpochSecond(),
            "-t",
            "dnsmasq",
            "-t",
            "dnsmasq-dhcp");
    said.append(asked(journal));
    var sudo = new ArrayList<>(List.of("sudo", "-n"));
    sudo.addAll(journal);
    said.append(asked(sudo));
    try {
      Files.createDirectories(DIR);
      Files.writeString(DIR.resolve(instance + "-" + System.currentTimeMillis() + ".txt"), said);
    } catch (IOException e) {
      System.err.println("[incus-evidence] could not keep the evidence of " + instance + ": " + e);
    }
  }

  private List<String> bridges() throws InterruptedException {
    try {
      var listed = incus.exec(List.of("incus", "network", "list", "--format", "json"));
      if (!listed.ok()) {
        return List.of();
      }
      return YamlUtil.parseList(listed.stdout()).stream()
          .filter(network -> Boolean.TRUE.equals(network.get("managed")))
          .map(network -> String.valueOf(network.get("name")))
          .toList();
    } catch (IOException | TimeoutException | RuntimeException e) {
      return List.of();
    }
  }

  private String asked(List<String> command) throws InterruptedException {
    var head = "== " + String.join(" ", command) + "\n";
    try {
      var result = incus.exec(command);
      return head + "exit " + result.exitCode() + "\n" + result.stdout() + result.stderr() + "\n";
    } catch (IOException | TimeoutException e) {
      return head + "could not ask: " + e + "\n";
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      return "could not read: " + e + "\n";
    }
  }
}
