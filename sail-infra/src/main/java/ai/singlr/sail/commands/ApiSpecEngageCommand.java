/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.EngagementMode;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Adds an agent to a spec's room: it joins the conversation and answers every human message until
 * removed. Full access is the default — conversations produce artifacts; {@code --read-only} is the
 * explicit narrow choice, offered only where the harness enforces it. The member works in the
 * spec's own workspace. A thin client of {@code POST /v1/rooms/{id}/members}; the server's refusals
 * render verbatim.
 */
@Command(
    name = "engage",
    description =
        "Add an agent to this spec's room — it answers every message until"
            + " 'spec disengage' removes it. Full access by default; --read-only for the enforced"
            + " narrow mode.",
    mixinStandardHelpOptions = true)
public final class ApiSpecEngageCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Option(names = "--agent", required = true, description = "Agent to add: claude-code, codex.")
  private String agent;

  @Option(names = "--model", description = "Model override for the agent.")
  private String model;

  @Option(
      names = "--read-only",
      description = "Enforced read-only conversation (claude-code only today).")
  private boolean readOnly;

  @Mixin private SyncOptions syncOptions;

  @Mixin private ConnectionOptions connection;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidSpecId(specId);
    var config = connection.resolve();
    var body = new LinkedHashMap<String, Object>();
    body.put("agent", agent);
    body.put("mode", (readOnly ? EngagementMode.READ_ONLY : EngagementMode.FULL).wire());
    if (Strings.isNotBlank(model)) {
      body.put("model", model);
    }
    try (var client = new SailApiClient(config.serverUrl(), config.token(), syncOptions.noSync())) {
      var result = client.post("/v1/rooms/" + specId + "/members", Map.copyOf(body));

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Engaged "
                  + agent
                  + " ("
                  + result.get("mode")
                  + ") in spec '"
                  + specId
                  + "' — it now answers every message in this room."));
    }
  }
}
