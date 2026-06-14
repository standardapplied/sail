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
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "show",
    description = "Show a spec's metadata and content.",
    mixinStandardHelpOptions = true)
public final class ApiSpecShowCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Mixin private ConnectionOptions connection;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  @SuppressWarnings("unchecked")
  private void execute() throws Exception {
    NameValidator.requireValidSpecId(specId);
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token())) {
      var result = client.get("/v1/specs/" + specId);

      if (json) {
        System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
        return;
      }

      var spec = (Map<String, Object>) result.get("spec");
      System.out.println(Ansi.AUTO.string("  @|bold Spec:|@ " + spec.get("id")));
      if (spec.get("project") != null) {
        System.out.println(Ansi.AUTO.string("  @|bold Project:|@ " + spec.get("project")));
      }
      System.out.println(Ansi.AUTO.string("  @|bold Title:|@ " + spec.get("title")));
      System.out.println(Ansi.AUTO.string("  @|bold Status:|@ " + spec.get("status")));
      if (spec.get("assignee") != null) {
        System.out.println(Ansi.AUTO.string("  @|bold Assignee:|@ " + spec.get("assignee")));
      }
      var deps = (List<String>) spec.getOrDefault("depends_on", List.of());
      if (!deps.isEmpty()) {
        System.out.println(Ansi.AUTO.string("  @|bold Depends On:|@ " + String.join(", ", deps)));
      }
      var repos = (List<String>) spec.getOrDefault("repos", List.of());
      if (!repos.isEmpty()) {
        System.out.println(Ansi.AUTO.string("  @|bold Repos:|@ " + String.join(", ", repos)));
      }
      if (spec.get("branch") != null) {
        System.out.println(Ansi.AUTO.string("  @|bold Branch:|@ " + spec.get("branch")));
      }

      var body = (String) result.get("body");
      if (Strings.isNotBlank(body)) {
        System.out.println();
        System.out.println(body);
      }
    }
  }
}
