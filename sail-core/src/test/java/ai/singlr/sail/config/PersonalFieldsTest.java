/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalFieldsTest {

  @Test
  void redactsGitIdentityToPlaceholders() {
    var redacted = PersonalFields.redact("git:\n  name: Uday Chandra\n  email: uday@example.com\n");
    var git = git(redacted);
    assertEquals("${GIT_NAME}", git.get("name"));
    assertEquals("${GIT_EMAIL}", git.get("email"));
  }

  @Test
  void redactsAuthorizedKeysToASinglePlaceholder() {
    var redacted =
        PersonalFields.redact(
            "ssh:\n  user: dev\n  authorized_keys:\n    - ssh-ed25519 AAAA main\n"
                + "    - ssh-ed25519 BBBB laptop\n");
    var ssh = ssh(redacted);
    assertEquals(List.of("${SSH_PUBLIC_KEY}"), ssh.get("authorized_keys"));
    assertEquals("dev", ssh.get("user"), "the shared ssh user is left alone");
  }

  @Test
  void dropsTheLocalGitSshKeyPath() {
    var redacted =
        PersonalFields.redact(
            "git:\n  name: Uday\n  email: uday@example.com\n  auth: ssh\n"
                + "  ssh_key: ~/.ssh/id_ed25519\n");
    var git = git(redacted);
    assertFalse(git.containsKey("ssh_key"), "the local private-key path is never synced");
    assertEquals("ssh", git.get("auth"), "git.auth (token vs ssh) stays — a project choice");
    assertEquals("${GIT_NAME}", git.get("name"));
    assertFalse(
        redacted.contains("id_ed25519"), "no key path leaks into the catalogued definition");
  }

  @Test
  void strippingSshKeyAloneCountsAsAChange() {
    var redacted = PersonalFields.redact("git:\n  name: '${GIT_NAME}'\n  ssh_key: ~/.ssh/k\n");
    assertFalse(git(redacted).containsKey("ssh_key"));
  }

  @Test
  void leavesSharedFieldsUntouched() {
    var redacted =
        PersonalFields.redact(
            "name: acme\nimage: ubuntu/24.04\n"
                + "resources:\n  cpu: 4\n  memory: 8GB\n  disk: 50GB\n"
                + "git:\n  name: Uday\n  email: uday@example.com\n  auth: ssh\n");
    var root = YamlUtil.parseMap(redacted);
    assertEquals("acme", root.get("name"));
    assertEquals("ubuntu/24.04", root.get("image"));
    assertEquals("ssh", git(redacted).get("auth"), "git.auth is a project choice, not personal");
  }

  @Test
  void returnsTheInputUnchangedWhenNothingIsPersonal() {
    var definition = "name: acme\nimage: ubuntu/24.04\n";
    assertSame(definition, PersonalFields.redact(definition), "no parse/dump churn when untouched");
  }

  @Test
  void isIdempotent() {
    var once = PersonalFields.redact("git:\n  name: Uday\n  email: uday@example.com\n");
    assertEquals(once, PersonalFields.redact(once));
  }

  @Test
  void redactingAnAlreadyRedactedDefinitionDoesNotReportAChange() {
    var redacted = PersonalFields.redact("git:\n  name: Uday\n  email: uday@example.com\n");
    assertSame(redacted, PersonalFields.redact(redacted), "second pass is a no-op, returns same");
  }

  @Test
  void toleratesMissingGitAndSshBlocks() {
    var definition = "name: acme\n";
    assertSame(definition, PersonalFields.redact(definition));
  }

  @Test
  void redactsGitEvenWhenSshIsAbsentAndViceVersa() {
    assertTrue(
        PersonalFields.redact("git:\n  name: U\n  email: u@e.com\n").contains("${GIT_NAME}"));
    assertTrue(
        PersonalFields.redact("ssh:\n  authorized_keys:\n    - key\n")
            .contains("${SSH_PUBLIC_KEY}"));
  }

  @Test
  void survivesBlankInput() {
    assertSame("", PersonalFields.redact(""));
    assertEquals("   ", PersonalFields.redact("   "));
  }

  @Test
  void redactedDefinitionNoLongerCarriesTheOriginalIdentity() {
    var redacted =
        PersonalFields.redact(
            "git:\n  name: Uday Chandra\n  email: uday@example.com\n"
                + "ssh:\n  authorized_keys:\n    - ssh-ed25519 SECRETKEY main\n");
    assertFalse(redacted.contains("Uday Chandra"));
    assertFalse(redacted.contains("uday@example.com"));
    assertFalse(redacted.contains("SECRETKEY"));
  }

  @SuppressWarnings("unchecked")
  private static java.util.Map<String, Object> git(String yaml) {
    return (java.util.Map<String, Object>) YamlUtil.parseMap(yaml).get("git");
  }

  @SuppressWarnings("unchecked")
  private static java.util.Map<String, Object> ssh(String yaml) {
    return (java.util.Map<String, Object>) YamlUtil.parseMap(yaml).get("ssh");
  }
}
