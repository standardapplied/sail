/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The connections a door serves, each on a virtual thread of its own, all ended and waited for when
 * the door closes: a connection whose thread is blocked reading would otherwise keep its socket
 * open after its door is gone.
 */
final class OpenConnections {

  private final Set<SocketChannel> open = ConcurrentHashMap.newKeySet();
  private final Set<Thread> serving = ConcurrentHashMap.newKeySet();

  /** Serves {@code channel} with {@code work} on a virtual thread named {@code name}. */
  void serve(String name, SocketChannel channel, Consumer<SocketChannel> work) {
    open.add(channel);
    var thread =
        Thread.ofVirtual()
            .name(name)
            .unstarted(
                () -> {
                  try {
                    work.accept(channel);
                  } finally {
                    open.remove(channel);
                    serving.remove(Thread.currentThread());
                  }
                });
    serving.add(thread);
    thread.start();
  }

  /**
   * Closes every connection still open and waits for the threads serving them, once nothing serves
   * new ones: closing a channel ends a read or write blocked on it. An interrupt stops the waiting
   * and is kept.
   */
  void close() {
    for (var channel : List.copyOf(open)) {
      try {
        channel.close();
      } catch (IOException alreadyGone) {
        var unused = alreadyGone;
      }
    }
    for (var thread : List.copyOf(serving)) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
