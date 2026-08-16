/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "comment",
    description = "Post a message to a spec's conversation.",
    mixinStandardHelpOptions = true)
public final class ApiSpecCommentCommand implements Runnable {

  @Parameters(index = "0", description = "Spec ID.")
  private String specId;

  @Option(names = "--body", required = true, description = "Message text, or - to read stdin.")
  private String body;

  @Option(names = "--reply-to", description = "Message ID to reply to.")
  private String replyTo;

  @Option(names = "--question", description = "Mark this message as a question that needs a reply.")
  private boolean question;

  @Mixin private SyncOptions syncOptions;
  @Mixin private ConnectionOptions connection;
  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidSpecId(specId);
    var text =
        "-".equals(body) ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8) : body;
    var request = new LinkedHashMap<String, Object>();
    request.put("body", text);
    if (replyTo != null) {
      request.put("reply_to", replyTo);
    }
    if (question) {
      request.put("question", true);
    }
    var config = connection.resolve();
    try (var client = new SailApiClient(config.serverUrl(), config.token(), syncOptions.noSync())) {
      Map<String, Object> result = client.post("/v1/specs/" + specId + "/messages", request);
      System.out.println(YamlUtil.dumpJson(new LinkedHashMap<>(result)));
    }
  }
}
