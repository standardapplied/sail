/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncIdentityTest {

  private static final String PUB =
      "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITESTKEYBLOB sail-sync@node";

  @TempDir Path dir;

  private SyncIdentity identity(ShellExec shell) {
    return new SyncIdentity(shell, dir.resolve("sync_ed25519"), dir.resolve("sync_ed25519.pub"));
  }

  @Test
  void ensureGeneratesTheKeypairAndReturnsItsPublicLine() throws Exception {
    var keygen = new FakeKeygen(0);
    var pub = identity(keygen).ensure("sail-sync@node");

    assertEquals(PUB, pub);
    assertTrue(keygen.ran, "ssh-keygen should run when no key exists");
    assertTrue(Files.isRegularFile(dir.resolve("sync_ed25519")));
    assertTrue(Files.isRegularFile(dir.resolve("sync_ed25519.pub")));
  }

  @Test
  void ensureIsIdempotentAndReusesAnExistingKey() throws Exception {
    Files.writeString(dir.resolve("sync_ed25519"), "PRIVATE");
    Files.writeString(dir.resolve("sync_ed25519.pub"), PUB + "\n");

    var keygen = new FakeKeygen(0);
    var pub = identity(keygen).ensure("sail-sync@node");

    assertEquals(PUB, pub);
    assertFalse(keygen.ran, "an existing keypair must be reused untouched");
  }

  @Test
  void ensureSurfacesAKeygenFailure() {
    var failing = new FakeKeygen(1);

    var error = assertThrows(IOException.class, () -> identity(failing).ensure("sail-sync@node"));
    assertTrue(error.getMessage().contains("ssh-keygen failed"));
  }

  @Test
  void existsTracksBothHalvesOfTheKeypair() throws Exception {
    var identity = identity(new FakeKeygen(0));
    assertFalse(identity.exists());

    Files.writeString(dir.resolve("sync_ed25519"), "PRIVATE");
    assertFalse(identity.exists(), "private half alone is not a usable identity");

    Files.writeString(dir.resolve("sync_ed25519.pub"), PUB);
    assertTrue(identity.exists());
  }

  @Test
  void resolveReturnsTheKeyOnlyOnceItExists() throws Exception {
    var key = dir.resolve("sync_ed25519");
    assertTrue(SyncIdentity.resolve(key).isEmpty(), "no managed key yet → default-key behaviour");

    Files.writeString(key, "PRIVATE");
    assertEquals(key, SyncIdentity.resolve(key).orElseThrow());
  }

  @Test
  void publicKeyLineIsTrimmed() throws Exception {
    Files.writeString(dir.resolve("sync_ed25519.pub"), "  " + PUB + "  \n");
    assertEquals(PUB, identity(new FakeKeygen(0)).publicKeyLine());
  }

  private final class FakeKeygen implements ShellExec {

    private final int exitCode;
    private boolean ran;

    private FakeKeygen(int exitCode) {
      this.exitCode = exitCode;
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      ran = true;
      if (exitCode != 0) {
        return new Result(exitCode, "", "boom");
      }
      var target = Path.of(command.get(command.indexOf("-f") + 1));
      Files.writeString(target, "PRIVATE");
      Files.writeString(Path.of(target + ".pub"), PUB + "\n");
      return new Result(0, "", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
