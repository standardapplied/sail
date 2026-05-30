/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Model for {@code /etc/sail/host.yaml} — server-side state written by {@code sail host init} and
 * read by all other commands.
 */
public record HostYaml(
    String storageBackend,
    String pool,
    String poolDisk,
    String bridge,
    String baseProfile,
    String image,
    String incusVersion,
    String serverIp,
    String initializedAt,
    WebauthnConfig webauthn) {

  public HostYaml {
    webauthn = webauthn == null ? WebauthnConfig.disabled() : webauthn;
  }

  /** Provisioning-only constructor; passkey settings default to disabled. */
  public HostYaml(
      String storageBackend,
      String pool,
      String poolDisk,
      String bridge,
      String baseProfile,
      String image,
      String incusVersion,
      String serverIp,
      String initializedAt) {
    this(
        storageBackend,
        pool,
        poolDisk,
        bridge,
        baseProfile,
        image,
        incusVersion,
        serverIp,
        initializedAt,
        WebauthnConfig.disabled());
  }

  public static final String DEFAULT_POOL = "devpool";
  public static final String DEFAULT_BRIDGE = "incusbr0";
  public static final String DEFAULT_PROFILE = "singlr-base";
  public static final String DEFAULT_IMAGE = "ubuntu/24.04";
  public static final String BACKEND_DIR = "dir";
  public static final String BACKEND_ZFS = "zfs";

  public boolean isDir() {
    return BACKEND_DIR.equals(storageBackend);
  }

  public boolean isZfs() {
    return BACKEND_ZFS.equals(storageBackend);
  }

  @SuppressWarnings("unchecked")
  public static HostYaml fromMap(Map<String, Object> map) {
    var backend = Objects.requireNonNullElse((String) map.get("storage_backend"), BACKEND_ZFS);
    return new HostYaml(
        backend,
        (String) map.get("pool"),
        (String) map.get("pool_disk"),
        (String) map.get("bridge"),
        (String) map.get("base_profile"),
        (String) map.get("image"),
        (String) map.get("incus_version"),
        (String) map.get("server_ip"),
        (String) map.get("initialized_at"),
        WebauthnConfig.fromMap((Map<String, Object>) map.get("webauthn")));
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("storage_backend", storageBackend);
    map.put("pool", pool);
    map.put("pool_disk", poolDisk);
    map.put("bridge", bridge);
    map.put("base_profile", baseProfile);
    map.put("image", image);
    map.put("incus_version", incusVersion);
    map.put("server_ip", serverIp);
    map.put("initialized_at", initializedAt);
    if (webauthn != null && webauthn.isConfigured()) {
      map.put("webauthn", webauthn.toMap());
    }
    return map;
  }
}
