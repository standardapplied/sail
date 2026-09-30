/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.sync.SyncRpcServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/** A sync channel over a pipe to a main served in-process, as a test stands in for ssh. */
public final class PipedSyncChannel {

  private PipedSyncChannel() {}

  /** A channel to {@code server}, served on a virtual thread; closing it fails if main failed. */
  public static SyncOperations.Channel to(SyncRpcServer server) throws IOException {
    var toServer = new PipedOutputStream();
    var serverIn = new PipedInputStream(toServer);
    var serverOut = new PipedOutputStream();
    var fromServer = new PipedInputStream(serverOut);
    var error = new AtomicReference<Throwable>();
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (serverIn;
                      serverOut) {
                    server.serve(serverIn, serverOut);
                  } catch (Throwable e) {
                    error.set(e);
                  }
                });
    return new SyncOperations.Channel() {
      public InputStream reader() {
        return fromServer;
      }

      public OutputStream writer() {
        return toServer;
      }

      public void close() throws IOException {
        try {
          thread.join();
          if (error.get() != null) throw new IOException("main failed", error.get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        } finally {
          toServer.close();
          fromServer.close();
        }
      }
    };
  }
}
