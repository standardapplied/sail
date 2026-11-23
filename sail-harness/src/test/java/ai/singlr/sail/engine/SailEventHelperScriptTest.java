/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The helper script as a hook runs it: the real script, a real socket in place of the daemon's, and
 * what was posted to it read back. The socket lives in a directory of its own with a short path: a
 * Unix socket's address has no room for a test's nested temporary directory.
 */
class SailEventHelperScriptTest {

  @TempDir Path dir;

  private final List<Map<String, Object>> posted = new CopyOnWriteArrayList<>();
  private ServerSocketChannel daemon;
  private Thread serving;
  private Path socketDir;
  private Path script;

  @BeforeEach
  void aDaemonListens() throws IOException {
    socketDir = Files.createTempDirectory("sail");
    var socket = socketDir.resolve("api.sock");
    daemon = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    daemon.bind(UnixDomainSocketAddress.of(socket));
    serving = Thread.ofVirtual().start(this::serve);
    script = dir.resolve("sail-event.sh");
    Files.writeString(
        script,
        SailEventHelper.scriptContent()
            .replace(SailPaths.apiSocketContainerPath().toString(), socket.toString()));
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
  }

  @AfterEach
  void theDaemonStops() throws IOException, InterruptedException {
    daemon.close();
    assertTrue(serving.join(Child.PATIENCE), "the daemon's thread ended");
    Files.deleteIfExists(socketDir.resolve("api.sock"));
    Files.deleteIfExists(socketDir);
  }

  @Test
  void aToolCallFinishingIsPostedAsItIs() throws Exception {
    hook("agent_tool_finished", "{\"tool_name\":\"Bash\"}");

    assertEquals(1, posted.size());
    assertEquals("agent_tool_finished", posted.getFirst().get("type"));
    assertFalse(dataOf(posted.getFirst()).containsKey("batch"));
  }

  @Test
  void theMainAgentsBatchEndIsPostedAsAToolFinishThatMarksTheBatch() throws Exception {
    hook(
        SailEventHelper.BATCH_RESOLVED,
        "{\"hook_event_name\":\"PostToolBatch\",\"tool_calls\":[{\"agent_id\":\"not a key\"}]}");

    assertEquals(1, posted.size());
    assertEquals(
        "agent_tool_finished",
        posted.getFirst().get("type"),
        "readers that know nothing of batches take it for a call finishing");
    assertEquals(Boolean.TRUE, dataOf(posted.getFirst()).get("batch"));
    assertEquals("run-1", dataOf(posted.getFirst()).get("run_id"));
  }

  @Test
  void aSubagentsBatchEndIsNotPosted() throws Exception {
    hook(
        SailEventHelper.BATCH_RESOLVED,
        "{\"hook_event_name\":\"PostToolBatch\",\"agent_id\":\"a8a365bf\",\"tool_calls\":[]}");

    assertEquals(
        List.of(),
        posted,
        "a subagent's batch says nothing of the calls the main agent still waits on");
  }

  @Test
  void aBatchEndWhoseAgentCannotBeReadIsNotPosted() throws Exception {
    hook(SailEventHelper.BATCH_RESOLVED, "not json");

    assertEquals(
        List.of(), posted, "nothing says whose batch ended, so nobody is told a batch ended");
  }

  private void hook(String argument, String payload) throws Exception {
    var builder = new ProcessBuilder(script.toString(), argument);
    builder.environment().put("SAIL_RUN_CREDENTIAL", "credential");
    builder.environment().put("SAIL_RUN_ID", "run-1");
    var ran = Child.run(builder, payload, Child.PATIENCE);
    assertEquals(0, ran.exit(), ran.stdout() + ran.stderr());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> dataOf(Map<String, Object> event) {
    return (Map<String, Object>) event.get("data");
  }

  private void serve() {
    while (true) {
      try (var connection = daemon.accept()) {
        posted.add(YamlUtil.parseMap(bodyOf(connection)));
        connection.write(
            StandardCharsets.UTF_8.encode(
                "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
      } catch (ClosedChannelException closed) {
        return;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static String bodyOf(SocketChannel connection) throws IOException {
    var request = new StringBuilder();
    var buffer = ByteBuffer.allocate(8192);
    while (true) {
      var head = request.indexOf("\r\n\r\n");
      if (head >= 0) {
        var length =
            request
                .substring(0, head)
                .lines()
                .filter(line -> line.toLowerCase().startsWith("content-length:"))
                .map(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim()))
                .findFirst()
                .orElse(0);
        if (request.length() - head - 4 >= length) {
          return request.substring(head + 4);
        }
      }
      buffer.clear();
      if (connection.read(buffer) < 0) {
        return "";
      }
      request.append(StandardCharsets.UTF_8.decode(buffer.flip()));
    }
  }
}
