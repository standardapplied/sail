/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.engine.SpecCliHelper;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.RevisionJournal;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A2 by construction: a write is decided at exactly one of two points, main's commit for a pushed
 * revision and the journal for every other. Read from the compiled classes of every module: only
 * the journal and the message store append a revision to the change log; only they, and a rule
 * delegating to another, ask a write rule; and only main's commit takes a store's rule to hand it
 * one. A door that decides a row write, or journals one on its own, fails here.
 */
class OneDecisionPointTest {

  private static final String STORES = "ai/singlr/sail/store/";
  private static final String RULES = "ai/singlr/sail/authority/";
  private static final String JOURNAL = STORES + "RevisionJournal";
  private static final String MESSAGES = STORES + "MessageStore";
  private static final String CHANGE_LOG = STORES + "ChangeLog";
  private static final String MAINS_COMMIT = "ai/singlr/sail/sync/StoreReplica";
  private static final String DECIDES =
      "(Lai/singlr/sail/identity/Actor;Ljava/lang/String;Ljava/util/Map;Ljava/util/Map;)"
          + "Ljava/util/Optional;";

  @Test
  void onlyTheJournalAndMainsCommitDecideOrJournalAWrite() throws Exception {
    var scanned = 0;
    var violations = new TreeSet<String>();
    for (var module : List.of(RevisionJournal.class, SpecCliHelper.class, SailOperations.class)) {
      for (var bytes : classesOf(module)) {
        violations.addAll(violations(bytes));
        scanned++;
      }
    }

    assertTrue(scanned > 500, "every module's classes were read: " + scanned);
    assertEquals(Set.of(), violations);
  }

  @Test
  void theGuardFlagsADoorThatDecidesOrJournalsOnItsOwn() throws IOException {
    try (var bytes = Leaky.class.getResourceAsStream("OneDecisionPointTest$Leaky.class")) {
      assertEquals(
          List.of(
              "ai/singlr/sail/api/OneDecisionPointTest$Leaky asks a write rule: SpecAuthority.decide",
              "ai/singlr/sail/api/OneDecisionPointTest$Leaky journals a revision: ChangeLog.append",
              "ai/singlr/sail/api/OneDecisionPointTest$Leaky takes a store's rule:"
                  + " SpecStore.authority"),
          violations(bytes.readAllBytes()));
    }
  }

  /** What no door may do. */
  private static final class Leaky {
    Optional<Refusal> decide(SpecStore specs, Map<String, Object> next) {
      return specs.authority().decide(Actor.current(), "auth", null, next);
    }

    void journal(ChangeLog log) {
      log.append("spec", "auth", "1-a", "local", false, "{}");
    }
  }

  private static List<String> violations(byte[] bytes) {
    var model = ClassFile.of().parse(bytes);
    var name = model.thisClass().asInternalName();
    var found = new TreeSet<String>();
    for (var entry : model.constantPool()) {
      if (!(entry instanceof MemberRefEntry member)) {
        continue;
      }
      var owner = member.owner().asInternalName();
      var method = member.name().stringValue();
      var called = owner.substring(owner.lastIndexOf('/') + 1) + "." + method;
      if (owner.equals(CHANGE_LOG)
          && Set.of("append", "appendSynced", "appendAuthored").contains(method)
          && !Set.of(JOURNAL, MESSAGES, CHANGE_LOG).contains(name)) {
        found.add(name + " journals a revision: " + called);
      }
      if (method.equals("decide")
          && member.type().stringValue().equals(DECIDES)
          && !Set.of(JOURNAL, MESSAGES).contains(name)
          && !name.startsWith(RULES)) {
        found.add(name + " asks a write rule: " + called);
      }
      if (method.equals("authority")
          && owner.startsWith(STORES)
          && !name.startsWith(STORES)
          && !name.equals(MAINS_COMMIT)) {
        found.add(name + " takes a store's rule: " + called);
      }
    }
    return List.copyOf(found);
  }

  /**
   * The bytes of every class in the module {@code anchor} was loaded from: a directory or a jar.
   */
  private static List<byte[]> classesOf(Class<?> anchor) throws IOException, URISyntaxException {
    var location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (Files.isDirectory(location)) {
      return classesUnder(location);
    }
    try (var jar = FileSystems.newFileSystem(location)) {
      return classesUnder(jar.getPath("/"));
    }
  }

  private static List<byte[]> classesUnder(Path root) throws IOException {
    var classes = new ArrayList<byte[]>();
    try (var files = Files.walk(root)) {
      files
          .filter(file -> file.toString().endsWith(".class"))
          .forEach(
              file -> {
                try {
                  classes.add(Files.readAllBytes(file));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }
    return classes;
  }
}
