/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BaseImageTest {

  private static final String CT = "990054afd627" + "0".repeat(52);
  private static final String VM = "9cbfd6cffda6" + "0".repeat(52);
  private static final String CLOUD = "abfe016001e3" + "0".repeat(52);
  private static final String LOCAL = "incus image list local: --format=json";
  private static final String LOCAL_BEFORE = "incus image list local:";
  private static final String LOCAL_AFTER = "local: --format=json";
  private static final String REMOTE = "incus image list images: ubuntu/24.04 type=container";
  private static final String COPY =
      "incus image copy images:" + CT + " local: --alias ubuntu-24.04";
  private static final Remedy HEAL = Remedy.of("Heal it.");

  private static String image(String fingerprint, String type, String... aliases) {
    var names =
        String.join(",", Arrays.stream(aliases).map(a -> "{\"name\":\"" + a + "\"}").toList());
    return "{\"fingerprint\":\""
        + fingerprint
        + "\",\"type\":\""
        + type
        + "\",\"architecture\":\"x86_64\",\"aliases\":["
        + names
        + "]}";
  }

  private static String list(String... images) {
    return "[" + String.join(",", images) + "]";
  }

  private static final String HELD_CT = list(image(CT, "container", "ubuntu-24.04"));
  private static final String HELD_VM = list(image(VM, "virtual-machine", "ubuntu-24.04"));
  private static final String SERVER_CT =
      image(CT, "container", "ubuntu/noble", "ubuntu/24.04", "ubuntu/24.04/amd64");
  private static final String SERVER_VM = image(VM, "virtual-machine", "ubuntu/24.04");
  private static final String SERVER_CLOUD = image(CLOUD, "container", "ubuntu/24.04/cloud");

  private static ScriptedShellExecutor box(String held, String server) {
    return new ScriptedShellExecutor()
        .onceOnOk(LOCAL_BEFORE, held)
        .onOk(LOCAL_AFTER, HELD_CT)
        .onOk(REMOTE, server)
        .onOk("incus image copy")
        .onOk("incus image delete");
  }

  @Test
  void aFreshBoxCopiesTheContainerImageByItsFingerprintWhenTheServerListsTheVmFirst()
      throws Exception {
    var shell = box("[]", list(SERVER_VM, SERVER_CLOUD, SERVER_CT));

    assertEquals("cached", new BaseImage(shell, "ubuntu/24.04").ensure());

    assertTrue(shell.invocations().contains(COPY), shell.invocations().toString());
    assertTrue(shell.invocations().stream().noneMatch(c -> c.startsWith("incus image delete")));
  }

  @Test
  void aFreshBoxCopiesTheContainerImageWhenTheServerListsItFirst() throws Exception {
    var shell = box("[]", list(SERVER_CT, SERVER_VM));

    assertEquals("cached", new BaseImage(shell, "ubuntu/24.04").ensure());

    assertTrue(shell.invocations().contains(COPY), shell.invocations().toString());
  }

  @Test
  void aServerListingOnlyAVmImageOrNoneByThatNameCachesNothingAndSaysSo() {
    for (var server : List.of(list(SERVER_VM), list(SERVER_CLOUD))) {
      var shell = box("[]", server);

      var failure =
          assertThrows(IOException.class, () -> new BaseImage(shell, "ubuntu/24.04").ensure());

      assertEquals(
          "The image server lists no container image named ubuntu/24.04 for this box"
              + " (images:ubuntu/24.04), so none was cached.",
          failure.getMessage());
      assertTrue(shell.invocations().stream().noneMatch(c -> c.startsWith("incus image copy")));
    }
  }

  @Test
  void aVmImageHeldUnderTheAliasIsDeletedAndReplacedByTheContainerImage() throws Exception {
    var shell = box(HELD_VM, list(SERVER_VM, SERVER_CT));

    assertEquals(
        "replaced the virtual-machine image 9cbfd6cffda6 with the container image",
        new BaseImage(shell, "ubuntu/24.04").ensure());

    var ran = shell.invocations();
    assertTrue(ran.indexOf("incus image delete " + VM) >= 0, ran.toString());
    assertTrue(ran.indexOf("incus image delete " + VM) < ran.indexOf(COPY), ran.toString());
  }

  @Test
  void aContainerImageHeldUnderTheAliasIsLeftAsItIs() throws Exception {
    var shell = box(HELD_CT, list(SERVER_VM, SERVER_CT));

    assertEquals("already cached", new BaseImage(shell, "ubuntu/24.04").ensure());

    assertEquals(List.of(LOCAL), shell.invocations());
  }

  @Test
  void aCopyIncusHoldsAsAnotherTypeIsRefusedNamingWhatWasCopied() {
    var shell =
        new ScriptedShellExecutor()
            .onceOnOk(LOCAL_BEFORE, "[]")
            .onOk(LOCAL_AFTER, HELD_VM)
            .onOk(REMOTE, list(SERVER_CT))
            .onOk("incus image copy");

    var failure =
        assertThrows(IOException.class, () -> new BaseImage(shell, "ubuntu/24.04").ensure());

    assertEquals(
        "Copied images:"
            + CT
            + " as ubuntu-24.04, but Incus holds the virtual-machine image 9cbfd6cffda6 under it,"
            + " not a container image.",
        failure.getMessage());
  }

  @Test
  void aFailedListingOrCopyFailsTheCacheNamingTheCommand() {
    var shell =
        new ScriptedShellExecutor().onOk(LOCAL, "[]").onFail(REMOTE, "Failed parsing stream");

    var failure =
        assertThrows(IOException.class, () -> new BaseImage(shell, "ubuntu/24.04").ensure());

    assertEquals(
        "incus image list images: ubuntu/24.04 type=container --format=json failed: Failed"
            + " parsing stream",
        failure.getMessage());
  }

  @Test
  void aLaunchFromAVmImageIsRefusedSayingWhatIsTrueAndHowItHeals() {
    var shell = new ScriptedShellExecutor().onOk(LOCAL, HELD_VM);

    var failure =
        assertThrows(
            IOException.class, () -> new BaseImage(shell, "ubuntu/24.04").requireContainer(HEAL));

    var said = assertInstanceOf(Remedied.class, failure);
    assertEquals(
        "This runner's image ubuntu-24.04 is the virtual-machine image 9cbfd6cffda6, which it"
            + " cannot run as a container.",
        said.reason());
    assertEquals(HEAL, said.remedy());
  }

  @Test
  void aLaunchFromAContainerImageANameNotHeldOrAListingIncusRefusesGoesAhead() throws Exception {
    new BaseImage(new ScriptedShellExecutor().onOk(LOCAL, HELD_CT), "ubuntu/24.04")
        .requireContainer(HEAL);
    new BaseImage(new ScriptedShellExecutor().onOk(LOCAL, "[]"), "ubuntu/24.04")
        .requireContainer(HEAL);
    new BaseImage(new ScriptedShellExecutor().onFail(LOCAL, "no incus"), "ubuntu/24.04")
        .requireContainer(HEAL);
  }

  @Test
  void theLocalAliasIsTheImageWithItsSlashesAsDashes() {
    assertEquals(
        "ubuntu-24.04", new BaseImage(new ScriptedShellExecutor(), "ubuntu/24.04").alias());
  }
}
