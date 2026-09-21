/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.io.Reader;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One node-to-main sync session over a channel: the seam the round drives, reconciling one entity
 * type at a time, pulling the FDE roster, and ending the session on {@link #close}. All exchanges
 * share the one reader/writer and run sequentially, so the typed exchanges never interleave on the
 * wire. {@link #open} sends the hello and returns the paged protocol-4 session main welcomed.
 */
public sealed interface SyncSession extends AutoCloseable permits PagedSyncSession {

  /**
   * How one entity type fared in a round: the engine's counts, how many pages and entries main
   * served for it, whether nothing at all had to move ({@code skipped}), and the failure that
   * stopped it, if one did.
   */
  record TypeReport(
      String type,
      SyncEngine.Report report,
      int pages,
      int entries,
      boolean skipped,
      String failure) {
    public static TypeReport failed(String type, String failure) {
      return new TypeReport(type, SyncEngine.Report.NONE, 0, 0, false, failure);
    }
  }

  /**
   * Reconciles every entity of {@code type}: what main changed since this node's checkpoint, then
   * what this node changed itself, pushing and adopting through the engine as it goes.
   */
  TypeReport reconcile(String type, LocalReplica local);

  /** Pulls main's FDE roster; the node mirrors it main-authoritatively. */
  List<Map<String, Object>> fetchFdes();

  /** Ends the session; main returns nothing. */
  @Override
  void close();

  /**
   * Opens a session with {@code hello}. A {@link SyncWire.Welcome} yields the paged session, with
   * one line of {@code notice} when this node runs ahead of main's version; a refusal or anything
   * else fails naming it, and an answer this protocol cannot decode — what a main on an older
   * protocol gives a hello — fails naming the remedy.
   */
  static SyncSession open(Reader in, Writer out, SyncWire.Hello hello, Consumer<String> notice) {
    var context = SyncWire.context(hello);
    var line = Rpc.exchange(in, out, SyncWire.encode(hello), context);
    SyncWire.Response response;
    try {
      response = SyncWire.decodeResponse(line);
    } catch (RuntimeException e) {
      throw new SyncTransportException(
          "protocol",
          context
              + ": main is on a sync protocol this node cannot speak: upgrade main ("
              + e.getMessage()
              + ")",
          e);
    }
    return switch (response) {
      case SyncWire.Welcome welcome -> PagedSyncSession.open(in, out, hello, welcome, notice);
      case SyncWire.Refuse refuse ->
          throw new SyncTransportException("refused", context + ": " + refuse.reason(), null);
      case SyncWire.Failed failed ->
          throw new SyncTransportException(failed.kind(), context + ": " + failed.message(), null);
      default ->
          throw new SyncTransportException(context + ": Expected a welcome, got: " + response);
    };
  }
}
