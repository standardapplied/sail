/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoxCredentialFileTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private BoxCredentialStore store;
  private Path dir;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("box.db"));
    new SchemaManager(db).migrate();
    store = new BoxCredentialStore(db);
    dir = tempDir.resolve("run");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void ensureMintsWritesAndReportsProvisioned() throws IOException {
    var outcome = BoxCredentialFile.ensure(store, "uday", dir);

    assertEquals(BoxCredentialFile.Outcome.PROVISIONED, outcome);
    var file = dir.resolve(BoxCredentialFile.FILE_NAME);
    var plaintext = Files.readString(file).strip();
    assertEquals(Optional.of("uday"), store.resolve(plaintext));
    var permissions = Files.getPosixFilePermissions(file);
    assertTrue(permissions.contains(PosixFilePermission.OTHERS_READ));
    assertTrue(!permissions.contains(PosixFilePermission.OTHERS_WRITE));
  }

  @Test
  void ensureIsIdempotentWhenFileAndRowAgree() throws IOException {
    BoxCredentialFile.ensure(store, "uday", dir);
    var before = Files.readString(dir.resolve(BoxCredentialFile.FILE_NAME));

    var outcome = BoxCredentialFile.ensure(store, "uday", dir);

    assertEquals(BoxCredentialFile.Outcome.ALREADY_PRESENT, outcome);
    assertEquals(before, Files.readString(dir.resolve(BoxCredentialFile.FILE_NAME)));
  }

  @Test
  void ensureHealsAGarbageFile() throws IOException {
    BoxCredentialFile.ensure(store, "uday", dir);
    Files.writeString(dir.resolve(BoxCredentialFile.FILE_NAME), "not-a-credential");

    var outcome = BoxCredentialFile.ensure(store, "uday", dir);

    assertEquals(BoxCredentialFile.Outcome.PROVISIONED, outcome);
    var plaintext = Files.readString(dir.resolve(BoxCredentialFile.FILE_NAME)).strip();
    assertEquals(Optional.of("uday"), store.resolve(plaintext));
  }

  @Test
  void ensureRotatesWhenTheHandleChanges() throws IOException {
    BoxCredentialFile.ensure(store, "uday", dir);
    var old = Files.readString(dir.resolve(BoxCredentialFile.FILE_NAME)).strip();

    var outcome = BoxCredentialFile.ensure(store, "sumesh", dir);

    assertEquals(BoxCredentialFile.Outcome.PROVISIONED, outcome);
    assertEquals(Optional.empty(), store.resolve(old));
    var fresh = Files.readString(dir.resolve(BoxCredentialFile.FILE_NAME)).strip();
    assertEquals(Optional.of("sumesh"), store.resolve(fresh));
  }

  @Test
  void ensureCreatesTheDirectoryWhenAbsent() throws IOException {
    var nested = tempDir.resolve("deep").resolve("run");

    BoxCredentialFile.ensure(store, "uday", nested);

    assertTrue(Files.isRegularFile(nested.resolve(BoxCredentialFile.FILE_NAME)));
  }
}
