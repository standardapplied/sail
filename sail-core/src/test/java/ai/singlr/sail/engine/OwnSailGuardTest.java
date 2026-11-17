/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OwnSailGuardTest {

  private static final Path PERSONS = Path.of("/home/dev/.sail");
  private static final Path SYSTEM = Path.of("/var/lib/sail");
  private static final Path OWN = Path.of("/work/sail-core/target/test-home-1/.sail");

  @Test
  void aJvmWithASailOfItsOwnReachesNoRealOne() {
    assertEquals(
        List.of(),
        OwnSailGuard.reached(OWN, OWN, OWN.resolve("locks"), Optional.of(PERSONS), SYSTEM));
  }

  @Test
  void aJvmInThePersonsHomeReachesTheirSail() {
    assertEquals(
        List.of(
            "its Sail home /home/dev/.sail is the person's own",
            "its data directory /home/dev/.sail is the person's own",
            "its locks /home/dev/.sail/locks is the person's own"),
        OwnSailGuard.reached(
            PERSONS, PERSONS, PERSONS.resolve("locks"), Optional.of(PERSONS), SYSTEM));
  }

  /** On a box with Sail installed the data directory is the system one, whatever the home is. */
  @Test
  void aJvmOnABoxWithSailInstalledReachesItsProvisionedSail() {
    assertEquals(
        List.of(
            "its data directory /var/lib/sail is the box's provisioned Sail",
            "its locks /var/lib/sail/locks is the box's provisioned Sail"),
        OwnSailGuard.reached(OWN, SYSTEM, SYSTEM.resolve("locks"), Optional.of(PERSONS), SYSTEM));
  }

  @Test
  void aJvmWhoseHomeIsUnknownIsHeldOnlyToTheProvisionedSail() {
    assertEquals(
        List.of(),
        OwnSailGuard.reached(PERSONS, PERSONS, PERSONS.resolve("locks"), Optional.empty(), SYSTEM));
  }

  /** This run never takes the box's provisioned Sail, so a box with Sail installed is safe too. */
  @Test
  void thisRunNeverTakesTheBoxsProvisionedSail() {
    assertFalse(SailPaths.takesTheSystemSail());
  }

  /** The run this test is part of has its own Sail: the guard let it start. */
  @Test
  void thisRunHasASailOfItsOwn() {
    assertEquals(
        List.of(),
        OwnSailGuard.reached(
            SailPaths.sailDir(),
            SailPaths.dataDir(),
            SailPaths.locksDir(),
            Optional.ofNullable(System.getenv("HOME")).map(home -> Path.of(home, ".sail")),
            SailPaths.systemDataDir()));
  }
}
