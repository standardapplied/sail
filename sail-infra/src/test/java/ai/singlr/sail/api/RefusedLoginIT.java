/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A refused login is said in the spec's room by a real box's finish, over a real workspace (RL1,
 * RL4): each harness's fake binary writes, where that harness writes its report, the lines the real
 * harness wrote when it was given a fake credential (Claude Code 2.1.322 refusing a token on its
 * stdout, codex-cli 0.163.0 refusing a key in its rollout), and exits 1; the box's own run tracker
 * then reads that report out of the workspace and says the sentence once. Runs only under the
 * {@code integration} profile against a real incus daemon; skips elsewhere.
 */
class RefusedLoginIT extends AbstractIncusIT {

  private static final String PROJECT = "sail-it-refused";
  private static final String HANDLE = "it-node";
  private static final Actor OPERATOR = new Actor(HANDLE, Role.ADMIN, Actor.Lane.CLI);
  private static final String ROLLOUT = "/home/dev/.codex/sessions/rollout-it.jsonl";

  /** Prints what Claude Code printed refusing a fake token, then exits as it did. */
  private static final String FAKE_CLAUDE =
      """
      #!/bin/sh
      cat /usr/local/share/sail-it/claude-refused.jsonl
      : > "$HOME/.sail/done-$SAIL_RUN_ID"
      exit 1
      """;

  /** Writes the rollout codex wrote refusing a fake key, then exits as it did. */
  private static final String FAKE_CODEX =
      """
      #!/bin/sh
      mkdir -p "$HOME/.codex/sessions"
      cp /usr/local/share/sail-it/codex-refused.jsonl %s
      echo "ERROR: unexpected status 401 Unauthorized"
      : > "$HOME/.sail/done-$SAIL_RUN_ID"
      exit 1
      """
          .formatted(ROLLOUT);

  private static final String YAML =
      """
      name: %s
      repos:
        - url: https://example.invalid/app.git
          path: app
        - url: https://example.invalid/web.git
          path: web
      agent:
        type: claude-code
      """
          .formatted(PROJECT);

  private Path stateDir;

  @BeforeEach
  void provision() throws Exception {
    ensureIncusOrSkip();
    stateDir = Files.createTempDirectory("refused-login-it");
    Files.writeString(stateDir.resolve("sail.yaml"), YAML);
    specBox(
        TestProjects.reading(stateDir.resolve("sail.yaml")).require(PROJECT),
        template -> {
          var share = exec(template, List.of("mkdir", "-p", "/usr/local/share/sail-it"));
          assertTrue(share.ok(), share.stderr());
          for (var recorded :
              Map.of(
                      "claude-refused.jsonl", "claude-code-refused-token.jsonl",
                      "codex-refused.jsonl", "codex-refused-key.jsonl")
                  .entrySet()) {
            ContainerFilePush.push(
                shell,
                template,
                "/usr/local/share/sail-it/" + recorded.getKey(),
                fixture(recorded.getValue()),
                List.of("--mode", "0644"));
          }
          ContainerFilePush.push(
              shell, template, "/usr/local/bin/claude", FAKE_CLAUDE, List.of("--mode", "0755"));
          ContainerFilePush.push(
              shell, template, "/usr/local/bin/codex", FAKE_CODEX, List.of("--mode", "0755"));
        });
  }

  @AfterEach
  void cleanup() {
    removeSpecBox(PROJECT);
    if (stateDir != null) {
      deleteRecursively(stateDir);
    }
  }

