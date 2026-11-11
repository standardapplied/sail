/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.api.ApiResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A8 and A9 at the container socket, the one door they are about: what the box credential in a
 * project container may do, whoever presents it, and what an agent may do through its own run
 * credential, each asked of a real box's operations as production resolves the credential. The cast
 * is {@link DoorFleet}'s: eng's box is a node of main's, where eng is a member; uday's box is main,
 * where uday is the admin.
 */
class OwnWorkSocketTest {

  private static final String CANNOT_APPROVE =
      "403 agent_lane_forbidden The credential in a project container cannot approve specs."
          + " | Do it from outside the container: 'sail spec update <id> --status <status>' on the"
          + " host, or Mast.";

  @TempDir Path tempDir;
  private DoorFleet fleet;

  @BeforeEach
  void setUp() throws Exception {
    fleet = DoorFleet.open(tempDir);
  }

  @AfterEach
  void tearDown() {
    fleet.close();
  }

  /**
   * Row 21 on eng's box: role before lane, so reassigning e1 is admin-only, as at every door, and
   * marking it done names the lane.
   */
  @Test
  void row21EngsCredentialInTheContainerIsRefusedReassigningE1AndMarkingItDone() {
    assertEquals(
        "403 forbidden_admin_only Reassigning spec 'e1' moves work between FDEs and is an"
            + " admin-only action (currently 'eng'). | Ask an admin to reassign it. You may grab a"
            + " spec only while it is unassigned.",
        said(socket("eng", "PUT", "/v1/specs/e1", "assignee=client")));
    assertEquals(CANNOT_APPROVE, said(socket("eng", "PUT", "/v1/specs/e1", "status=done")));
    assertEquals(
        "allowed", said(socket("eng", "PUT", "/v1/specs/e1", "title=retitled")), "control");
  }

  /** Row 21 on main: uday's credential is an admin's, so each refusal names the lane. */
  @Test
  void row21UdaysCredentialInTheContainerOnMainNamesTheLaneForEach() {
    assertEquals(
        "403 agent_lane_forbidden The credential in a project container cannot assign specs. |"
            + " Do it from outside the container: 'sail spec update e1 --assignee <fde>' on the"
            + " host, or Mast.",
        said(socket("uday", "PUT", "/v1/specs/e1", "assignee=client")));
    assertEquals(CANNOT_APPROVE, said(socket("uday", "PUT", "/v1/specs/e1", "status=done")));
    assertEquals(
        CANNOT_APPROVE,
        said(
            socket("uday", "POST", "/v1/specs", "id=u-ready&project=acme&title=u&status=pending")));
    assertEquals(
        CANNOT_APPROVE, said(socket("uday", "PUT", "/v1/specs/a2", "status=archived")), "archive");
    assertEquals(
        "allowed",
        said(socket("uday", "POST", "/v1/specs", "id=u-draft&project=acme&title=u")),
        "control: a draft");
  }

  private ApiResponse socket(String person, String method, String path, String form) {
    return fleet.boxes().get(person).socket(method, path, form);
  }

  private static String said(ApiResponse response) {
    if (response.status() / 100 == 2) {
      return "allowed";
    }
    var error = (Map<?, ?>) response.body().get("error");
    return response.status()
        + " "
        + error.get("code")
        + " "
        + error.get("message")
        + " | "
        + error.get("action");
  }
}
