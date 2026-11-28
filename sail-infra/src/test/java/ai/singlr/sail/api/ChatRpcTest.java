/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ChatBench.as;
import static ai.singlr.sail.api.RpcChat.notification;
import static ai.singlr.sail.api.RpcChat.object;
import static ai.singlr.sail.api.RpcChat.request;
import static ai.singlr.sail.api.RpcChat.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.ProjectStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A second harness fits the host as it is: one whose wire is JSON-RPC ({@link RpcChat}), with a
 * handshake before the first message, request ids, and a turn that a response ends, runs a turn
 * through the same host, routes and log as Claude Code does: the message, a call, a permission
 * asked and answered, its text and the turn's end. The agent stands in, answering each line Sail
 * writes as such an agent would.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ChatRpcTest {

  private static final String SESSION = "rpc-7f3a";

  @TempDir Path dir;
  private ChatBench bench;
  private ChatHost host;

  @BeforeEach
  void setUp() {
    var claude = Harnesses.of("claude-code");
    var rpc =
        (Harness)
            Proxy.newProxyInstance(
                Harness.class.getClassLoader(),
                new Class<?>[] {Harness.class},
                (proxy, called, args) ->
                    switch (called.getName()) {
                      case "chat" -> Optional.of(new RpcChat());
                      case "displayName" -> "Rpc Agent";
                      default -> invoke(called, claude, args);
                    });
    bench = new ChatBench(dir);
    host =
        bench.restarted(
            new ChatLaunch(
                bench.door,
                bench.fdes,
                ProjectReader.ofCatalog(new ProjectStore(bench.db)),
                bench.processes,
                bench.processes.container(),
                TestAuth.roles(bench.db, new SyncConfig("main", null, "ada", "box")),
                name -> rpc));
  }

  @AfterEach
  void tearDown() {
    host.close();
    bench.close();
  }

  private static Object invoke(Method called, Harness real, Object[] args) throws Throwable {
    try {
      return called.invoke(real, args);
    } catch (InvocationTargetException thrown) {
      throw thrown.getCause();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new AssertionError("the test never let the agent open");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  /**
   * The agent answers {@code initialize} only once the message is logged, so the log's order is the
   * test's: the session is named after the message that waited for it.
   */
  @Test
  void aJsonRpcHarnessRunsATurnThroughTheHostAsItIs() throws Exception {
    var opened = new CountDownLatch(1);
    var asked = new CountDownLatch(1);
    var ended = new CountDownLatch(1);
    bench.processes.onStart(
        fake -> {
          fake.onClose(fake::exitsIfRunning);
          fake.onWrite(
              line -> {
                if (line.contains("\"method\": \"initialize\"")) {
                  Thread.ofVirtual()
                      .start(
                          () -> {
                            awaitQuietly(opened);
                            fake.says(
                                response(
                                    RpcChat.OPENING,
                                    object("session", SESSION, "mode", "default")));
                          });
                } else if (line.contains("\"method\": \"prompt\"")) {
                  fake.says(
                      notification(
                          "update", object("kind", "tool", "call", "t1", "command", "date")),
                      request(
                          "ask-1",
                          "permission",
                          object("call", "t1", "tool", "run", "input", object("command", "date"))));
                  asked.countDown();
                } else if (line.contains("\"id\": \"ask-1\"")) {
                  fake.says(
                      notification(
                          "update",
                          object("kind", "done", "call", "t1", "output", "Sat Nov 28 2026")),
                      notification("update", object("kind", "text", "text", "It is Saturday.")),
                      response("turn-1", object("stop", "end_turn")));
                  ended.countDown();
                }
              });
        });
    var session =
        as("sam", () -> host.open("acme", "try", "claude-code", "default", false)).session();

    var sent = as("sam", () -> host.send(session, "What day is it?"));
    opened.countDown();
    assertTrue(asked.await(20, TimeUnit.SECONDS), "the agent was never prompted");
    var fake = bench.processes.latest();
    fake.settle();
    var answered =
        as("sam", () -> host.answers().answer(session, "ask-1", Map.of("decision", "allow")));
    assertTrue(ended.await(20, TimeUnit.SECONDS), "the agent was never answered");
    fake.settle();

    assertEquals(new ChatHost.Sent(1, false), sent);
    assertEquals(Map.of("seq", 5L), answered.toMap());
    assertEquals(
        List.of(
            "{\"jsonrpc\": \"2.0\", \"id\": \"open\", \"method\": \"initialize\", \"params\":"
                + " {\"protocol\": 1}}",
            "{\"jsonrpc\": \"2.0\", \"id\": \"turn-1\", \"method\": \"prompt\", \"params\":"
                + " {\"session\": \"rpc-7f3a\", \"text\": \"What day is it?\"}}",
            "{\"jsonrpc\": \"2.0\", \"id\": \"ask-1\", \"result\": {\"outcome\": \"allow\"}}"),
        fake.written,
        "opened first, prompted once the session was named, answered by the request's id");
    assertEquals(
        List.of(
            "message",
            "session",
            "tool",
            "permission",
            "answered",
            "tool_done",
            "text",
            "turn_ended"),
        bench.kinds(session));
    var items = as("sam", () -> host.read(session, 0)).items();
    assertEquals(
        List.of("ask-1", "t1", "command", "date"),
        List.of(
            items.get(3).get("id"),
            items.get(3).get("call"),
            items.get(3).get("kind"),
            items.get(3).get("words")));
    assertEquals("It is Saturday.", items.get(6).get("text"));
    var chat = bench.store.find(session).orElseThrow();
    assertEquals(List.of(SESSION, "default"), List.of(chat.conversation(), chat.mode()));
    assertFalse(chat.turnOpen(), "the response to its prompt ended the turn");
    assertEquals(List.of("rpc-agent", "--stdio"), bench.processes.starts.getFirst().command());
  }
}
