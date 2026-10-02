/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ContentFixtures;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MaterializedFilesMigration;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The upgrade that ships the record, on main, through the real commands: {@code sail migrate} seeds
 * what main wrote from history and then imports only a person's edit, republishing neither main's
 * stale copy over a node's newer version nor a file a node deleted; and after {@code sail sync gc}
 * compacted the history that used to decide it, {@code sail project files pull} still refreshes the
 * copies main wrote. Runs in its own JVM so the box's paths are its own.
 */
class FilesUpgradeCliTest {

  @TempDir Path home;

  @Test
  void theUpgradeOnMainPublishesNothingItCannotTellAndPullStillRefreshesMainsCopies()
      throws Exception {
    var data = Files.createDirectories(home.resolve(".sail"));
    var output = home.resolve("output.txt");
    var builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                FilesUpgradeCliTest.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    builder.environment().put("SAIL_DATA_DIR", data.toString());
    var process = builder.start();
    try {
      assertTrue(process.waitFor(120, TimeUnit.SECONDS), "isolated upgrade timed out");
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
    }
  }

  public static void main(String[] args) throws Exception {
    var filesDir = SailPaths.projectsDir().resolve("acme/files");
    try (var mainDb = Sqlite.open(SailPaths.controlPlaneDb());
        var nodeDb = Sqlite.open(Path.of(System.getProperty("user.home"), "node.db"))) {
      new SchemaManager(mainDb).migrate();
      new SchemaManager(nodeDb).migrate();
      var main = new FileStore(mainDb);
      var node = new FileStore(nodeDb);
      for (var path : List.of("stale.conf", "gone.conf", "edited.conf", "busy.conf")) {
        ContentFixtures.put(main, "acme", path, "v0");
      }
      assertEquals(0, new CommandLine(new ProjectFilesCommand.Export()).execute("-p", "acme"));
      mainDb.execute("DELETE FROM materialized_files");
      mainDb.execute("DELETE FROM data_migrations WHERE name = ?", MaterializedFilesMigration.NAME);
      SyncBox.round(mainDb, nodeDb, "file");
      ContentFixtures.put(node, "acme", "stale.conf", "v1");
      Acting.system(() -> node.delete("acme", "gone.conf"));
      var busy = SyncBox.pushPastHistory(mainDb, nodeDb, "acme", "busy.conf");
      Files.writeString(filesDir.resolve("edited.conf"), "a person's edit");

      assertEquals(0, new CommandLine(new MigrateCommand()).execute("--non-interactive"));

      assertEquals("v1", ContentFixtures.text(main, "acme", "stale.conf"), "republished");
      assertTrue(main.find("acme", "gone.conf").isEmpty(), "resurrected");
      assertEquals(
          "v0",
          ContentFixtures.text(main, "acme", "edited.conf"),
          "an edit of a shared file nothing can tell from a stale copy is not published unasked");
      assertEquals(busy, ContentFixtures.text(main, "acme", "busy.conf"));
      SyncBox.round(mainDb, nodeDb, "file");
      assertEquals("v1", ContentFixtures.text(node, "acme", "stale.conf"));
      assertTrue(node.find("acme", "gone.conf").isEmpty());

      assertEquals(0, new CommandLine(new SyncCommand.Gc()).execute());
      assertEquals(0, new CommandLine(new ProjectFilesCommand.Export()).execute("-p", "acme"));

      assertEquals("v1", Files.readString(filesDir.resolve("stale.conf")));
      assertEquals(busy, Files.readString(filesDir.resolve("busy.conf")));
      assertEquals(
          "a person's edit",
          Files.readString(filesDir.resolve("edited.conf")),
          "the undecided copy is kept until the person shares it");
      assertFalse(Files.exists(filesDir.resolve("gone.conf")));

      assertEquals(
          0,
          new CommandLine(new ProjectFilesCommand.Add())
              .execute("-p", "acme", filesDir.resolve("edited.conf").toString()));
      assertEquals("a person's edit", ContentFixtures.text(main, "acme", "edited.conf"));
      SyncBox.round(mainDb, nodeDb, "file");
      assertEquals("a person's edit", ContentFixtures.text(node, "acme", "edited.conf"));
    }
  }
}
