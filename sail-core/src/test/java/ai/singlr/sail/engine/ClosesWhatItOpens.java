/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a test class that leaves open a descriptor it opened: an HTTP client's selector, a server's
 * socket, a store's database files. What a class leaves open is closed when it is collected, at a
 * moment no one chooses and inside whichever class runs then, or not before the fork ends.
 * Registered for every class of every module through JUnit's extension auto-detection, as {@link
 * OwnSailGuard} is (this module's test resources name it, and its test-jar carries them to the
 * others), it checks once the class's own {@code @AfterAll} has run. Linux only, where {@code
 * /proc/self/fd} names what each descriptor is open on; elsewhere it does nothing.
 */
public final class ClosesWhatItOpens implements BeforeAllCallback, AfterAllCallback {

  private static final Path PROC_FD = Path.of("/proc/self/fd");

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ClosesWhatItOpens.class);

  /**
   * The class path's jars, which the JVM's class loader opens as the first class needing each loads
   * and keeps open for the fork's life.
   */
  private static final Predicate<String> CLASS_PATH_JAR = target -> target.endsWith(".jar");

  /**
   * The kernel's random sources, which the JDK's {@code SecureRandom} opens on its first use (a
   * temporary file's name, a token) and keeps open for the fork's life.
   */
  private static final Predicate<String> RANDOM_SOURCE =
      target -> target.equals("/dev/random") || target.equals("/dev/urandom");

  /**
   * The cgroup's files, which the JVM reads from its own threads while it runs in a container: a
   * compiler thread asks the cgroup's {@code memory.current} how much memory is free each time it
   * adds a compiler thread ({@code -Xlog:os+container=trace} and {@code -Xlog:jit+thread=debug}
   * show both), opening and closing it inside whichever class is running then.
   */
  private static final Predicate<String> CGROUP_READ =
      target -> target.startsWith("/sys/fs/cgroup/");

  /** What the fork itself opens, for its life or for a read of its own, whichever class runs. */
  private static final List<Predicate<String>> THE_FORKS =
      List.of(CLASS_PATH_JAR, RANDOM_SOURCE, CGROUP_READ);

  /**
   * The JDK's classes whose first use opens descriptors for the fork's life that no target tells
   * apart from a class's own, used before every class's baseline: NIO's dispatcher keeps a socket
   * to put in place of one closed while a thread blocks on it, and the pollers of virtual threads'
   * blocking I/O hold an epoll each.
   */
  private static final List<String> THE_JDKS =
      List.of("sun.nio.ch.UnixDispatcher", "sun.nio.ch.Poller");

  private record Before(Map<String, Object> open) {}

  @Override
  public void beforeAll(ExtensionContext context) throws IOException, ClassNotFoundException {
    if (Files.isDirectory(PROC_FD)) {
      context.getStore(NAMESPACE).put(Before.class, new Before(baseline()));
    }
  }

  /**
   * What is open before a stretch of work that {@link #assertClosed} then checks, once the JDK has
   * opened {@link #THE_JDKS what it keeps for the fork's life} where that is checked.
   */
  public static Map<String, Object> baseline() throws IOException, ClassNotFoundException {
    if (Files.isDirectory(PROC_FD)) {
      for (var name : THE_JDKS) {
        Class.forName(name);
      }
    }
    return OpenDescriptors.list();
  }

  @Override
  public void afterAll(ExtensionContext context) throws IOException {
    var before = Optional.ofNullable(context.getStore(NAMESPACE).get(Before.class, Before.class));
    if (before.isPresent()) {
      assertClosed(context.getRequiredTestClass().getName(), before.get().open());
    }
  }

  /**
   * Fails, naming each with its number and what it is open on, if a descriptor not open on the same
   * thing {@code before} is open now and is not {@link #THE_FORKS the fork's own}; does nothing
   * where no {@code /proc/self/fd} names targets.
   */
  public static void assertClosed(String owner, Map<String, Object> before) throws IOException {
    if (!Files.isDirectory(PROC_FD)) {
      return;
    }
    var left = new ArrayList<String>();
    for (var number : OpenDescriptors.opened(before, OpenDescriptors.list()).keySet()) {
      try {
        var target = Files.readSymbolicLink(PROC_FD.resolve(number)).toString();
        if (THE_FORKS.stream().noneMatch(kind -> kind.test(target))) {
          left.add(number + " " + target);
        }
      } catch (FileSystemException closedMeanwhile) {
        var unused = closedMeanwhile;
      }
    }
    if (!left.isEmpty()) {
      throw new AssertionError(owner + " left these open: " + String.join(", ", left));
    }
  }
}