  @Test
  void eachHarnessRefusingItsFakeLoginIsSaidOnceInTheSpecsRoomByTheBoxsOwnFinish()
      throws Exception {
    try (var db = Sqlite.open(stateDir.resolve(DateTimeUtils.newId() + ".db"))) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, PROJECT);
      var fdes = new FdeStore(db);
      var id = fdes.add(HANDLE, null, HANDLE + "@sail.invalid", "admin").id();
      fdes.logins().store(id, Harnesses.of("claude-code"), null, "token", "sk-ant-oat01-it-fake");
      fdes.logins().store(id, Harnesses.of("codex"), null, "api-key", "sk-proj-it-fake-0000");
      var specs = new SpecStore(db);
      var runs = new RunStore(db);
      var messages = new MessageStore(db);
      seedSpec(specs, "spec-claude", "claude-code", "app");
      seedSpec(specs, "spec-codex", "codex", "web");
      var ops =
          new DispatchOperations(
                  shell,
                  TestProjects.reading(stateDir.resolve("sail.yaml")),
                  specs,
                  new ReviewStore(db),
                  runs,
                  fdes,
                  event -> {},
                  new WatcherSpawner(shell, (command, logPath) -> 4242L),
                  TestWorkspaces.over(shell),
                  DispatchOperations.shellLauncher(shell),
                  DispatchOperations.Listener.NONE,
                  SessionYield.NONE)
              .useMessages(messages);
      var tracker = new RunTracker(runs, ops.runReports(), SyncScheduler.disabled(), () -> HANDLE);

      for (var spec : List.of("spec-claude", "spec-codex")) {
        var dispatched =
            assertInstanceOf(
                DispatchOperations.Dispatched.class,
                Acting.by(
                    OPERATOR,
                    () ->
                        ops.dispatch(
                            PROJECT,
                            new DispatchOperations.Request(spec, "background", false, null, false),
                            HANDLE)));
        var workspace = dispatched.workspace().instance();
        var done = "/home/dev/.sail/done-" + dispatched.runId();
        var ended =
            shell.exec(
                ContainerExec.asDevUser(
                    workspace,
                    List.of(
                        "timeout",
                        "60",
                        "sh",
                        "-c",
                        "until test -e " + done + "; do sleep 0.2; done")));
        assertTrue(ended.ok(), spec + "'s fake harness never ran: " + ended.stderr());
        if (spec.equals("spec-codex")) {
          Acting.system(
              () -> runs.recordSession(dispatched.runId(), "thread-it", "startup", ROLLOUT));
        }
        var stop =
            RunStops.of(
                Event.WellKnownData.SOURCE_WATCHER,
                PROJECT,
                spec,
                spec.equals("spec-codex") ? "codex" : "claude-code",
                dispatched.runId(),
                "build",
                1,
                null);
        tracker.onEvent(stop);
        tracker.onEvent(stop);
      }

      assertEquals(
          List.of(
              "Claude Code refused it-node's login 'default' (long-lived token), so its run"
                  + " stopped. Run claude setup-token on a machine where Claude Code is signed in,"
                  + " then sail agent login claude-code. Then build it again: sail spec dispatch"
                  + " -p sail-it-refused --spec spec-claude --restart."),
          said(messages, "spec-claude"));
      assertEquals(
          List.of(
              "Codex CLI refused it-node's login 'default' (API key), so its run stopped. Create"
                  + " an API key at platform.openai.com/api-keys, then sail agent login codex. Then"
                  + " build it again: sail spec dispatch -p sail-it-refused --spec spec-codex"
                  + " --restart."),
          said(messages, "spec-codex"));
    }
  }

  private static List<String> said(MessageStore messages, String room) {
    return messages.list(room, null, 50).stream()
        .filter(message -> MessageStore.SAIL_AUTHOR.equals(message.author()))
        .map(MessageStore.MessageRow::body)
        .toList();
  }

  private static String fixture(String name) {
    try (var in = RefusedLoginIT.class.getResourceAsStream("/ai/singlr/sail/harness/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void seedSpec(SpecStore store, String id, String agent, String repo) {
    Acting.as(
        HANDLE,
        () -> {
          store.create(
              new SpecStore.SpecRow(
                  id,
                  PROJECT,
                  "Title " + id,
                  SpecStatus.PENDING,
                  HANDLE,
                  agent,
                  null,
                  null,
                  null,
                  0,
                  HANDLE,
                  null,
                  null,
                  HANDLE,
                  List.of(),
                  List.of(repo)));
          store.setContent(id, "Do " + id, "");
        });
  }
}
