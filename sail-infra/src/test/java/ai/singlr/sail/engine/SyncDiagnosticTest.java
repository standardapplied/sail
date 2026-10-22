/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncTransportException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A sync whose ssh dies before main replies says why, in ssh's own words. */
class SyncDiagnosticTest {

  @TempDir Path home;

  private static final String AUTHORISE =
      "sudo sail fde add mady --role member --key \"ssh-ed25519 AAAA sail-sync:mady\""
          + " --main-host-key SHA256:pinned";

  private String syncFailure(String sshSaid) throws Exception {
    return OperatorCommand.SUDO.render(failure(sshSaid));
  }

  private SyncTransportException failure(String sshSaid) throws Exception {
    return failure(closed(sshSaid, handle -> Optional.empty()));
  }

  private SyncTransportException failure(SyncOperations.Channel channel) throws Exception {
    try (var db = Sqlite.open(home.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var config = new SyncConfig("node", "sail@main", "mady", "box-1");
      var operations = new SyncOperations(db, "node", home, () -> config, target -> channel);
      return assertThrows(
          SyncTransportException.class, () -> operations.sync(new SyncRequest(null)));
    }
  }

  private static SyncOperations.Channel closed(
      String sshSaid, Function<String, Optional<String>> authorisation) {
    return new SyncOperations.Channel() {
      @Override
      public Optional<String> authorisation(String handle) {
        return authorisation.apply(handle);
      }

      @Override
      public InputStream reader() {
        return new ByteArrayInputStream(new byte[0]);
      }

      @Override
      public OutputStream writer() {
        return new ByteArrayOutputStream();
      }

      @Override
      public String diagnostic() {
        return sshSaid;
      }

      @Override
      public void close() {}
    };
  }

  @Test
  void anUnpinnedOrChangedHostKeyNamesTheRemedy() throws Exception {
    assertEquals(
        "hello: Sync channel closed before main replied. ssh said: Host key verification failed. If"
            + " main was rebuilt from a copy of its database, remove the old key with 'sudo"
            + " ssh-keygen -R main', re-run 'sudo sail init --main sail@main' and compare the"
            + " fingerprint it prints with main's ('sudo ssh-keygen -lf"
            + " /etc/ssh/ssh_host_ed25519_key.pub' on main); otherwise something between this box"
            + " and main is answering for it.",
        syncFailure("Host key verification failed."));
    assertEquals(
        "hello: Sync channel closed before main replied. ssh said: Host key verification failed. If"
            + " main was rebuilt from a copy of its database, remove the old key with 'ssh-keygen"
            + " -R main', re-run 'sail init --main sail@main' and compare the fingerprint it prints"
            + " with main's ('sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub' on main);"
            + " otherwise something between this box and main is answering for it.",
        OperatorCommand.PLAIN.render(failure("Host key verification failed.")));
  }

  @Test
  void aKeyMainHasNotAuthorisedNamesTheLineToSendItsOperator() throws Exception {
    var refused =
        failure(
            closed(
                "sail@10.128.0.25: Permission denied (publickey).",
                handle -> Optional.of(AUTHORISE.replace("mady", handle))));

    assertEquals("unreachable", refused.kind());
    assertEquals(
        "hello: Sync channel closed before main replied. Main has not authorised this box's key"
            + " yet. Send its operator this line, then sync again:\n  "
            + AUTHORISE
            + "\n  ssh said: sail@10.128.0.25: Permission denied (publickey).",
        OperatorCommand.PLAIN.render(refused));
  }

  @Test
  void anotherFailureNeverAsksTheChannelForTheLine() throws Exception {
    var refused =
        failure(
            closed(
                "Connection timed out",
                handle -> {
                  throw new AssertionError("only a refused key is authorised");
                }));

    assertEquals(
        "hello: Sync channel closed before main replied. ssh said: Connection timed out",
        OperatorCommand.PLAIN.render(refused));
  }

  @Test
  void aChannelSaysNoLineUnlessItCan() {
    assertEquals(Optional.empty(), closedQuietly().authorisation("mady"));
  }

  @Test
  void anyOtherLastLineIsQuotedAlone() throws Exception {
    assertEquals(
        "hello: Sync channel closed before main replied. ssh said: Permission denied (publickey).",
        syncFailure("Permission denied (publickey)."));
  }

  @Test
  void aTransportWithNothingToSayFailsAsBefore() throws Exception {
    assertEquals("hello: Sync channel closed before main replied.", syncFailure(""));
  }

  @Test
  void aChannelWithNoDiagnosticSaysNothing() {
    assertEquals("", closedQuietly().diagnostic());
  }

  private static SyncOperations.Channel closedQuietly() {
    return new SyncOperations.Channel() {
      @Override
      public InputStream reader() {
        return InputStream.nullInputStream();
      }

      @Override
      public OutputStream writer() {
        return OutputStream.nullOutputStream();
      }

      @Override
      public void close() {}
    };
  }

  @Test
  void sshsLastStderrLineIsItsDiagnosticOnceItExits() throws Exception {
    var ssh =
        new ProcessBuilder(
                List.of(
                    "sh",
                    "-c",
                    "echo 'Warning: no such host' >&2; echo 'Host key verification failed.' >&2;"
                        + " echo >&2; exit 255"))
            .start();
    try (var channel = new SshSyncChannel(ssh, handle -> Optional.empty())) {
      assertEquals("Host key verification failed.", channel.diagnostic());
    }
  }
}
