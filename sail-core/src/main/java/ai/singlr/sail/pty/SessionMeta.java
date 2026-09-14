/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The sidecar a session keeps beside its ring: everything a successor host needs that the master
 * descriptor cannot carry — its {@link PtySession.Origin}, working directory, geometry, who held
 * the keyboard, the child's pid, when it was born and under which host. Rewritten whole on every
 * change of a field it holds (small and rare, never on the byte path), by temp-and-rename so a
 * crash mid-write leaves the previous file rather than half of one; owner-only like the ring.
 */
public record SessionMeta(
    PtySession.Origin origin,
    String cwd,
    int cols,
    int rows,
    String writerFde,
    long writerId,
    long childPid,
    long createdAt,
    String hostVersion,
    boolean everAttached) {

  private static final int VERSION = 1;

  public void write(Path path) throws IOException {
    var map = new LinkedHashMap<String, Object>();
    map.put("version", VERSION);
    map.put("name", origin.name());
    map.put("instance_id", origin.instanceId());
    map.put("owner_fde", origin.ownerFde());
    map.put("project", Objects.toString(origin.project(), ""));
    map.put("room", Objects.toString(origin.room(), ""));
    map.put("command", origin.command());
    map.put("cwd", cwd);
    map.put("cols", cols);
    map.put("rows", rows);
    map.put("writer_fde", writerFde);
    map.put("writer_id", writerId);
    map.put("child_pid", childPid);
    map.put("created_at", createdAt);
    map.put("host_version", hostVersion);
    map.put("ever_attached", everAttached);
    var temp = path.resolveSibling(path.getFileName() + ".tmp");
    Files.deleteIfExists(temp);
    try {
      Files.createFile(
          temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException notPosix) {
      Files.createFile(temp);
    }
    Files.writeString(temp, YamlUtil.dumpJson(map));
    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Reads a sidecar; anything short of a whole, well-formed one is refused with the reason. */
  public static SessionMeta read(Path path) throws IOException {
    var text = Files.readString(path);
    try {
      var map = YamlUtil.parseMapStrict(text);
      var version = number(map, "version");
      if (version != VERSION) {
        throw new IOException("sidecar version " + version + "; this build reads " + VERSION);
      }
      var origin =
          new PtySession.Origin(
              string(map, "name"),
              string(map, "instance_id"),
              string(map, "owner_fde"),
              string(map, "project"),
              string(map, "room"),
              strings(map.get("command")));
      return new SessionMeta(
          origin,
          string(map, "cwd"),
          (int) number(map, "cols"),
          (int) number(map, "rows"),
          string(map, "writer_fde"),
          number(map, "writer_id"),
          number(map, "child_pid"),
          number(map, "created_at"),
          string(map, "host_version"),
          Boolean.TRUE.equals(map.get("ever_attached")));
    } catch (IOException e) {
      throw new IOException("Session sidecar " + path + " is unreadable: " + e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new IOException("Session sidecar " + path + " is unreadable: " + e, e);
    }
  }

  private static String string(Map<String, Object> map, String key) throws IOException {
    if (!(map.get(key) instanceof String value)) {
      throw new IOException("missing field '" + key + "'");
    }
    return value;
  }

  private static long number(Map<String, Object> map, String key) throws IOException {
    if (!(map.get(key) instanceof Number value)) {
      throw new IOException("missing field '" + key + "'");
    }
    return value.longValue();
  }

  private static List<String> strings(Object value) throws IOException {
    if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
      throw new IOException("missing field 'command'");
    }
    return list.stream().map(String.class::cast).toList();
  }
}
