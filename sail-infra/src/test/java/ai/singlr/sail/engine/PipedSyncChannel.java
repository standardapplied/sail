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

  /** One session of a main, served over {@code in} and {@code out}. */
  @FunctionalInterface
  public interface Main {
    void serve(InputStream in, OutputStream out) throws Exception;
  }

  /** A channel to {@code server}, served on a virtual thread; closing it fails if main failed. */
  public static SyncOperations.Channel to(SyncRpcServer server) throws IOException {
    return to((Main) server::serve);
  }

  /** A channel to {@code main}, served on a virtual thread; closing it fails if main failed. */
  public static SyncOperations.Channel to(Main main) throws IOException {
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
                    main.serve(serverIn, serverOut);
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

      /**
       * Closes this node's end first, as ssh's would, so a main left mid-hello sees EOF and exits.
       */
      public void close() throws IOException {
        toServer.close();
        try {
          thread.join();
          if (error.get() != null) throw new IOException("main failed", error.get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        } finally {
          fromServer.close();
        }
      }
    };
  }
}
