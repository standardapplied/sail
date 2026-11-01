/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The sync lane on the binary engineers install, a fleet of the build under test, across real sshd
 * and the gateway's forced command. See {@link NativeFleet} for how the binaries arrive and why a
 * box is a container.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class NativeFleetIT {

  private static final String BETA_ON_DISK =
      "test -e /root/.sail/projects/beta && echo present || echo gone";

  private static final int BODY_BYTES = 48_000;
  private static final int CUT_AFTER_BYTES = 3_000_000;

  @Test
  void aFleetKeepsEveryWriteOfAnOfflineNodeAndSurfacesTheConflict() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      var mady = fleet.node("mady", fleet.candidate());
      main.serving(
          () -> {
            main.createSpecs("seed", 5, BODY_BYTES);
            main.shOk(
                "dd if=/dev/urandom of=/tmp/shared.bin bs=1M count=3 status=none && chmod 644"
                    + " /tmp/shared.bin");
            main.sailOk(
                "project", "files", "add", "-p", "demo", "/tmp/shared.bin", "--as", "shared.bin");
            main.apiOk("spec", "update", "seed-1", "--assignee", "mady");
          });
      var sharedHash = main.shOk("sha256sum /tmp/shared.bin").split(" ")[0];
      var sharedMode =
          Integer.parseInt(
              main.shOk("stat -c %a \"$HOME/.sail/projects/demo/files/shared.bin\"").strip(), 8);
      mady.sailOk("sync");
      fleet.assertConverged(mady);

      main.serving(
          () -> {
            main.createSpecs("main-new", 1, BODY_BYTES);
            main.retitle("seed-1", "edited on main");
          });
      fleet.offline(
          mady,
          () -> {
            mady.createSpecs("node-new", 1, BODY_BYTES);
            mady.retitle("seed-1", "edited on the node");
          });

      mady.sailOk("sync");
      assertEquals(List.of("seed-1"), pendingConflicts(mady));
      assertEquals("edited on main", main.title("seed-1"));
      assertEquals("edited on the node", mady.title("seed-1"));
      assertEquals("1", main.query("SELECT count(*) FROM specs WHERE id = 'node-new-1'").strip());
      assertEquals("1", mady.query("SELECT count(*) FROM specs WHERE id = 'main-new-1'").strip());

      mady.sailOk("conflicts", "resolve", "seed-1", "--mine");
      mady.sailOk("sync");
      assertEquals(List.of(), pendingConflicts(mady));
      assertEquals("edited on the node", main.title("seed-1"));
      fleet.assertConverged(mady);
      assertEquals(
          sharedHash,
          mady.shOk("sail project files cat -p demo shared.bin | sha256sum").split(" ")[0]);
      assertEquals(
          Integer.toString(sharedMode),
          mady.query("SELECT mode FROM project_files WHERE path = 'shared.bin'").strip());
      assertEquals(
          main.query("SELECT content_hash, size, mode, kind FROM project_files ORDER BY id"),
          mady.query("SELECT content_hash, size, mode, kind FROM project_files ORDER BY id"));
      fleet.assertHeadsAgree(mady);
    }
  }

  @Test
  void aBoxAnswersAnInteractiveSpecSessionAsItsHandleFromTheMomentItTakesOne() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      var mady = fleet.node("mady", fleet.candidate());
      var joined = mady.boxCredential();

      main.serving(
          () -> {
            assertEquals(
                "uday", NativeFleet.json(main.interactiveSpec("whoami").output()).get("handle"));
            main.sailOk("fde", "add", "grace", "--role", "admin", "--email", "grace@example.dev");
            var uday = main.boxCredential();

            main.sailOk("host", "config", "set", "sync-handle", "grace");

            assertEquals(
                "grace", NativeFleet.json(main.interactiveSpec("whoami").output()).get("handle"));
            assertEquals("401", main.whoamiStatus(uday), "the old handle's credential is revoked");
          });
      mady.sailOk("sync");
      fleet.offline(
          mady,
          () -> {
            assertEquals(joined, mady.boxCredential(), "join issued it; the start kept it");
            assertEquals(
                "mady", NativeFleet.json(mady.interactiveSpec("whoami").output()).get("handle"));
          });
    }
  }

  @Test
  void aFreshNodePullsPastOneFrameAndACutTransportLosesNothing() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      main.serving(
          () -> {
            main.shOk(
                "dd if=/dev/urandom of=/tmp/large.bin bs=1M count=40 status=none && chmod 750"
                    + " /tmp/large.bin");
            main.sailOk(
                "project", "files", "add", "-p", "demo", "/tmp/large.bin", "--as", "large.bin");
          });
      var expectedHash = main.shOk("sha256sum /tmp/large.bin").split(" ")[0];
      var rejesh = fleet.node("rejesh", fleet.candidate());
      rejesh.shOk(
          """
          install -d /opt/cut
          printf '#!/bin/sh\\n/usr/bin/ssh "$@" | { stdbuf -o0 head -c %d; pkill -x ssh; }\\n' > /opt/cut/ssh
          chmod 755 /opt/cut/ssh
          """
              .formatted(CUT_AFTER_BYTES));

      var cut = rejesh.sh("PATH=/opt/cut:$PATH sail sync");

      assertNotEquals(0, cut.exit(), cut::output);
      assertEquals("ok", rejesh.query("PRAGMA integrity_check").strip());
      assertEquals(
          "0",
          rejesh
              .query(
                  "SELECT count(*) FROM specs s LEFT JOIN spec_content c ON c.spec_id = s.id"
                      + " WHERE c.spec_id IS NULL")
              .strip(),
          "a cut round left a spec without its content");

      var heldBytes =
          Long.parseLong(rejesh.query("SELECT COALESCE(sum(size), 0) FROM chunks").strip());
      assertTrue(heldBytes > 0);
      var totalBytes =
          Long.parseLong(main.query("SELECT COALESCE(sum(size), 0) FROM chunks").strip());
      var round = NativeFleet.json(rejesh.sailOk("sync", "--json"));

      assertEquals(totalBytes - heldBytes, ((Number) round.get("bytes_fetched")).longValue());
      assertEquals(
          expectedHash,
          rejesh.shOk("sail project files cat -p demo large.bin | sha256sum").split(" ")[0]);
      assertEquals(
          "488", rejesh.query("SELECT mode FROM project_files WHERE path = 'large.bin'").strip());
      fleet.assertConverged(rejesh);
      assertEquals(0, NativeFleet.json(rejesh.sailOk("sync", "--json")).get("pulled"));
      fleet.assertHeadsAgree(rejesh);
    }
  }

  @Test
  void aNodeRecoversFromARekeyedMainByFollowingWhatItsSyncPrinted() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      main.serving(() -> main.createSpecs("pinned", 2, 1_000));
      var mady = fleet.node("mady", fleet.candidate());

      mady.sailOk("sync");
      fleet.assertConverged(mady);

      fleet.rekeyMain();
      var refused = mady.sail("sync");

      assertNotEquals(0, refused.exit(), refused::output);
      assertTrue(
          refused
              .output()
              .contains(
                  "hello: Sync channel closed before main replied. ssh said: Host key verification"
                      + " failed. If main was rebuilt from a copy of its database, remove the old"
                      + " key with 'ssh-keygen -R localhost', re-run 'sail init --main"
                      + " sail@localhost' and compare the fingerprint it prints with main's ('sudo"
                      + " ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub' on main); otherwise"
                      + " something between this box and main is answering for it."),
          refused::output);
      assertFalse(refused.output().contains("REMOTE HOST IDENTIFICATION"), refused::output);

      mady.shOk("ssh-keygen -R localhost");
      var rejoined = mady.sailOk("join", "sail@localhost", "--handle", "mady");

      assertTrue(
          rejoined.contains("Main already has this box's key. Finish with sail sync."), rejoined);
      assertFalse(rejoined.contains("fde add"), rejoined);
      mady.sailOk("sync");
      fleet.assertConverged(mady);
    }
  }

  @Test
  void aNodeRefusesAnEmptyMainSetUpAgainAtItsAddressAndMovesNothing() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      main.serving(() -> main.createSpecs("seed", 2, 1_000));
      var mady = fleet.node("mady", fleet.candidate());
      mady.sailOk("sync");
      fleet.assertConverged(mady);
      fleet.offline(mady, () -> mady.createSpecs("node-new", 1, 1_000));
      var synced = main.query("SELECT id FROM catalog").strip();
      var nodeBefore = mady.replicated();

      fleet.rebuildMain(null);
      mady.shOk("ssh-keygen -R localhost");
      var joined = NativeFleet.json(mady.sailOk("join", "localhost", "--handle", "mady", "--json"));
      main.sailOk(
          "fde",
          "add",
          "mady",
          "--role",
          "member",
          "--email",
          "mady@example.dev",
          "--key",
          (String) joined.get("public_key"),
          "--main-host-key",
          (String) joined.get("main_host_key"));
      var mainBefore = main.replicated();
      var refused = mady.sail("sync");

      assertEquals(1, refused.exit(), refused::output);
      assertTrue(
          refused
              .output()
              .contains(
                  "Main at sail@localhost is not the database this box synced with (catalog "
                      + synced
                      + ", now "
                      + main.query("SELECT id FROM catalog").strip()
                      + "). Restore main's database (/var/lib/sail/sail.db) from a copy, then"
                      + " sync again."),
          refused::output);
      assertFalse(refused.output().contains("Already in sync"), refused::output);
      assertEquals(mainBefore, main.replicated());
      assertEquals(nodeBefore, mady.replicated());
    }
  }

  @Test
  void aMainSetUpAgainFromACopyOfItsDatabaseIsTheSameMainAndTakesTheNodesNewerWork()
      throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      main.serving(() -> main.createSpecs("seed", 2, 1_000));
      var mady = fleet.node("mady", fleet.candidate());
      mady.sailOk("sync");
      fleet.assertConverged(mady);
      main.shOk("sqlite3 /var/lib/sail/sail.db \".backup /root/sail.db.copy\"");
      fleet.offline(mady, () -> mady.createSpecs("node-new", 1, 1_000));

      var migrated = fleet.rebuildMain("/root/sail.db.copy");
      mady.shOk("ssh-keygen -R localhost");
      var rejoined = mady.sailOk("join", "localhost");
      var fingerprint = main.shOk("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub").split(" ")[1];

      assertTrue(
          rejoined.contains(
              "Pinned main's host key "
                  + fingerprint
                  + ". Check it on main: sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"),
          rejoined);
      assertTrue(rejoined.contains("Main already has this box's key."), rejoined);
      assertFalse(rejoined.contains("Permission denied"), rejoined);
      assertTrue(
          mady.shOk("cat /root/.sail/host.yaml").contains("handle: mady"),
          "a re-join without --handle keeps the handle, on a roster of two");
      assertTrue(
          migrated.contains(
              "API token re-issued for this box: the database came from another box."),
          migrated);
      main.serving(
          () -> {
            main.apiOk("spec", "list", "-p", "demo");
            assertEquals(
                "uday", NativeFleet.json(main.interactiveSpec("whoami").output()).get("handle"));
          });
      mady.sailOk("sync");
      assertEquals("1", main.query("SELECT count(*) FROM specs WHERE id = 'node-new-1'").strip());
      fleet.assertConverged(mady);
      var again = mady.sailOk("sync");
      assertTrue(again.contains("Already in sync with main."), again);
    }
  }

  @Test
  void aMainRestoredInPlaceFromAnOlderCopyLeavesTheNodeHoldingWhatMainHoldsAfterOneRound()
      throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      main.serving(() -> main.createSpecs("seed", 2, 1_000));
      var mady = fleet.node("mady", fleet.candidate());
      mady.sailOk("sync");
      main.shOk("sqlite3 /var/lib/sail/sail.db \".backup /root/sail.db.copy\"");
      main.serving(() -> main.createSpecs("late", 2, 1_000));
      mady.sailOk("sync");
      assertEquals("2", mady.query("SELECT count(*) FROM specs WHERE id LIKE 'late-%'").strip());

      var migrated = fleet.restoreMain("/root/sail.db.copy");
      var round = mady.sailOk("sync");

      assertFalse(migrated.contains("API token"), migrated);
      assertTrue(
          round.contains(
              "main no longer holds 2 spec(s) this box had pulled (late-1, late-2): main was"
                  + " restored from a copy older than this box's last sync. Each stays in its"
                  + " history: sail spec history <id>."),
          round);
      fleet.assertConverged(mady);
      assertEquals(
          "2",
          mady.query(
                  "SELECT count(*) FROM change_log WHERE entity_type = 'spec' AND entity_id ="
                      + " 'late-1'")
              .strip(),
          "the version pulled and its withdrawal stay in the spec's history");
      var again = mady.sailOk("sync");
      assertTrue(again.contains("Already in sync with main."), again);
    }
  }

  @Test
  void aMainRestoredInPlaceThatWroteMoreThanItLostLeavesTheNodeHoldingWhatMainHolds()
      throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      main.serving(() -> main.createSpecs("seed", 2, 1_000));
      var mady = fleet.node("mady", fleet.candidate());
      mady.sailOk("sync");
      main.shOk("sqlite3 /var/lib/sail/sail.db \".backup /root/sail.db.copy\"");
      main.serving(() -> main.createSpecs("late", 2, 1_000));
      mady.sailOk("sync");

      fleet.restoreMain("/root/sail.db.copy");
      main.serving(() -> main.createSpecs("after", 3, 1_000));
      var round = mady.sailOk("sync");

      assertTrue(
          round.contains(
              "main no longer holds 2 spec(s) this box had pulled (late-1, late-2): main was"
                  + " restored from a copy older than this box's last sync."),
          round);
      assertEquals("3", mady.query("SELECT count(*) FROM specs WHERE id LIKE 'after-%'").strip());
      fleet.assertConverged(mady);
      var again = mady.sailOk("sync");
      assertTrue(again.contains("Already in sync with main."), again);
    }
  }

  @Test
  void aPruneOnMainLeavesTheNodeNoRowHistoryOrBlobOfItAfterOneRound() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      var mady = fleet.node("mady", fleet.candidate());
      main.serving(
          () -> {
            main.createSpecs("doomed", 1, BODY_BYTES);
            main.createSpecs("kept", 1, BODY_BYTES);
            main.shOk("dd if=/dev/urandom of=/tmp/scratch.bin bs=1M count=2 status=none");
            main.sailOk(
                "project", "files", "add", "-p", "scratch", "/tmp/scratch.bin", "--as", "s.bin");
            main.shOk("dd if=/dev/urandom of=/tmp/kept.bin bs=1M count=1 status=none");
            main.sailOk("project", "files", "add", "-p", "demo", "/tmp/kept.bin", "--as", "k.bin");
          });
      main.execute(RUN_OF_DOOMED);
      mady.sailOk("sync");
      fleet.assertConverged(mady);
      assertEquals("1", mady.query("SELECT count(*) FROM runs WHERE spec_id = 'doomed-1'").strip());
      var gone =
          List.of(
              main.query("SELECT body_hash FROM specs WHERE id = 'doomed-1'").strip(),
              main.query("SELECT content_hash FROM project_files WHERE project = 'scratch'")
                  .strip());
      var keptFile =
          main.query("SELECT content_hash FROM project_files WHERE project = 'demo'").strip();
      for (var hash : gone) {
        assertEquals(
            "1", mady.query("SELECT count(*) FROM blobs WHERE hash = '" + hash + "'").strip());
      }

      main.serving(
          () -> {
            main.apiOk("spec", "update", "doomed-1", "--status", "archived");
            main.apiOk("spec", "prune", "doomed-1", "--apply");
          });
      main.shOk(
          "printf '#!/bin/sh\\n"
              + "echo \"[]\"\\n"
              + "' > /usr/local/bin/incus && chmod 755 /usr/local/bin/incus");
      main.sailOk("project", "destroy", "scratch", "--purge", "--yes", "--json");
      mady.sailOk("sync");

      for (var box : List.of(main, mady)) {
        for (var table :
            List.of(
                "specs WHERE id = 'doomed-1'",
                "spec_content WHERE spec_id = 'doomed-1'",
                "rooms WHERE id = 'doomed-1'",
                "runs WHERE spec_id = 'doomed-1'",
                "project_files WHERE project = 'scratch'")) {
          assertEquals("0", box.query("SELECT count(*) FROM " + table).strip(), table);
        }
        assertEquals(
            "0",
            box.query(
                    "SELECT count(*) FROM change_log WHERE kind <> 'erasure' AND (entity_id ="
                        + " 'doomed-1' OR entity_id = '"
                        + DOOMED_RUN
                        + "' OR entity_id LIKE 'scratch%')")
                .strip(),
            "history of what was pruned");
        for (var hash : gone) {
          assertEquals(
              "0", box.query("SELECT count(*) FROM blobs WHERE hash = '" + hash + "'").strip());
        }
        assertEquals(
            "1", box.query("SELECT count(*) FROM blobs WHERE hash = '" + keptFile + "'").strip());
        assertEquals("1", box.query("SELECT count(*) FROM specs WHERE id = 'kept-1'").strip());
      }
      assertTrue(
          Integer.parseInt(
                  mady.query("SELECT count(*) FROM change_log WHERE kind = 'erasure'").strip())
              >= 3,
          "the node holds main's erasure rows");
      fleet.assertConverged(mady);
      fleet.assertHeadsAgree(mady);
    }
  }

  @Test
  void aScopedNodeHoldsExactlyItsGrantedProjectsThroughGrantWriteRevokeAndPrune() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      main.catalog("alpha", "beta");
      var nia = fleet.node("nia", fleet.candidate(), "--scoped");
      var uma = fleet.node("uma", fleet.candidate());
      var ole = fleet.node("ole", fleet.candidate(), "--scoped");
      main.sailOk("fde", "grant", "nia", "alpha");
      main.sailOk("fde", "grant", "ole", "alpha");
      main.serving(
          () -> {
            main.createSpecs("a", 2, BODY_BYTES, "alpha");
            main.createSpecs("b", 2, BODY_BYTES, "beta");
            main.shOk("dd if=/dev/urandom of=/tmp/a.bin bs=1M count=1 status=none");
            main.shOk("dd if=/dev/urandom of=/tmp/b.bin bs=1M count=1 status=none");
            main.sailOk("project", "files", "add", "-p", "alpha", "/tmp/a.bin", "--as", "a.bin");
            main.sailOk("project", "files", "add", "-p", "beta", "/tmp/b.bin", "--as", "b.bin");
          });
      var betaFile =
          main.query("SELECT content_hash FROM project_files WHERE project = 'beta'").strip();

      nia.sailOk("sync");
      uma.sailOk("sync");
      ole.sailOk("sync");

      assertEquals(
          "a-1\na-2",
          ole.query("SELECT id FROM specs ORDER BY id").strip(),
          "main scopes a second scoped node to its own grant");
      assertEquals(
          "0", ole.query("SELECT count(*) FROM blobs WHERE hash = '" + betaFile + "'").strip());
      fleet.assertScopedTo(nia, "alpha");
      assertEquals("2", nia.query("SELECT count(*) FROM specs").strip());
      assertEquals(
          "0", nia.query("SELECT count(*) FROM blobs WHERE hash = '" + betaFile + "'").strip());
      assertEquals(
          "nia|nia@example.dev\nole|\nuday|\numa|",
          nia.query("SELECT handle, email FROM fdes ORDER BY handle").strip(),
          "a scoped node mirrors who shares its projects, and no email but its own");
      fleet.assertConverged(uma);
      fleet.assertHeadsAgree(uma);
      main.serving(
          () -> {
            var listed =
                nia.shOk(
                    "ssh -o BatchMode=yes -o IdentitiesOnly=yes -i /root/.sail/sync_ed25519"
                        + " sail@localhost spec list --all-projects --json --server"
                        + " http://127.0.0.1:7171");
            assertTrue(listed.contains("\"a-1\"") && listed.contains("\"a-2\""), listed);
            assertFalse(listed.contains("b-1") || listed.contains("beta"), listed);
          });

      main.sailOk("fde", "grant", "nia", "beta");
      nia.sailOk("sync");

      fleet.assertScopedTo(nia, "alpha", "beta");
      assertEquals("4", nia.query("SELECT count(*) FROM specs").strip());
      assertEquals(
          "1", nia.query("SELECT count(*) FROM blobs WHERE hash = '" + betaFile + "'").strip());

      fleet.offline(
          nia,
          () -> {
            nia.createSpecs("nia-a", 1, BODY_BYTES, "alpha");
            nia.apiOk("spec", "update", "b-1", "--assignee", "nia");
          });
      main.sailOk("fde", "revoke", "nia", "beta");
      var revoked = nia.sail("sync");

      assertEquals(0, revoked.exit(), revoked::output);
      assertTrue(
          revoked.output().contains("project beta: dropped from this box with the"),
          revoked::output);
      fleet.assertScopedTo(nia, "alpha");
      assertEquals(
          "0", nia.query("SELECT count(*) FROM blobs WHERE hash = '" + betaFile + "'").strip());
      assertEquals("1", main.query("SELECT count(*) FROM specs WHERE id = 'nia-a-1'").strip());
      assertEquals(
          "",
          main.query("SELECT COALESCE(assignee, '') FROM specs WHERE id = 'b-1'").strip(),
          "main holds the spec without the node's unpushed claim");

      main.serving(
          () -> {
            for (var doomed : List.of("a-1", "b-2")) {
              main.apiOk("spec", "update", doomed, "--status", "archived");
              main.apiOk("spec", "prune", doomed, "--apply");
            }
          });
      nia.sailOk("sync");
      uma.sailOk("sync");

      assertEquals(
          "1",
          nia.query(
                  "SELECT count(*) FROM change_log WHERE entity_type = 'spec' AND entity_id ="
                      + " 'a-1' AND kind = 'erasure'")
              .strip(),
          "the erasure of a spec the node held reached it");
      assertEquals(
          "0",
          nia.query("SELECT count(*) FROM change_log WHERE entity_id IN ('b-1', 'b-2')").strip(),
          "nothing of another project's erasure crossed");
      fleet.assertScopedTo(nia, "alpha");
      fleet.assertConverged(uma);
      fleet.assertHeadsAgree(uma);
      assertEquals(0, NativeFleet.json(nia.sailOk("sync", "--json")).get("pulled"));

      main.sailOk("fde", "revoke", "ole", "alpha");
      var oleRevoked = ole.sail("sync");

      assertEquals(0, oleRevoked.exit(), oleRevoked::output);
      assertTrue(
          oleRevoked.output().contains("project alpha: dropped from this box with the"),
          oleRevoked::output);
      assertEquals(
          "",
          ole.query("SELECT id FROM specs ORDER BY id").strip(),
          "a scoped node drops the project main revoked from it");
    }
  }

  @Test
  void aScopeChangedAfterCreationLeavesEachBoxHoldingExactlyWhatItAdmits() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      main.catalog("alpha", "beta");
      var nia = fleet.node("nia", fleet.candidate(), "--scoped");
      var uma = fleet.node("uma", fleet.candidate());
      var eng = fleet.node("eng", fleet.candidate());
      main.sailOk("fde", "grant", "nia", "alpha");
      main.sailOk("fde", "grant", "nia", "beta");
      main.serving(
          () -> {
            main.createSpecs("a", 1, BODY_BYTES, "alpha");
            main.createSpecs("b", 1, BODY_BYTES, "beta");
          });
      for (var node : List.of(nia, uma, eng)) {
        node.sailOk("sync");
      }
      fleet.assertScopedTo(nia, "alpha", "beta");
      fleet.assertConverged(uma, eng);
      var engKeys =
          "SELECT count(*) FROM fde_ssh_keys k JOIN fdes f ON f.id = k.fde_id"
              + " WHERE f.handle = 'eng'";
      assertEquals("1", main.query(engKeys).strip());

      main.sailOk("fde", "update", "eng", "--scoped", "--grant", "alpha");
      var narrowed = eng.sail("sync");

      assertEquals(0, narrowed.exit(), narrowed::output);
      assertTrue(
          narrowed.output().contains("project beta: dropped from this box with the"),
          narrowed::output);
      fleet.assertScopedTo(eng, "alpha");
      assertEquals("1", main.query(engKeys).strip(), "eng keeps the key it syncs with");
      assertEquals("gone", eng.shOk(BETA_ON_DISK).strip());
      uma.sailOk("sync");
      fleet.assertConverged(uma);

      fleet.offline(nia, () -> nia.createSpecs("nia-a", 1, BODY_BYTES, "alpha"));
      main.sailOk("fde", "revoke", "nia", "beta");
      nia.sailOk("sync");

      fleet.assertScopedTo(nia, "alpha");
      assertEquals("1", main.query("SELECT count(*) FROM specs WHERE id = 'nia-a-1'").strip());
      assertEquals("gone", nia.shOk(BETA_ON_DISK).strip());
      assertEquals(
          "eng|\nnia|nia@example.dev\nuday|\numa|",
          nia.query("SELECT handle, email FROM fdes ORDER BY handle").strip(),
          "a scoped node holds the roster main lists to it, and nothing it no longer does");
      assertEquals("0", nia.query("SELECT count(*) FROM fdes WHERE status = 'disabled'").strip());
      uma.sailOk("sync");
      fleet.assertConverged(uma);

      main.sailOk("fde", "update", "nia", "--unscoped");
      nia.sailOk("sync");
      uma.sailOk("sync");

      fleet.assertConverged(nia, uma);
      fleet.assertHeadsAgree(uma);
      assertEquals(
          "eng|eng@example.dev\nnia|nia@example.dev\nuday|uday@example.dev\numa|uma@example.dev",
          nia.query("SELECT handle, email FROM fdes ORDER BY handle").strip(),
          "a widened node holds the whole roster again");
    }
  }

  @Test
  void everyBoxHoldsTheAuthorsTimesAndAScopedNodeTakesNothingItWouldLose() throws Exception {
    try (var fleet = NativeFleet.openOrSkip()) {
      var main = fleet.main("uday", fleet.candidate());
      main.catalog("alpha", "beta");
      var nia = fleet.node("nia", fleet.candidate(), "--scoped");
      var uma = fleet.node("uma", fleet.candidate());
      main.sailOk("fde", "grant", "nia", "alpha");
      main.serving(() -> main.createSpecs("m", 2, 1_000, "alpha"));

      var first = nia.sailOk("sync");
      uma.sailOk("sync");
      assertFalse(first.contains("dropped"), first);

      fleet.offline(
          nia,
          () -> {
            nia.createSpecs("n", 1, 1_000, "alpha");
            var refused = nia.api("spec", "create", "--id", "c-beta", "--title", "C", "-p", "beta");
            assertNotEquals(0, refused.exit(), refused::output);
            assertTrue(
                refused
                    .output()
                    .contains(
                        "Project 'beta' isn't on this box. Projects here: alpha. If an admin"
                            + " granted it since your last sync, run: sail sync. Otherwise ask an"
                            + " admin to grant it on main: sail fde grant nia beta."),
                refused::output);
          });
      nia.sailOk("sync");
      uma.sailOk("sync");

      var times = "SELECT id, project, created_at, updated_at FROM specs ORDER BY id";
      assertEquals(
          "m-1|alpha\nm-2|alpha\nn-1|alpha",
          main.query("SELECT id, project FROM specs ORDER BY id").strip());
      assertEquals(main.query(times), nia.query(times), "nia holds main's times");
      assertEquals(main.query(times), uma.query(times), "uma holds main's times");

      main.sailOk("fde", "revoke", "nia", "alpha");
      var revoked = nia.sailOk("sync");

      assertEquals(
          1,
          revoked.split("project alpha: dropped from this box", -1).length - 1,
          "the drop is announced once: " + revoked);
      assertFalse(revoked.contains("unpushed"), revoked);
    }
  }

  private static final String DOOMED_RUN = "019fee00-0000-7000-8000-00000000d00d";

  private static final String RUN_OF_DOOMED =
      """
      INSERT INTO runs (id, project, spec_id, agent, status, started_at, node, role, rev)
      VALUES ('%1$s', 'demo', 'doomed-1', 'claude', 'completed', 1788220800000,
          'uday', 'build', '1-d00d');
      INSERT INTO change_log (entity_type, entity_id, rev, actor, recorded_at, origin, deleted,
          snapshot)
      VALUES ('run', '%1$s', '1-d00d', 'uday', 1788220800000, 'local', 0,
          '{"id": "%1$s", "project": "demo", "spec_id": "doomed-1", "node": "uday",
            "role": "build", "agent": "claude", "status": "completed",
            "started_at": "2026-09-01T00:00:00Z", "repos": [], "principals": []}');
      INSERT INTO change_heads (entity_type, entity_id, seq)
      VALUES ('run', '%1$s', last_insert_rowid());\
      """
          .formatted(DOOMED_RUN);

  private static List<String> pendingConflicts(NativeFleet.Box box) throws Exception {
    return NativeFleet.jsonList(box.sailOk("conflicts", "--json")).stream()
        .map(conflict -> (String) conflict.get("entity"))
        .toList();
  }
}
