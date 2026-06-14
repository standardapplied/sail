/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConflictDetectorTest {

  private static Map<String, Object> snap(Object... kv) {
    var m = new LinkedHashMap<String, Object>();
    for (var i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void identicalLocalAndRemoteConverge() {
    var base = snap("title", "A", "status", "pending");
    var both = snap("title", "B", "status", "pending");
    assertInstanceOf(ConflictDetector.Converged.class, ConflictDetector.detect(base, both, both));
  }

  @Test
  void onlyRemoteChangedTakesRemote() {
    var base = snap("title", "A", "status", "pending");
    var local = snap("title", "A", "status", "pending");
    var remote = snap("title", "A", "status", "in_progress");
    assertInstanceOf(
        ConflictDetector.TakeRemote.class, ConflictDetector.detect(base, local, remote));
  }

  @Test
  void onlyLocalChangedKeepsLocal() {
    var base = snap("title", "A", "status", "pending");
    var local = snap("title", "A2", "status", "pending");
    var remote = snap("title", "A", "status", "pending");
    assertInstanceOf(
        ConflictDetector.KeepLocal.class, ConflictDetector.detect(base, local, remote));
  }

  @Test
  void disjointFieldEditsAutoMerge() {
    var base = snap("title", "A", "status", "pending", "assignee", null);
    var local = snap("title", "A2", "status", "pending", "assignee", null);
    var remote = snap("title", "A", "status", "in_progress", "assignee", null);

    var resolution = ConflictDetector.detect(base, local, remote);

    var merged = assertInstanceOf(ConflictDetector.Merged.class, resolution);
    assertEquals("A2", merged.result().get("title"));
    assertEquals("in_progress", merged.result().get("status"));
  }

  @Test
  void sameFieldDifferentValuesConflict() {
    var base = snap("title", "A");
    var local = snap("title", "Local");
    var remote = snap("title", "Remote");

    var conflict =
        assertInstanceOf(
            ConflictDetector.Conflict.class, ConflictDetector.detect(base, local, remote));
    assertEquals(java.util.List.of("title"), conflict.fields());
  }

  @Test
  void sameFieldSameNewValueIsNotAConflict() {
    var base = snap("title", "A");
    var converged = snap("title", "Same");
    assertInstanceOf(
        ConflictDetector.Converged.class, ConflictDetector.detect(base, converged, converged));
  }

  @Test
  void mixedMergeAndConflictReportsOnlyConflictingFields() {
    var base = snap("title", "A", "status", "pending", "branch", "main");
    var local = snap("title", "Local", "status", "in_progress", "branch", "main");
    var remote = snap("title", "Remote", "status", "pending", "branch", "feat");

    var conflict =
        assertInstanceOf(
            ConflictDetector.Conflict.class, ConflictDetector.detect(base, local, remote));
    assertEquals(java.util.List.of("title"), conflict.fields());
  }

  @Test
  void localDeleteWithUnchangedRemotePropagatesTheDelete() {
    var base = snap("title", "A");
    assertInstanceOf(
        ConflictDetector.KeepLocal.class, ConflictDetector.detect(base, null, snap("title", "A")));
  }

  @Test
  void remoteDeleteWithUnchangedLocalAcceptsTheDelete() {
    var base = snap("title", "A");
    assertInstanceOf(
        ConflictDetector.TakeRemote.class, ConflictDetector.detect(base, snap("title", "A"), null));
  }

  @Test
  void deleteVersusEditConflicts() {
    var base = snap("title", "A");
    var edited = snap("title", "Edited");

    var localDeleteRemoteEdit = ConflictDetector.detect(base, null, edited);
    var remoteDeleteLocalEdit = ConflictDetector.detect(base, edited, null);

    assertInstanceOf(ConflictDetector.Conflict.class, localDeleteRemoteEdit);
    assertInstanceOf(ConflictDetector.Conflict.class, remoteDeleteLocalEdit);
    assertEquals(
        java.util.List.of(ConflictDetector.DELETED_FIELD),
        ((ConflictDetector.Conflict) localDeleteRemoteEdit).fields());
  }

  @Test
  void newLocalEntityAbsentOnRemotePushes() {
    assertInstanceOf(
        ConflictDetector.KeepLocal.class, ConflictDetector.detect(null, snap("title", "A"), null));
  }

  @Test
  void newRemoteEntityAbsentLocallyPulls() {
    assertInstanceOf(
        ConflictDetector.TakeRemote.class, ConflictDetector.detect(null, null, snap("title", "A")));
  }

  @Test
  void bothDeletedConverge() {
    assertInstanceOf(
        ConflictDetector.Converged.class, ConflictDetector.detect(snap("title", "A"), null, null));
  }

  @Test
  void noCommonAncestorIdenticalConvergesDifferentConflicts() {
    var same = snap("title", "X");
    assertInstanceOf(ConflictDetector.Converged.class, ConflictDetector.detect(null, same, same));

    var local = snap("title", "X");
    var remote = snap("title", "Y");
    assertInstanceOf(ConflictDetector.Conflict.class, ConflictDetector.detect(null, local, remote));
  }

  @Test
  void newFieldAddedOnlyLocallyMergesWithoutConflict() {
    var base = snap("title", "A");
    var local = snap("title", "A", "assignee", "uday");
    var remote = snap("title", "A2");

    var merged =
        assertInstanceOf(
            ConflictDetector.Merged.class, ConflictDetector.detect(base, local, remote));
    assertEquals("uday", merged.result().get("assignee"));
    assertEquals("A2", merged.result().get("title"));
  }

  @Test
  void differingMetadataNeverBlocksConverge() {
    var base = snap("title", "A", "_actor", "ada");
    var local = snap("title", "B", "_actor", "ada");
    var remote = snap("title", "B", "_actor", "bob");

    assertInstanceOf(
        ConflictDetector.Converged.class,
        ConflictDetector.detect(base, local, remote),
        "identical work fields converge even though _actor differs");
  }

  @Test
  void metadataIsNeverReportedAsAClashingField() {
    var base = snap("title", "A", "_actor", "ada");
    var local = snap("title", "B", "_actor", "ada");
    var remote = snap("title", "C", "_actor", "bob");

    var conflict =
        assertInstanceOf(
            ConflictDetector.Conflict.class, ConflictDetector.detect(base, local, remote));
    assertEquals(java.util.List.of("title"), conflict.fields(), "_actor is never a clashing field");
  }

  @Test
  void aMergeKeepsTheLocalBoxsMetadata() {
    var base = snap("title", "A", "status", "pending", "_actor", "base");
    var local = snap("title", "A2", "status", "pending", "_actor", "ada");
    var remote = snap("title", "A", "status", "in_progress", "_actor", "bob");

    var merged =
        assertInstanceOf(
            ConflictDetector.Merged.class, ConflictDetector.detect(base, local, remote));
    assertEquals("A2", merged.result().get("title"));
    assertEquals("in_progress", merged.result().get("status"));
    assertEquals(
        "ada", merged.result().get("_actor"), "the merging box authored the merged result");
  }
}
