/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  @Test
  void aLatestWinsFieldMovedOnBothSidesMergesToTheLaterInstant() {
    var base = Map.<String, Object>of("status", "running", "beat", "2026-09-01T00:00:00Z");
    var earlier = Map.<String, Object>of("status", "running", "beat", "2026-09-01T00:00:41Z");
    var later = Map.<String, Object>of("status", "running", "beat", "2026-09-01T00:00:41.5Z");

    var localLater =
        assertInstanceOf(
            ConflictDetector.Merged.class,
            ConflictDetector.detect(base, later, earlier, Set.of("beat")));
    var remoteLater =
        assertInstanceOf(
            ConflictDetector.Merged.class,
            ConflictDetector.detect(base, earlier, later, Set.of("beat")));

    assertEquals("2026-09-01T00:00:41.5Z", localLater.result().get("beat"));
    assertEquals("2026-09-01T00:00:41.5Z", remoteLater.result().get("beat"));
  }

  @Test
  void aLatestWinsFieldNeverExcusesARealConflictBesideIt() {
    var base = Map.<String, Object>of("status", "running", "beat", "2026-09-01T00:00:00Z");
    var local = Map.<String, Object>of("status", "completed", "beat", "2026-09-01T00:00:05Z");
    var remote = Map.<String, Object>of("status", "cancelled", "beat", "2026-09-01T00:00:09Z");

    var conflict =
        assertInstanceOf(
            ConflictDetector.Conflict.class,
            ConflictDetector.detect(base, local, remote, Set.of("beat")));

    assertEquals(List.of("status"), conflict.fields());
  }

  @Test
  void aLatestWinsFieldStampedOnOneSideOnlyBeatsAnAbsentStamp() {
    var base = new LinkedHashMap<String, Object>();
    base.put("status", "running");
    base.put("beat", "2026-09-01T00:00:00Z");
    var cleared = new LinkedHashMap<String, Object>(base);
    cleared.put("beat", null);
    var stamped = Map.<String, Object>of("status", "running", "beat", "2026-09-01T00:00:09Z");

    var merged =
        assertInstanceOf(
            ConflictDetector.Merged.class,
            ConflictDetector.detect(base, cleared, stamped, Set.of("beat")));

    assertEquals("2026-09-01T00:00:09Z", merged.result().get("beat"));
  }
}
