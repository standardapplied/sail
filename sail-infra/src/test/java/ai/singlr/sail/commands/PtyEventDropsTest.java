/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PtyEventDropsTest {

  @TempDir Path tempDir;

  @Test
  void concurrentFailuresAllCountAndReadersNeverSeeAPartialMeter() throws Exception {
    var file = PtyEventDrops.fileOf(tempDir.resolve("pty.sock"));
    var writers = 8;
    var perWriter = 50;
    var start = new CyclicBarrier(writers + 2);
    var done = new CountDownLatch(writers);
    var writersRunning = new AtomicBoolean(true);
    for (int w = 0; w < writers; w++) {
      Thread.ofPlatform()
          .start(
              () -> {
                await(start);
                for (int i = 0; i < perWriter; i++) {
                  PtyEventDrops.record(file, "pty_session_started", "disk full");
                }
                done.countDown();
              });
    }
    var observed = new ArrayList<Long>();
    var reader =
        Thread.ofPlatform()
            .start(
                () -> {
                  await(start);
                  while (writersRunning.get()) {
                    observed.add(PtyEventDrops.read(file).count());
                  }
                });
    start.await();
    done.await();
    writersRunning.set(false);
    reader.join();

    assertEquals(
        (long) writers * perWriter,
        PtyEventDrops.read(file).count(),
        "a drop meter that loses drops under concurrency measures nothing");
    var floor = 0L;
    for (var count : observed) {
      assertTrue(
          count >= floor,
          "a reader saw the meter go backwards ("
              + count
              + " after "
              + floor
              + ") — a partially"
              + " written file parsed as fewer drops than already recorded");
      floor = Math.max(floor, count);
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (Exception interrupted) {
      throw new IllegalStateException(interrupted);
    }
  }
}
