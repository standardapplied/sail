/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthorizedKeysSyncTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private ScriptedShellExecutor shell;
  private Path destination;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    var fde = new FdeStore(db).add("uday", null, null, "admin");
    new FdeSshKeyStore(db)
        .add(fde.id(), SshPublicKey.parse(TestSshKeys.ed25519("seed-1", "uday@mac")));
    shell = new ScriptedShellExecutor();
    destination = tempDir.resolve("sail-home/.ssh/authorized_keys");
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private AuthorizedKeysSync sync(boolean root) {
    return new AuthorizedKeysSync(destination, root, shell);
  }

  @Test
  void needsRootWithoutPrivileges() throws Exception {
    var outcome = sync(false).sync(db);
    assertInstanceOf(AuthorizedKeysSync.NeedsRoot.class, outcome);
    assertTrue(shell.invocations().isEmpty());
  }

  @Test
  void notProvisionedWhenSailSshDirectoryIsMissing() throws Exception {
    var outcome = sync(true).sync(db);
    assertInstanceOf(AuthorizedKeysSync.NotProvisioned.class, outcome);
    assertTrue(shell.invocations().isEmpty());
  }

  @Test
  void syncsRegisteredKeysWhenProvisioned() throws Exception {
    Files.createDirectories(destination.getParent());
    shell.onOk("install");

    var outcome = sync(true).sync(db);

    var synced = assertInstanceOf(AuthorizedKeysSync.Synced.class, outcome);
    assertEquals(1, synced.keyCount());
    assertEquals(destination, synced.destination());
    var invocation = shell.invocations().getFirst();
    assertTrue(invocation.contains("install -m 600 -o sail -g sail"));
    assertTrue(invocation.endsWith(destination.toString()));
  }

  @Test
  void surfacesInstallFailure() throws Exception {
    Files.createDirectories(destination.getParent());
    shell.onFail("install", "read-only filesystem");

    var thrown = assertThrows(IllegalStateException.class, () -> sync(true).sync(db));
    assertTrue(thrown.getMessage().contains(destination.toString()));
    assertTrue(thrown.getMessage().contains("read-only filesystem"));
  }

  @Test
  void rendersForcedCommandLinesWithoutWriting() {
    var content = sync(true).render(db);
    assertTrue(content.startsWith("# Managed by sail"));
    assertTrue(content.contains("_gateway --fde uday\",restrict ssh-ed25519 "));
    assertTrue(shell.invocations().isEmpty());
  }
}
