/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.TestDefinitions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ProjectServeCommandTest {

  private static final String ACME =
      "name: acme\nservices:\n  postgres:\n    image: postgres:16\n    ports: [5432]\n"
          + "processes:\n  web:\n    command: npm run dev\n    port: 3000\n";
  private static final String RUNNING = "[{\"name\": \"acme\", \"status\": \"Running\"}]";
  private static final String LISTENING =
      "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\nLISTEN 0 511 0.0.0.0:3000 0.0.0.0:*\n---\n"
          + "Id=sail-process-web.service\nActiveState=active\nSubState=running\nExecMainStatus=0\n";
  private static final String EXITED =
      "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\n---\n"
          + "Id=sail-process-web.service\nActiveState=failed\nSubState=failed\nExecMainStatus=1\n";

  @TempDir Path dir;
  private Sqlite db;
  private final ScriptedShellExecutor shell =
      new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("incus list", RUNNING);

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.yaml(ACME)));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private HostOperations operations() {
    return OperationsFactory.create(
        db, shell, null, null, SyncScheduler.disabled(), SessionYield.NONE);
  }

  private record Ran(int exit, String out) {}

  private Ran serve(String... args) {
    var out = new ByteArrayOutputStream();
    var original = System.out;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      var exit = new CommandLine(new ProjectServeCommand(ignored -> operations())).execute(args);
      return new Ran(exit, CommandLine.Help.Ansi.OFF.string(out.toString(StandardCharsets.UTF_8)));
    } finally {
      System.setOut(original);
    }
  }

  @Test
  void serveSaysWhatListensWhereAndExitsZeroWhenEveryPortListens() {
    shell.onOk("ss -ltnH", LISTENING);

    var ran = serve("acme");

    assertEquals(
        new Ran(0, "  Brought up acme\n  postgres  5432  listening\n  web       3000  listening\n"),
        ran);
  }

  @Test
  void aPortThatExitedExitsOneWithItsLastLine() {
    shell.onOk("ss -ltnH", EXITED).onOk("awk -v mark", "Error: Cannot find module 'next'\n");

    var ran = serve("acme");

    assertEquals(1, ran.exit());
    assertTrue(
        ran.out().endsWith("  web       3000  exited 1: Error: Cannot find module 'next'\n"),
        ran.out());
  }

  @Test
  void jsonIsTheApisAnswer() {
    shell.onOk("ss -ltnH", LISTENING);

    var ran = serve("acme", "--json");

    var parsed = YamlUtil.parseMap(ran.out());
    assertEquals(0, ran.exit());
    assertEquals("acme", parsed.get("project"));
    assertEquals(true, parsed.get("all_listening"));
    assertEquals(
        List.of("postgres", "web"),
        ((List<?>) parsed.get("ports"))
            .stream().map(row -> ((Map<?, ?>) row).get("name")).toList());
  }

  @Test
  void dryRunLooksAndStartsNothing() {
    shell.onOk("ss -ltnH", "---\n");

    var ran = serve("acme", "--dry-run");

    assertEquals(
        new Ran(0, "  Would bring up acme\n  postgres  5432  stopped\n  web       3000  stopped\n"),
        ran);
    assertEquals(
        List.of(),
        shell.invocations().stream().filter(c -> c.contains("systemctl --user start")).toList());
  }

  @Test
  void stopStopsTheProcessesAndSaysWhereThePortsStand() {
    shell
        .onOk("ss -ltnH", "LISTEN 0 4096 0.0.0.0:5432 0.0.0.0:*\n---\n")
        .onOk("basename", "sail-process-web\n");

    var ran = serve("acme", "--stop");

    assertEquals(
        new Ran(
            0,
            "  Stopped the processes of acme\n  postgres  5432  listening\n  web       3000  stopped\n"),
        ran);
  }

  @Test
  void aProjectWhosePortsNobodyDeclaresSaysHowToDeclareOne() {
    Acting.system(() -> new ProjectStore(db).upsert(TestDefinitions.named("acme")));
    shell.onOk("ss -ltnH", "---\n");

    var ran = serve("acme");

    assertEquals(
        new Ran(
            0,
            "  Brought up acme\n  No service or process declares a port: add `port` to a process"
                + " in its definition.\n"),
        ran);
  }
}
