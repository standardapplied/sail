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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A sync whose ssh dies before main replies says why, in ssh's own words. */
class SyncDiagnosticTest {

  @TempDir Path home;

  private String syncFailure(String sshSaid) throws Exception {
    try (var db = Sqlite.open(home.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var config = new SyncConfig("node", "sail@main", "mady", "box-1");
      var operations =
          new SyncOperations(db, "node", home, () -> config, target -> closed(sshSaid));
      return assertThrows(
              SyncTransportException.class, () -> operations.sync(new SyncRequest(null)))
          .getMessage();
    }
  }

  private static SyncOperations.Channel closed(String sshSaid) {
    return new SyncOperations.Channel() {
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
        "hello: Sync channel closed before main replied. ssh said: Host key verification failed."
            + " If main was rebuilt, remove the old key with 'sudo ssh-keygen -R main' and re-run"
            + " 'sudo sail init --main sail@main'; otherwise something between this box and main"
            + " is answering for it.",
        syncFailure("Host key verification failed."));
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
    var channel =
        new SyncOperations.Channel() {
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

    assertEquals("", channel.diagnostic());
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
    try (var channel = new SshSyncChannel(ssh)) {
      assertEquals("Host key verification failed.", channel.diagnostic());
    }
  }
}
