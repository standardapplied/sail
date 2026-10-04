/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * What a watcher records beside a run when the run crosses one of its limits ({@link
 * AgentUnit#guardrailTriggerPath}): which limit, what was measured against it, what the watcher did
 * about it, and — as a person reads it — why the run ended. The record outlives the watcher and
 * needs no daemon, so a kill whose stop never reached the control plane is still a kill to whoever
 * reconstructs that stop, and {@code sail agent report} says what happened to the run.
 *
 * @param triggeredAt when the limit was found crossed
 * @param reason which limit: {@code max_duration} or {@code stall}
 * @param detail what was measured against it
 * @param action what the run's guardrails say to do: {@code stop}, {@code snapshot-and-stop} or
 *     {@code notify}
 * @param cause why the run ended, as its stop says it: {@code time limit (45m)}, {@code stall
 *     (20m)}; the bare {@code reason} for a record an older watcher wrote without one
 */
public record GuardrailTrigger(
    String triggeredAt, String reason, String detail, String action, String cause) {

  private static final String NOTIFY = "notify";

  /** The record of {@code triggered}, found at {@code at}. */
  public static GuardrailTrigger of(
      GuardrailChecker.GuardrailResult.Triggered triggered, Instant at) {
    return new GuardrailTrigger(
        at.toString(),
        triggered.reason(),
        triggered.detail(),
        triggered.action(),
        triggered.cause());
  }

  /** Whether {@code action} ends the run, as every action but {@code notify} does. */
  public static boolean stops(String action) {
    return !NOTIFY.equals(action);
  }

  /** Whether the watcher ended the run for this limit rather than only saying it was crossed. */
  public boolean stops() {
    return stops(action);
  }

  /** Writes this record beside the run {@code unit} names. */
  public void write(ShellExec shell, String project, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var map = new LinkedHashMap<String, Object>();
    map.put("triggered_at", triggeredAt);
    map.put("reason", reason);
    map.put("detail", detail);
    map.put("action", action);
    map.put("cause", cause);
    shell.exec(
        ContainerExec.asDevUser(
            project,
            List.of(
                "bash",
                "-c",
                "mkdir -p \"$(dirname \"$2\")\" && printf '%s' \"$1\" > \"$2\"",
                "bash",
                YamlUtil.dumpToString(map),
                unit.guardrailTriggerPath())));
  }

  /** The record beside the run {@code unit} names, or empty when no limit of its was crossed. */
  public static Optional<GuardrailTrigger> read(ShellExec shell, String project, AgentUnit unit)
      throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(ContainerExec.asDevUser(project, List.of("cat", unit.guardrailTriggerPath())));
    if (!result.ok() || Strings.isBlank(result.stdout())) {
      return Optional.empty();
    }
    var map = YamlUtil.parseMap(result.stdout());
    var reason = Objects.toString(map.get("reason"), null);
    return Optional.of(
        new GuardrailTrigger(
            Objects.toString(map.get("triggered_at"), null),
            reason,
            Objects.toString(map.get("detail"), null),
            Objects.toString(map.get("action"), null),
            Objects.toString(map.get("cause"), reason)));
  }
}
