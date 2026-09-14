/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The service side of systemd's file descriptor store, spoken directly: {@code sd_notify} datagrams
 * on {@code $NOTIFY_SOCKET} with the descriptor riding in an {@code SCM_RIGHTS} control message.
 * The manager keeps every stored descriptor across restarts and crashes of the service and hands
 * them back to the next instance as {@code LISTEN_FDS}/{@code LISTEN_FDNAMES} — the one primitive
 * that lets a pty host be replaced without its sessions noticing.
 *
 * <p>Without a notify socket (a developer run, a test) the store is a no-op. A socket that answers
 * nothing is warned about once and then ignored: a broken notify path must never refuse a session,
 * it only forfeits the handoff. {@link Receiver} is the manager's side, for tests and the native
 * self-test only.
 */
public final class SdNotify {

  private static final int AF_UNIX = 1;
  private static final int SOCK_DGRAM = 2;
  private static final int SOCK_CLOEXEC = 0x80000;
  private static final int SOL_SOCKET = 1;
  private static final int SCM_RIGHTS = 1;
  private static final int MSG_NOSIGNAL = 0x4000;
  private static final int SUN_PATH_MAX = 108;
  private static final int SOCKADDR_UN_BYTES = 2 + SUN_PATH_MAX;
  private static final int IOVEC_BYTES = 16;
  private static final int MSGHDR_BYTES = 56;
  private static final int CMSGHDR_BYTES = 16;
  private static final int MAX_FDS_PER_MESSAGE = 8;
  private static final int MAX_TEXT_BYTES = 4096;
  private static final int LISTEN_FDS_START = 3;

  /** The store speaks no notify socket at all: every push and removal is a no-op. */
  public static final SdNotify NONE = new SdNotify("");

  private record Libc(
      MethodHandle socket,
      MethodHandle bind,
      MethodHandle sendmsg,
      MethodHandle recvmsg,
      MethodHandle close) {}

  private static final Libc LIBC = loadLibc();

