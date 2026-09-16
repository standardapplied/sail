/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandsUseTheSeamTest {
  private static final Set<String> ALLOWED =
      Set.of(
          "MigrateCommand", "JoinCommand", "ServerStartCommand", "SyncServerCommand", "FdeCommand");

  @Test
  void commandsUseOperationsInsteadOfOpeningStores() throws Exception {
    var root =
        Path.of(SyncCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("ai/singlr/sail/commands");
    var violations = new ArrayList<String>();
    try (var classes = Files.walk(root)) {
      for (var path : classes.filter(p -> p.toString().endsWith(".class")).toList()) {
        var name = path.getFileName().toString().replaceFirst("[.$].*$", "");
        if (!ALLOWED.contains(name)) {
          violations.addAll(violations(Files.readAllBytes(path)));
        }
      }
    }
    assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
  }

  @Test
  void guardRejectsATestCommandThatOpensSqliteOrConstructsAStore() throws IOException {
    try (var bytes =
        BypassCommand.class.getResourceAsStream("CommandsUseTheSeamTest$BypassCommand.class")) {
      var violations = violations(bytes.readAllBytes());
      assertEquals(2, violations.size());
      assertTrue(violations.stream().anyMatch(v -> v.endsWith("Sqlite.open")));
      assertTrue(violations.stream().anyMatch(v -> v.endsWith("SpecStore.<init>")));
    }
  }

  private static List<String> violations(byte[] bytes) {
    var model = ClassFile.of().parse(bytes);
    var violations = new ArrayList<String>();
    for (var entry : model.constantPool()) {
      if (entry instanceof MemberRefEntry member) {
        var owner = member.owner().asInternalName();
        var method = member.name().stringValue();
        if ((owner.equals("ai/singlr/sail/store/Sqlite") && method.equals("open"))
            || (owner.endsWith("Store") && method.equals("<init>"))) {
          violations.add(model.thisClass().asInternalName() + " calls " + owner + "." + method);
        }
      }
    }
    return List.copyOf(violations);
  }

  private static final class BypassCommand {
    SpecStore open(Path path) {
      return new SpecStore(Sqlite.open(path));
    }
  }
}
