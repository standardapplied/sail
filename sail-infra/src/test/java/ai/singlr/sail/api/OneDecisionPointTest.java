/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.authority.MessageAuthority;
import ai.singlr.sail.authority.WriteAuthority;
import ai.singlr.sail.common.ModuleClasses;
import ai.singlr.sail.engine.SpecCliHelper;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.RevisionJournal;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.net.URISyntaxException;
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
 * the journal and the message store append a revision to the change log or ask the seam that
 * decides a write of this box's own ({@code WriteAuthority.local}, {@code admit}); only they, and a
 * rule delegating to another, ask a write rule; only a rule asks {@code Access} what a revision
 * takes ({@code WriteAuthority.refused}); only that seam asks {@code Access} past the audience the
 * scope rule decided ({@code Access.withinAudience}); only the terminal's room pin and a room
 * session's report, two doors with no message to journal, ask the message rule's post ({@code
 * MessageAuthority.post}), and both still do; and only main's commit takes a store's rule to hand
 * it one. A door that decides a row write, or journals one on its own, fails here.
 */
class OneDecisionPointTest {

  private static final String STORES = "ai/singlr/sail/store/";
  private static final String RULES = "ai/singlr/sail/authority/";
  private static final String JOURNAL = STORES + "RevisionJournal";
  private static final String MESSAGES = STORES + "MessageStore";
  private static final String CHANGE_LOG = STORES + "ChangeLog";
  private static final String MAINS_COMMIT = "ai/singlr/sail/sync/StoreReplica";
  private static final String SEAM = RULES + "WriteAuthority";
  private static final String ACCESS = "ai/singlr/sail/access/Access";
  private static final String POSTS = RULES + "MessageAuthority";
  private static final Set<String> POSTING_DOORS =
      Set.of("ai/singlr/sail/engine/HostAccess", "ai/singlr/sail/api/SailOperations");
  private static final String DECIDES =
      "(Lai/singlr/sail/identity/Actor;Ljava/lang/String;Ljava/util/Map;Ljava/util/Map;)"
          + "Ljava/util/Optional;";

  @Test
  void onlyTheJournalAndMainsCommitDecideOrJournalAWrite() throws Exception {
    var violations = new TreeSet<String>();
    for (var bytes : everyMainClass()) {
      violations.addAll(violations(bytes));
    }

    assertEquals(Set.of(), violations);
  }

  @Test
  void exactlyThePostingDoorsAskTheMessageRulesPost() throws Exception {
    var asking = new TreeSet<String>();
    for (var bytes : everyMainClass()) {
      var model = ClassFile.of().parse(bytes);
      for (var entry : model.constantPool()) {
        if (entry instanceof MemberRefEntry member
            && member.owner().asInternalName().equals(POSTS)
            && member.name().stringValue().equals("post")) {
          asking.add(model.thisClass().asInternalName());
        }
      }
    }

    assertEquals(new TreeSet<>(POSTING_DOORS), asking);
  }

  @Test
  void theGuardFlagsADoorThatDecidesOrJournalsOnItsOwn() throws IOException {
    try (var bytes = Leaky.class.getResourceAsStream("OneDecisionPointTest$Leaky.class")) {
      assertEquals(
          List.of(
              "ai/singlr/sail/api/OneDecisionPointTest$Leaky asks a write rule: SpecAuthority.decide",
              "ai/singlr/sail/api/OneDecisionPointTest$Leaky asks the journal's seam:"
                  + " WriteAuthority.local",
              "ai/singlr/sail/api/OneDecisionPointTest$Leaky asks the message rule's post:"
                  + " MessageAuthority.post",
              "ai/singlr/sail/api/OneDecisionPointTest$Leaky asks what a revision takes:"
                  + " WriteAuthority.refused",
              "ai/singlr/sail/api/OneDecisionPointTest$Leaky journals a revision: ChangeLog.append",
              "ai/singlr/sail/api/OneDecisionPointTest$Leaky skips the audience a scope rule"
                  + " decided: Access.withinAudience",
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

    Optional<Refusal> local(WriteAuthority rule) {
      return WriteAuthority.local(rule, "auth", null, null);
    }

    Decision posts() {
      return MessageAuthority.post(
          Actor.current(), Resources.conversation("lobby", "acme", List.of()));
    }

    Optional<Refusal> unscoped() {
      return Access.withinAudience(Actor.current(), Action.READ, Resources.people());
    }

    Optional<Refusal> take() {
      return WriteAuthority.refused(
          Actor.current(), WriteAuthority.Taking.of(Resources.people(), Action.MANAGE_PEOPLE));
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
      if (owner.equals(SEAM)
          && Set.of("local", "admit").contains(method)
          && !Set.of(JOURNAL, MESSAGES).contains(name)) {
        found.add(name + " asks the journal's seam: " + called);
      }
      if (owner.equals(SEAM) && method.equals("refused") && !name.startsWith(RULES)) {
        found.add(name + " asks what a revision takes: " + called);
      }
      if (owner.equals(ACCESS) && method.equals("withinAudience") && !name.equals(SEAM)) {
        found.add(name + " skips the audience a scope rule decided: " + called);
      }
      if (owner.equals(POSTS) && method.equals("post") && !POSTING_DOORS.contains(name)) {
        found.add(name + " asks the message rule's post: " + called);
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

  /** The bytes of every main class of every module, each module read whole. */
  static List<byte[]> everyMainClass() throws IOException, URISyntaxException {
    var classes = new ArrayList<byte[]>();
    for (var module : List.of(RevisionJournal.class, SpecCliHelper.class, SailOperations.class)) {
      classes.addAll(ModuleClasses.of(module));
    }
    assertTrue(classes.size() > 500, "every module's classes were read: " + classes.size());
    return classes;
  }
}
