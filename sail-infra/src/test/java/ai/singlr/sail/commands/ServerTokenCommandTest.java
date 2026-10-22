/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ServerTokenCommandTest {

  @TempDir Path tempDir;

  private final PrintStream originalOut = System.out;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private String refusal;

  @BeforeEach
  void aMigratedControlPlane() {
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try (var db = Sqlite.open(database())) {
      new SchemaManager(db).migrate();
      new FdeStore(db).add("ada", "Ada", "ada@example.com", "member");
    }
  }

  @AfterEach
  void restoreOut() {
    System.setOut(originalOut);
  }

  @Test
  void tokensAreCreatedListedAndRevokedOnThisBox() {
    assertEquals(0, run(new ServerTokenCommand.ListTokens(operations())));
    assertEquals(List.of("  No tokens. Run 'sail server init' to create one."), printed());

    assertEquals(
        0, run(new ServerTokenCommand.Create(operations()), "ci", "member", "--ttl-days", "7"));
    assertEquals("  ✓ Token created: ci", printed().getFirst());
    assertTrue(printed().stream().anyMatch(line -> line.startsWith("    Expires ")));
    assertEquals("    Save this token — it will not be shown again.", printed().getLast());
    assertEquals(
        0,
        run(new ServerTokenCommand.Create(operations()), "forever", "--no-expiry", "--fde", "ada"));
    assertTrue(printed().contains("    Never expires."), printed().toString());

    assertEquals(0, run(new ServerTokenCommand.ListTokens(operations())));
    assertEquals(2, printed().size());
    assertTrue(printed().getFirst().matches("  ci {18}  member {2}  created .*  \\(expires .*\\)"));
    assertTrue(printed().getLast().endsWith("(never expires)"), printed().toString());

    assertEquals(0, run(new ServerTokenCommand.Revoke(operations()), "ci"));
    assertEquals(List.of("  ✓ Token 'ci' revoked."), printed());
    assertEquals(0, run(new ServerTokenCommand.Revoke(operations()), "ci"));
    assertEquals(List.of("  ⚠ No token named 'ci'."), printed());
    assertEquals(1, run(new ServerTokenCommand.Create(operations()), "x", "--fde", "ghost"));
    assertEquals("No FDE with handle 'ghost'. Add it with 'sail fde add ghost'.", refusal);
  }

  private Path database() {
    return tempDir.resolve("sail.db");
  }

  private Function<OperatorCommand, HostOperations> operations() {
    return operator -> OperationsFactory.open(database());
  }

  private int run(Runnable verb, String... args) {
    out.reset();
    refusal = null;
    var command = new CommandLine(verb);
    command.setExecutionExceptionHandler(
        (failure, line, parsed) -> {
          refusal = failure.getMessage();
          return 1;
        });
    return command.execute(args);
  }

  private List<String> printed() {
    return out.toString(StandardCharsets.UTF_8).lines().toList();
  }

  @Test
  void defaultsToTheStandardLifetime() {
    assertEquals(TokenStore.DEFAULT_TTL, ServerTokenCommand.Create.resolveTtl(false, null));
  }

  @Test
  void noExpiryYieldsNoLifetime() {
    assertNull(ServerTokenCommand.Create.resolveTtl(true, null));
  }

  @Test
  void anExplicitDayCountWins() {
    assertEquals(Duration.ofDays(30), ServerTokenCommand.Create.resolveTtl(false, 30));
  }

  @Test
  void noExpiryAndTtlTogetherIsRejected() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> ServerTokenCommand.Create.resolveTtl(true, 30));
    assertTrue(thrown.getMessage().contains("not both"));
  }

  @Test
  void aNonPositiveLifetimeIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> ServerTokenCommand.Create.resolveTtl(false, 0));
    assertThrows(
        IllegalArgumentException.class, () -> ServerTokenCommand.Create.resolveTtl(false, -5));
  }
}
