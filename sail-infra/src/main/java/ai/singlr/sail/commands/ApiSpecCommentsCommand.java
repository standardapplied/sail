/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "comments",
    description = "List a spec's conversation.",
    mixinStandardHelpOptions = true)
public final class ApiSpecCommentsCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Option(names = "--before", description = "Return messages before this message ID.")
  private String before;

  @Option(names = "--limit", defaultValue = "50", description = "Page size (max 100).")
  private int limit;

  @Mixin private SyncOptions syncOptions;
  @Mixin private ConnectionOptions connection;
  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidSpecId(specId);
    var path =
        new StringBuilder("/v1/rooms/").append(specId).append("/messages?limit=").append(limit);
    if (before != null) {
      path.append("&before=").append(URLEncoder.encode(before, StandardCharsets.UTF_8));
    }
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token(), syncOptions.noSync())) {
      var result = client.get(path.toString());
      System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
    }
  }
}
