/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Incus as every sail process on the box sees it: each container's {@code sail-api-sock} device and
 * machinery stamp are shared state, read and written one call at a time, and refused as Incus
 * refuses them: a remove of a device that is gone, an add of one that is there, and a {@code config
 * set} that lands between another writer's remove and add of the device, refused for the ETag it
 * read. Each container starts with its device pointing at {@link #MOVED}, where the socket used to
 * live. The first remove runs {@code afterFirstRemove} before it answers, so a test can hold one
 * writer inside the device check while another starts. Every other command is {@code rest}'s.
 */
final class SharedIncus implements ShellExec {

  static final String MOVED = "/run/sail";

  private final Map<String, String> sources = new ConcurrentHashMap<>();
  private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
  private final Set<String> stamped = ConcurrentHashMap.newKeySet();
  private final List<String> writes = new CopyOnWriteArrayList<>();
  private final AtomicInteger stampWrites = new AtomicInteger();
  private final AtomicInteger removes = new AtomicInteger();
  private final ShellExec rest;
  private final Runnable afterFirstRemove;

  SharedIncus(ShellExec rest, Runnable afterFirstRemove, String... containers) {
    this.rest = rest;
    this.afterFirstRemove = afterFirstRemove;
    for (var container : containers) {
      sources.put(container, MOVED);
    }
  }

  /** Every device and config write Incus accepted, in order, as {@code <verb> <container>}. */
  List<String> writes() {
    return List.copyOf(writes);
  }

  int stampWrites() {
    return stampWrites.get();
  }

  @Override
  public Result exec(List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    return exec(command, null, Duration.ZERO);
  }

  @Override
  public Result exec(List<String> command, Path workDir, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    var joined = String.join(" ", command);
    if (joined.startsWith("incus config device get") && joined.endsWith("sail-api-sock source")) {
      var source = sources.get(command.get(4));
      return source == null
          ? new Result(1, "", "Error: Device not found")
          : new Result(0, source + "\n", "");
    }
    if (joined.startsWith("incus config device remove")) {
      return remove(command.get(4));
    }
    if (joined.startsWith("incus config device add")) {
      return add(command.get(4), command.get(7).substring("source=".length()));
    }
    if (joined.startsWith("incus config set")) {
      if (refreshing.contains(command.get(3))) {
        return new Result(1, "", "Error: ETag doesn't match: 9bb6ef vs f727ff");
      }
      writes.add("set " + command.get(3));
    }
    if (joined.contains("cmp -s --")) {
      return new Result(stamped.contains(command.get(2)) ? 0 : 1, "", "");
    }
    if (joined.contains(ContainerSailSetup.STAMP_PATH)) {
      stamped.add(command.get(2));
      stampWrites.incrementAndGet();
    }
    return rest.exec(command, workDir, timeout);
  }

  private Result remove(String container) {
    if (sources.remove(container) == null) {
      return new Result(1, "", "Error: Device “sail-api-sock” doesn't exist");
    }
    refreshing.add(container);
    writes.add("remove " + container);
    if (removes.getAndIncrement() == 0) {
      afterFirstRemove.run();
    }
    return new Result(0, "", "");
  }

  private Result add(String container, String source) {
    if (sources.putIfAbsent(container, source) != null) {
      return new Result(1, "", "Error: The device already exists");
    }
    refreshing.remove(container);
    writes.add("add " + container);
    return new Result(0, "", "");
  }

  /** Whether {@code thread} waits on a lock, or has finished. */
  static Predicate<Thread> parkedOrDone() {
    return thread ->
        thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TERMINATED;
  }

  /** Spins until {@code condition} holds, failing once ten seconds have passed. */
  static void awaitUntil(BooleanSupplier condition) {
    var horizon = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > horizon) {
        throw new AssertionError("the other writer neither ran nor waited");
      }
      Thread.yield();
    }
  }

  @Override
  public boolean isDryRun() {
    return false;
  }
}
