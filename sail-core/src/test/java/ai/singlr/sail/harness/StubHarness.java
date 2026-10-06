/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A harness that is neither of the two sail ships, for proving what holds of any harness: it has a
 * name, a hook file and the port its login needs forwarded (or none), and answers everything else
 * as plainly as it can.
 */
public record StubHarness(String yamlName, HookFile hooks, OptionalInt loginTunnelPort)
    implements Harness {

  public StubHarness {
    Objects.requireNonNull(loginTunnelPort, "loginTunnelPort");
  }

  /**
   * A harness named {@code yamlName} that runs every required hook at one event and logs in through
   * {@code loginTunnelPort}, or without a tunnel when that is empty.
   */
  public static StubHarness loggingInThrough(String yamlName, OptionalInt loginTunnelPort) {
    return new StubHarness(
        yamlName,
        new HookFile(
            "/home/dev/." + yamlName + "/hooks.json",
            new LinkedHashMap<>(),
            List.of(new HookFile.Group("Any", null, List.copyOf(SailHook.required())))),
        loginTunnelPort);
  }

  @Override
  public String binaryName() {
    return yamlName;
  }

  @Override
  public String displayName() {
    return yamlName;
  }

  @Override
  public String installCommand() {
    return "install " + yamlName;
  }

  @Override
  public String homeContextPath() {
    return "." + yamlName + "/CONTEXT.md";
  }

  @Override
  public String skillsDir() {
    return "." + yamlName + "/skills/";
  }

  @Override
  public String languageRulePath(String name) {
    return skillsDir() + name + ".md";
  }

  @Override
  public String languageRule(String name, List<String> paths, String body) {
    return body;
  }

  @Override
  public String headless(Launch launch) {
    return yamlName + launch.resume().map(id -> " resume " + id).orElse("") + " " + launch.task();
  }

  @Override
  public Optional<String> readOnlyRefusal() {
    return Optional.of(yamlName + " has no read-only session.");
  }

  @Override
  public String interactive(boolean fullPermissions) {
    return yamlName;
  }

  @Override
  public String attach(String sessionId) {
    return sessionId != null ? yamlName + " " + Harness.requireSafeSessionId(sessionId) : yamlName;
  }

  @Override
  public boolean honoursReasoningEffort() {
    return true;
  }

  @Override
  public Optional<String> interactiveTip() {
    return Optional.empty();
  }
}