  private static Libc loadLibc() {
    return new Libc(
        Native.downcallCapturingErrno(
            "socket",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT)),
        Native.downcallCapturingErrno(
            "bind",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT)),
        Native.downcallCapturingErrno(
            "sendmsg",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT)),
        Native.downcallCapturingErrno(
            "recvmsg",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT)),
        Native.downcall(
            "close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)));
  }

  private final String socketPath;
  private volatile boolean warned;

  private SdNotify(String socketPath) {
    this.socketPath = socketPath;
  }

  /** The store {@code $NOTIFY_SOCKET} names, or {@link #NONE} when the process has none. */
  public static SdNotify fromEnvironment() {
    return to(Objects.toString(System.getenv("NOTIFY_SOCKET"), ""));
  }

  /** A store at {@code socketPath}; blank means {@link #NONE}. {@code @} prefixes are abstract. */
  public static SdNotify to(String socketPath) {
    return socketPath.isBlank() ? NONE : new SdNotify(socketPath);
  }

  public boolean enabled() {
    return !socketPath.isBlank();
  }

  /** Pushes {@code fd} into the store under {@code name}; the manager holds its own duplicate. */
  public void storeFd(String name, int fd) {
    send("FDSTORE=1\nFDNAME=" + name + "\n", fd);
  }

  /** Drops every descriptor stored under {@code name}. */
  public void removeFd(String name) {
    send("FDSTOREREMOVE=1\nFDNAME=" + name + "\n", -1);
  }

  private void send(String text, int fd) {
    if (!enabled()) {
      return;
    }
    try {
      sendDatagram(socketPath, text, fd);
    } catch (IOException e) {
      if (!warned) {
        warned = true;
        System.err.println(
            "pty-host: notify socket "
                + socketPath
                + " is not answering ("
                + e.getMessage()
                + "); sessions will not survive a host restart.");
      }
    }
  }

  /**
   * The descriptors the manager handed this process at start, by store name: {@code LISTEN_FDS}
   * descriptors beginning at 3, named by {@code LISTEN_FDNAMES}, and only when {@code LISTEN_PID}
   * is this process — an inheritance addressed to a parent is not ours to adopt. A descriptor
   * without a name is listed under its number so the caller can still close it.
   */
  public static Map<String, Integer> listenFds(Map<String, String> env, long pid) {
    var inherited = new LinkedHashMap<String, Integer>();
    if (!String.valueOf(pid).equals(env.get("LISTEN_PID"))) {
      return inherited;
    }
    int count;
    try {
      count = Integer.parseInt(Objects.toString(env.get("LISTEN_FDS"), "0"));
    } catch (NumberFormatException bad) {
      return inherited;
    }
    var names = Objects.toString(env.get("LISTEN_FDNAMES"), "").split(":", -1);
    for (var i = 0; i < count; i++) {
      var fd = LISTEN_FDS_START + i;
      var name = i < names.length && !names[i].isBlank() ? names[i] : "fd" + fd;
      inherited.put(name, fd);
    }
    return inherited;
  }

  private static void sendDatagram(String path, String text, int fd) throws IOException {
    var payload = text.getBytes(StandardCharsets.UTF_8);
    try (var arena = Arena.ofConfined()) {
      var errno = arena.allocate(Native.ERRNO_STATE);
      var sock = (int) LIBC.socket().invokeExact(errno, AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
      if (sock < 0) {
        throw new IOException("socket failed with errno " + Native.errno(errno) + ".");
      }
      try {
        var address = arena.allocate(SOCKADDR_UN_BYTES);
        var addressLength = fillAddress(address, path);
        var data = arena.allocate(payload.length);
        data.copyFrom(MemorySegment.ofArray(payload));
        var iov = arena.allocate(IOVEC_BYTES);
        iov.set(ValueLayout.ADDRESS, 0, data);
        iov.set(ValueLayout.JAVA_LONG, 8, payload.length);
        var msg = arena.allocate(MSGHDR_BYTES);
        msg.set(ValueLayout.ADDRESS, 0, address);
        msg.set(ValueLayout.JAVA_INT, 8, addressLength);
        msg.set(ValueLayout.ADDRESS, 16, iov);
        msg.set(ValueLayout.JAVA_LONG, 24, 1L);
        if (fd >= 0) {
          var control = arena.allocate(cmsgSpace(1));
          control.set(ValueLayout.JAVA_LONG, 0, CMSGHDR_BYTES + 4L);
          control.set(ValueLayout.JAVA_INT, 8, SOL_SOCKET);
          control.set(ValueLayout.JAVA_INT, 12, SCM_RIGHTS);
          control.set(ValueLayout.JAVA_INT, CMSGHDR_BYTES, fd);
          msg.set(ValueLayout.ADDRESS, 32, control);
          msg.set(ValueLayout.JAVA_LONG, 40, control.byteSize());
        }
        var sent = (long) LIBC.sendmsg().invokeExact(errno, sock, msg, MSG_NOSIGNAL);
        if (sent < 0) {
          throw new IOException("sendmsg failed with errno " + Native.errno(errno) + ".");
        }
      } finally {
        var unused = (int) LIBC.close().invokeExact(sock);
      }
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("sd_notify failed", t);
    }
  }

  private static long cmsgSpace(int fds) {
    return CMSGHDR_BYTES + ((4L * fds + 7) & ~7L);
  }

  /** Writes a {@code sockaddr_un} for {@code path} into {@code address}; returns its length. */
  private static int fillAddress(MemorySegment address, String path) throws IOException {
    var abstractNamespace = path.startsWith("@");
    var bytes = (abstractNamespace ? path.substring(1) : path).getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0 || bytes.length >= SUN_PATH_MAX) {
      throw new IOException("Unix socket path is empty or longer than 107 bytes: " + path);
    }
    address.set(ValueLayout.JAVA_SHORT, 0, (short) AF_UNIX);
    var offset = abstractNamespace ? 3 : 2;
    MemorySegment.copy(MemorySegment.ofArray(bytes), 0, address, offset, bytes.length);
    return offset + bytes.length + (abstractNamespace ? 0 : 1);
  }

  /** One datagram as the manager sees it: the text and the descriptors it carried. */
  public record Message(String text, List<Integer> fds) {
    /** The value of {@code KEY=value} in the text, or empty. */
    public String field(String key) {
      for (var line : text.split("\n")) {
        if (line.startsWith(key + "=")) {
          return line.substring(key.length() + 1);
        }
      }
      return "";
    }
  }

  /**
   * A manager stand-in: binds a datagram socket at a path and receives what a service sends,
   * descriptors included — the store's protocol proven from both ends without a real systemd.
   */
  public static final class Receiver implements AutoCloseable {

    private final int sock;
    private final Path path;

    private Receiver(int sock, Path path) {
      this.sock = sock;
      this.path = path;
    }

    public static Receiver bind(Path path) throws IOException {
      Files.deleteIfExists(path);
      try (var arena = Arena.ofConfined()) {
        var errno = arena.allocate(Native.ERRNO_STATE);
        var sock = (int) LIBC.socket().invokeExact(errno, AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (sock < 0) {
          throw new IOException("socket failed with errno " + Native.errno(errno) + ".");
        }
        var address = arena.allocate(SOCKADDR_UN_BYTES);
        var length = fillAddress(address, path.toString());
        if ((int) LIBC.bind().invokeExact(errno, sock, address, length) != 0) {
          var unused = (int) LIBC.close().invokeExact(sock);
          throw new IOException("bind failed with errno " + Native.errno(errno) + ".");
        }
        return new Receiver(sock, path);
      } catch (IOException e) {
        throw e;
      } catch (Throwable t) {
        throw new IOException("receiver bind failed", t);
      }
    }

    public Path path() {
      return path;
    }

    /** The receiving socket's descriptor — a descriptor that is anything but a pty master. */
    public int fd() {
      return sock;
    }

    /** Blocks for the next datagram. The descriptors it returns are this process's to close. */
    public Message receive() throws IOException {
      try (var arena = Arena.ofConfined()) {
        var errno = arena.allocate(Native.ERRNO_STATE);
        var data = arena.allocate(MAX_TEXT_BYTES);
        var iov = arena.allocate(IOVEC_BYTES);
        iov.set(ValueLayout.ADDRESS, 0, data);
        iov.set(ValueLayout.JAVA_LONG, 8, MAX_TEXT_BYTES);
        var control = arena.allocate(cmsgSpace(MAX_FDS_PER_MESSAGE));
        var msg = arena.allocate(MSGHDR_BYTES);
        msg.set(ValueLayout.ADDRESS, 16, iov);
        msg.set(ValueLayout.JAVA_LONG, 24, 1L);
        msg.set(ValueLayout.ADDRESS, 32, control);
        msg.set(ValueLayout.JAVA_LONG, 40, control.byteSize());
        var received = (long) LIBC.recvmsg().invokeExact(errno, sock, msg, 0);
        if (received < 0) {
          throw new IOException("recvmsg failed with errno " + Native.errno(errno) + ".");
        }
        var text =
            new String(
                data.asSlice(0, received).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        return new Message(text, fdsIn(control, msg.get(ValueLayout.JAVA_LONG, 40)));
      } catch (IOException e) {
        throw e;
      } catch (Throwable t) {
        throw new IOException("recvmsg failed", t);
      }
    }

    private static List<Integer> fdsIn(MemorySegment control, long controlLength) {
      var fds = new ArrayList<Integer>();
      if (controlLength < CMSGHDR_BYTES) {
        return fds;
      }
      var length = control.get(ValueLayout.JAVA_LONG, 0);
      var level = control.get(ValueLayout.JAVA_INT, 8);
      var type = control.get(ValueLayout.JAVA_INT, 12);
      if (level != SOL_SOCKET || type != SCM_RIGHTS) {
        return fds;
      }
      for (var offset = CMSGHDR_BYTES; offset + 4 <= length; offset += 4) {
        fds.add(control.get(ValueLayout.JAVA_INT, offset));
      }
      return fds;
    }

    @Override
    public void close() throws IOException {
      try {
        var unused = (int) LIBC.close().invokeExact(sock);
      } catch (Throwable t) {
        throw new IOException("receiver close failed", t);
      }
      Files.deleteIfExists(path);
    }
  }
}
