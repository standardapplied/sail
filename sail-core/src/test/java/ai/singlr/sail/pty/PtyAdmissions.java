/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;

/** Test hosts' admissions. */
public final class PtyAdmissions {

  /**
   * A host that keeps no projects and no rooms: every project-bound and room-bound create is
   * refused.
   */
  public static final PtyAdmission NONE =
      new PtyAdmission() {
        @Override
        public void box(PtyIdentity.Person who) {}

        @Override
        public void project(String project, PtyIdentity.Person who) throws IOException {
          throw new IOException("This host keeps no projects, so no session can be opened in one.");
        }

        @Override
        public void room(String room, String project, PtyIdentity.Person who) throws IOException {
          throw new IOException("This host keeps no rooms, so no session can be pinned to one.");
        }
      };

  private PtyAdmissions() {}
}
