/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Invites an agent into a spec's room: one choice, read only (default) or {@code --full}. The
 * server owns the whole launch — mode support, reservation, the full lane's pre-launch snapshot —
 * so this command is a thin client of {@code POST /v1/specs/{id}/invite} and renders the server's
 * refusals verbatim.
 */
@Command(
    name = "invite",
    description =
        "Invite an agent into this spec's room — read only by default, --full to let it change"
            + " specs and code (snapshots first).",
    mixinStandardHelpOptions = true)
public final class ApiSpecInviteCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Option(names = "--agent", required = true, description = "Agent to invite: claude-code, codex.")
  private String agent;

  @Option(names = "--model", description = "Model override for the invited agent.")
  private String model;

  @Option(
      names = "--full",
      description = "Full access: spec CLI writes and code changes, paid with a snapshot.")
  private boolean full;

  @Mixin private SyncOptions syncOptions;

  @Mixin private ConnectionOptions connection;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidSpecId(specId);
    var config = connection.resolve();
    var body = new LinkedHashMap<String, Object>();
    body.put("agent", agent);
    if (Strings.isNotBlank(model)) {
      body.put("model", model);
    }
    body.put("full", full);
    try (var client = new SailApiClient(config.serverUrl(), config.token(), syncOptions.noSync())) {
      var result = client.post("/v1/specs/" + specId + "/invite", Map.copyOf(body));

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }
      var mode = full ? "full access" : "read only";
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Invited "
                  + agent
                  + " ("
                  + mode
                  + ") into spec '"
                  + specId
                  + "' — run "
                  + result.get("run_id")
                  + " as "
                  + result.get("principal")
                  + "."));
      var snapshot = result.get("snapshot");
      if (snapshot != null && !snapshot.toString().isBlank()) {
        System.out.println("    Snapshot: " + snapshot);
      }
    }
  }
}
