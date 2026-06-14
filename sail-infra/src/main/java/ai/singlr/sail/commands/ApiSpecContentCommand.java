/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "content",
    description = "Get or set a spec's body and plan.",
    mixinStandardHelpOptions = true)
public final class ApiSpecContentCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Option(names = "--set", description = "Set content instead of reading it.")
  private boolean set;

  @Option(names = "--body-file", description = "Path to spec body markdown file.")
  private Path bodyFile;

  @Option(names = "--plan-file", description = "Path to plan markdown file.")
  private Path planFile;

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

    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      if (set) {
        var body = new LinkedHashMap<String, Object>();
        if (bodyFile != null) body.put("body", Files.readString(bodyFile));
        if (planFile != null) body.put("plan", Files.readString(planFile));
        var result = client.put("/v1/specs/" + specId + "/content", body);
        if (json) {
          System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        } else {
          System.out.println(Ansi.AUTO.string("  @|green ✓|@ Content updated for: " + specId));
        }
      } else {
        var result = client.get("/v1/specs/" + specId + "/content");
        if (json) {
          System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
          return;
        }
        var bodyText = (String) result.get("body");
        var planText = (String) result.get("plan");
        if (Strings.isNotBlank(bodyText)) {
          System.out.println(bodyText);
        }
        if (Strings.isNotBlank(planText)) {
          System.out.println();
          System.out.println(Ansi.AUTO.string("@|bold --- Plan ---|@"));
          System.out.println(planText);
        }
        if ((Strings.isBlank(bodyText)) && (Strings.isBlank(planText))) {
          System.out.println(Ansi.AUTO.string("  @|faint No content for " + specId + ".|@"));
        }
      }
    }
  }
}
