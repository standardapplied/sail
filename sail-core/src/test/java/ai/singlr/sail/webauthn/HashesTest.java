/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class HashesTest {

  @Test
  void sha256MatchesJdk() throws Exception {
    var input = "abc".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(input), Hashes.sha256(input));
  }

  @Test
  void unknownAlgorithmFailsLoud() {
    var ex =
        assertThrows(IllegalStateException.class, () -> Hashes.digest("NO-SUCH-ALG", new byte[0]));
    assertTrue(ex.getMessage().contains("NO-SUCH-ALG"));
  }
}
