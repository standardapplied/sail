/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.regex.Pattern;

/**
 * Validates project names, snapshot labels, service names, and other user-provided identifiers to
 * prevent path traversal and shell injection. Shared across all commands and config parsers.
 */
public final class NameValidator {

  private static final int MAX_LENGTH = 63;
  private static final int MAX_SPEC_ID_LENGTH = 80;
  private static final Pattern PROJECT_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
  private static final Pattern SPEC_ID = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
  private static final Pattern SNAPSHOT_LABEL = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*$");
  private static final Pattern SERVICE_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*$");
  private static final Pattern POSIX_USERNAME = Pattern.compile("^[a-z_][a-z0-9_-]*$");
  private static final Pattern FDE_HANDLE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");
  private static final int MAX_USERNAME_LENGTH = 32;
  private static final Pattern VERSION = Pattern.compile("^\\d+(\\.\\d+)*$");
  private static final Pattern SAFE_PATH = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._/-]*$");
  private static final Pattern GIT_REF = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._/-]*$");
  static final Pattern GITHUB_REPO = Pattern.compile("^[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+$");
  private static final Pattern SCHEME_GIT_URL = Pattern.compile("^(https?|ssh|git)://[^\\s]+$");
  private static final Pattern SCP_GIT_URL =
      Pattern.compile("^[a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+:[^\\s]+$");

  private NameValidator() {}

  /** Validates a project name. Throws if invalid. */
  public static void requireValidProjectName(String name) {
    if (name == null || name.length() > MAX_LENGTH || !PROJECT_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "Invalid project name: '"
              + name
              + "'. Must match [a-z0-9][a-z0-9-]*, max "
              + MAX_LENGTH
              + " characters.");
    }
  }

  /** Validates a spec id. Throws if invalid. */
  public static void requireValidSpecId(String specId) {
    if (specId == null
        || specId.length() > MAX_SPEC_ID_LENGTH
        || !SPEC_ID.matcher(specId).matches()) {
      throw new IllegalArgumentException(
          "Invalid spec id: '"
              + specId
              + "'. Must match [a-z0-9][a-z0-9-]*, max "
              + MAX_SPEC_ID_LENGTH
              + " characters.");
    }
  }

  /**
   * Validates an FDE handle. The handle is interpolated into the {@code sail} user's {@code
   * authorized_keys} forced command, so the conservative charset (no whitespace, quotes, or
   * newlines) is a security boundary, not just hygiene — it is enforced at creation so a malformed
   * handle can never reach the renderer, where it would otherwise fail the whole-file regeneration
   * and lock out key sync for every FDE.
   */
  public static void requireValidFdeHandle(String handle) {
    if (handle == null || handle.length() > MAX_LENGTH || !FDE_HANDLE.matcher(handle).matches()) {
      throw new IllegalArgumentException(
          "Invalid FDE handle: '"
              + handle
              + "'. Must match [A-Za-z0-9][A-Za-z0-9._-]*, max "
              + MAX_LENGTH
              + " characters.");
    }
  }

  /** Validates a snapshot label. Throws if invalid. */
  public static void requireValidSnapshotLabel(String label) {
    if (label == null || label.length() > MAX_LENGTH || !SNAPSHOT_LABEL.matcher(label).matches()) {
      throw new IllegalArgumentException(
          "Invalid snapshot label: '"
              + label
              + "'. Must match [a-zA-Z0-9][a-zA-Z0-9._-]*, max "
              + MAX_LENGTH
              + " characters.");
    }
  }

  /** Validates a service name. Throws if invalid. */
  public static void requireValidServiceName(String service) {
    if (service == null
        || service.length() > MAX_LENGTH
        || !SERVICE_NAME.matcher(service).matches()) {
      throw new IllegalArgumentException(
          "Invalid service name: '"
              + service
              + "'. Must match [a-zA-Z0-9][a-zA-Z0-9._-]*, max "
              + MAX_LENGTH
              + " characters.");
    }
  }

  /** Validates a POSIX username (ssh.user from sail.yaml). Throws if invalid. */
  public static void requireValidSshUser(String user) {
    if (user == null
        || user.length() > MAX_USERNAME_LENGTH
        || !POSIX_USERNAME.matcher(user).matches()) {
      throw new IllegalArgumentException(
          "Invalid ssh.user: '"
              + user
              + "'. Must be a valid POSIX username ([a-z_][a-z0-9_-]*), max "
              + MAX_USERNAME_LENGTH
              + " characters.");
    }
  }

  /** Validates a runtime version string (e.g. "22", "3.9.9", "24.13.1"). Throws if invalid. */
  public static void requireValidVersion(String version, String field) {
    if (version == null || !VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException(
          "Invalid "
              + field
              + ": '"
              + version
              + "'. Must be a dotted numeric version (e.g. 3.9.9).");
    }
  }

  /**
   * Validates a filesystem path segment (repos[].path). Rejects path traversal ({@code ..}),
   * absolute paths, and shell metacharacters. Throws if invalid.
   */
  public static void requireSafePath(String path, String field) {
    if (path == null || !SAFE_PATH.matcher(path).matches() || containsDotDot(path)) {
      throw new IllegalArgumentException(
          "Invalid "
              + field
              + ": '"
              + path
              + "'. Must match [a-zA-Z0-9][a-zA-Z0-9._/-]* with no '..' segments.");
    }
  }

  /** Validates a git branch or tag name. Throws if invalid. */
  public static void requireValidGitRef(String ref, String field) {
    if (ref == null || !GIT_REF.matcher(ref).matches() || containsDotDot(ref)) {
      throw new IllegalArgumentException(
          "Invalid " + field + ": '" + ref + "'. Must match [a-zA-Z0-9][a-zA-Z0-9._/-]*.");
    }
  }

  /** Validates a GitHub repo identifier (owner/name). Throws if invalid. */
  public static void requireValidGitHubRepo(String repo) {
    if (repo == null || !GITHUB_REPO.matcher(repo).matches()) {
      throw new IllegalArgumentException(
          "Invalid GitHub repository: '"
              + repo
              + "'. Must be in 'owner/name' format (e.g. acme-org/backend).");
    }
  }

  /**
   * Validates a git remote URL. Accepts {@code https://}, {@code http://}, {@code ssh://}, {@code
   * git://} URLs and scp-style {@code user@host:path} remotes. Rejects anything else — in
   * particular anything starting with {@code -}, which {@code git clone} would otherwise interpret
   * as an option (e.g. {@code --upload-pack=...}) rather than a URL.
   */
  public static void requireValidGitUrl(String url, String field) {
    var ok =
        url != null
            && !url.startsWith("-")
            && (SCHEME_GIT_URL.matcher(url).matches() || SCP_GIT_URL.matcher(url).matches());
    if (!ok) {
      throw new IllegalArgumentException(
          "Invalid "
              + field
              + ": '"
              + url
              + "'. Must be an https://, ssh://, git:// or user@host:path git URL.");
    }
  }

  private static boolean containsDotDot(String value) {
    return value.contains("..");
  }
}
