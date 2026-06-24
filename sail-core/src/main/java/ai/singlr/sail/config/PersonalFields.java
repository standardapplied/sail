/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.util.List;
import java.util.Map;

/**
 * The fields of a project definition that belong to whoever works a box, not to the project the
 * fleet shares: the git identity commits are authored with, the SSH key authorized into that box's
 * containers, and the local path to the private key git pushes with. {@link #redact} rewrites the
 * git identity and authorized keys to {@code ${...}} placeholders and drops the private-key path
 * entirely, so the catalogued, synced definition every box agrees on never carries one engineer's
 * identity or — the part that matters for security — their keys or key paths. Each box fills the
 * placeholders back in locally at provision time from its own identity (see {@link
 * PlaceholderResolver}); the dropped key path defaults to each box's own SSH key.
 *
 * <p>The transform is pure and idempotent: redacting an already-redacted definition returns it
 * unchanged, and a definition with no personal fields is returned byte-for-byte so a sync sees no
 * spurious change. Because it is deterministic, two boxes that redact the same concrete definition
 * reach identical content and the sync engine converges them without a conflict.
 */
public final class PersonalFields {

  private static final String GIT = "git";
  private static final String NAME = "name";
  private static final String EMAIL = "email";
  private static final String SSH_KEY = "ssh_key";
  private static final String SSH = "ssh";
  private static final String AUTHORIZED_KEYS = "authorized_keys";

  private static final List<String> SSH_KEY_PLACEHOLDER =
      List.of(PlaceholderResolver.token(PlaceholderResolver.SSH_PUBLIC_KEY));

  private PersonalFields() {}

  /**
   * Returns the definition with the developer's git identity and authorized SSH keys replaced by
   * placeholders and the local {@code git.ssh_key} path dropped, or the input unchanged when there
   * is nothing personal to redact.
   */
  public static String redact(String definition) {
    if (Strings.isBlank(definition)) {
      return definition;
    }
    var root = YamlUtil.parseMap(definition);
    var changed = redactGit(root) | redactSsh(root);
    return changed ? YamlUtil.dumpToString(root) : definition;
  }

  @SuppressWarnings("unchecked")
  private static boolean redactGit(Map<String, Object> root) {
    if (!(root.get(GIT) instanceof Map<?, ?> git)) {
      return false;
    }
    var fields = (Map<String, Object>) git;
    var changed = placeholder(fields, NAME, PlaceholderResolver.GIT_NAME);
    changed = placeholder(fields, EMAIL, PlaceholderResolver.GIT_EMAIL) || changed;
    return strip(fields, SSH_KEY) || changed;
  }

  private static boolean strip(Map<String, Object> fields, String key) {
    if (!fields.containsKey(key)) {
      return false;
    }
    fields.remove(key);
    return true;
  }

  @SuppressWarnings("unchecked")
  private static boolean redactSsh(Map<String, Object> root) {
    if (!(root.get(SSH) instanceof Map<?, ?> ssh)) {
      return false;
    }
    var fields = (Map<String, Object>) ssh;
    if (!fields.containsKey(AUTHORIZED_KEYS)
        || SSH_KEY_PLACEHOLDER.equals(fields.get(AUTHORIZED_KEYS))) {
      return false;
    }
    fields.put(AUTHORIZED_KEYS, SSH_KEY_PLACEHOLDER);
    return true;
  }

  private static boolean placeholder(Map<String, Object> fields, String key, String name) {
    var token = PlaceholderResolver.token(name);
    if (!fields.containsKey(key) || token.equals(fields.get(key))) {
      return false;
    }
    fields.put(key, token);
    return true;
  }
}
