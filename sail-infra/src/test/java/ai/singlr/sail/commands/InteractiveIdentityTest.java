/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.PlaceholderResolver;
import ai.singlr.sail.engine.LocalIdentity;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine.Help.Ansi;

class InteractiveIdentityTest {

  @TempDir Path tempDir;
  private final InputStream originalStdin = System.in;

  @AfterEach
  void restoreStdin() {
    System.setIn(originalStdin);
    ConsoleHelper.resetStdin();
  }

  private LocalIdentity box(String name, String email) {
    var shell = new ScriptedShellExecutor().onOk("user.name", name).onOk("user.email", email);
    return new LocalIdentity(shell, tempDir.resolve("absent.pub"));
  }

  private LocalIdentity boxWithKey(String key) throws Exception {
    var path = tempDir.resolve("sail.pub");
    Files.writeString(path, key);
    return new LocalIdentity(new ScriptedShellExecutor(), path);
  }

  private static void stdin(String input) {
    ConsoleHelper.resetStdin();
    System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
  }

  private static InteractiveIdentity resolver(LocalIdentity box, boolean canPrompt) {
    return new InteractiveIdentity(
        box, canPrompt, new PrintStream(new ByteArrayOutputStream()), Ansi.OFF);
  }

  @Test
  void canPromptOnlyWhenInteractiveNonJsonNotDryRunWithAConsole() {
    assertTrue(InteractiveIdentity.canPrompt(false, false, false, true));
    assertFalse(
        InteractiveIdentity.canPrompt(true, false, false, true), "--yes is non-interactive");
    assertFalse(
        InteractiveIdentity.canPrompt(false, true, false, true), "--json is non-interactive");
    assertFalse(
        InteractiveIdentity.canPrompt(false, false, true, true), "--dry-run does not prompt");
    assertFalse(InteractiveIdentity.canPrompt(false, false, false, false), "no console, no prompt");
  }

  @Test
  void promptOffersTheGitConfigDefaultAcceptedWithEnter() {
    stdin("\n");
    var id = resolver(box("Uday Chandra", "uday@singlr.ai"), true);

    assertEquals("Uday Chandra", id.apply(PlaceholderResolver.GIT_NAME));
  }

  @Test
  void aTypedValueOverridesTheDefault() {
    stdin("Mady Lee\n");
    var id = resolver(box("Uday Chandra", "uday@singlr.ai"), true);

    assertEquals("Mady Lee", id.apply(PlaceholderResolver.GIT_NAME));
  }

  @Test
  void promptsAsRequiredWhenTheBoxHasNoGitIdentity() {
    stdin("mady@singlr.ai\n");
    var id = resolver(box("", ""), true);

    assertEquals("mady@singlr.ai", id.apply(PlaceholderResolver.GIT_EMAIL));
  }

  @Test
  void nonInteractiveUsesTheBoxIdentitySilently() {
    var id = resolver(box("Uday Chandra", "uday@singlr.ai"), false);

    assertEquals("uday@singlr.ai", id.apply(PlaceholderResolver.GIT_EMAIL));
  }

  @Test
  void nonInteractiveFailsLoudWithTheFixWhenIdentityIsMissing() {
    var id = resolver(box("", ""), false);

    var ex =
        assertThrows(IllegalStateException.class, () -> id.apply(PlaceholderResolver.GIT_NAME));
    assertTrue(ex.getMessage().contains("git config --global"));
  }

  @Test
  void sshPublicKeyIsBoxResolvedNeverPrompted() throws Exception {
    var key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILDpT0mMcK mady@box";
    var id = resolver(boxWithKey(key), true);

    assertEquals(key, id.apply(PlaceholderResolver.SSH_PUBLIC_KEY));
  }
}
