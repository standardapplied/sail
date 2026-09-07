/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import org.junit.jupiter.api.Test;

class SubscriberQueueTest {

  private static PtyMessage.Output output(String text) {
    return new PtyMessage.Output(0, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static PtyMessage.Output out(int n) {
    return new PtyMessage.Output(n, new byte[] {(byte) n});
  }

  @Test
  void deliversInOrderUntilOverflowThenPausesDropsAndResumesOnCatchUp() throws Exception {
    var queue = new SubscriberQueue(2);
    queue.enqueue(out(1));
    queue.enqueue(out(2));
    queue.enqueue(out(3));
    queue.enqueue(out(4));

    assertInstanceOf(PtyMessage.Paused.class, queue.next(), "overflow replaces the backlog");
    assertInstanceOf(
        PtyMessage.Continued.class, queue.next(), "catching up resumes, deterministically");

    queue.enqueue(out(5));
    assertEquals(5, ((PtyMessage.Output) queue.next()).lastInputSeq(), "live traffic flows again");
  }

  @Test
  void everythingDuringThePauseIsDroppedNothingAfterItIs() throws Exception {
    var queue = new SubscriberQueue(1);
    queue.enqueue(out(1));
    queue.enqueue(out(2));
    queue.enqueue(out(3));
    queue.enqueue(out(4));

    assertInstanceOf(PtyMessage.Paused.class, queue.next());
    assertInstanceOf(PtyMessage.Continued.class, queue.next());
    queue.enqueue(out(9));
    assertEquals(9, ((PtyMessage.Output) queue.next()).lastInputSeq());
  }

  @Test
  void replaceWithSwapsTheBacklogButTerminalMessagesSurvive() throws Exception {
    var queue = new SubscriberQueue(8);
    queue.enqueue(out(1));
    queue.enqueue(out(2));
    queue.force(new PtyMessage.SessionEnded("gone"));
    queue.replaceWith(
        java.util.List.of(new PtyMessage.ReplayBegin(true), out(9), new PtyMessage.ReplayEnd()));

    assertInstanceOf(PtyMessage.ReplayBegin.class, queue.next(), "the resync leads");
    assertEquals(9, ((PtyMessage.Output) queue.next()).lastInputSeq());
    assertInstanceOf(PtyMessage.ReplayEnd.class, queue.next());
    assertInstanceOf(PtyMessage.SessionEnded.class, queue.next(), "the ending survives the swap");
  }

  @Test
  void replaceWithKeepsTheDetachPoisonAlive() throws Exception {
    var queue = new SubscriberQueue(8);
    queue.enqueue(out(1));
    queue.force(new PtyMessage.Ok());
    queue.replaceWith(java.util.List.of(new PtyMessage.ReplayBegin(true)));

    assertInstanceOf(PtyMessage.ReplayBegin.class, queue.next());
    assertInstanceOf(PtyMessage.Ok.class, queue.next(), "detach still terminates the send loop");
  }

  @Test
  void aReplayLargerThanTheCapDoesNotTripAnotherPause() throws Exception {
    var queue = new SubscriberQueue(2);
    queue.replaceWith(
        List.of(
            new PtyMessage.ReplayBegin(true),
            output("a"),
            output("b"),
            output("c"),
            new PtyMessage.ReplayEnd()));
    queue.enqueue(output("live-1"));
    queue.enqueue(output("live-2"));

    var kinds = new java.util.ArrayList<String>();
    for (int i = 0; i < 7; i++) {
      kinds.add(queue.next().getClass().getSimpleName());
    }
    assertEquals(
        List.of("ReplayBegin", "Output", "Output", "Output", "ReplayEnd", "Output", "Output"),
        kinds,
        "a five-message replay in a two-message queue is delivered whole, then live output");
  }

  @Test
  void forcedMessagesArriveEvenWhilePaused() throws Exception {
    var queue = new SubscriberQueue(1);
    queue.enqueue(out(1));
    queue.enqueue(out(2));
    queue.enqueue(out(3));
    queue.force(new PtyMessage.SessionEnded("gone"));

    assertInstanceOf(PtyMessage.Paused.class, queue.next());
    assertInstanceOf(PtyMessage.SessionEnded.class, queue.next(), "an ending outranks the pause");
  }

  @Test
  void theByteCapTripsThePauseLongBeforeTheCountCapWouldWithLargeFrames() throws Exception {
    var queue = new SubscriberQueue(4096, 256 * 1024);
    var big = new PtyMessage.Output(0, new byte[64 * 1024]);
    for (var i = 0; i < 5; i++) {
      queue.enqueue(big);
    }

    assertInstanceOf(
        PtyMessage.Paused.class,
        queue.next(),
        "five 64 KiB frames overrun the 256 KiB byte cap, pausing at 5 of 4096 slots — the byte cap"
            + " bounds heap where a frame count never could");
  }

  @Test
  void repeatedStateNotificationsCoalesceSoAStalledReaderCannotBeFloodedWithThem()
      throws Exception {
    var queue = new SubscriberQueue(4);
    queue.enqueue(out(1));
    queue.force(new PtyMessage.Resized(80, 24));
    for (var i = 0; i < 100_000; i++) {
      queue.force(new PtyMessage.WriterChanged("fde-" + i));
    }
    queue.force(new PtyMessage.Resized(100, 40));
    queue.force(new PtyMessage.SessionEnded("gone"));

    assertEquals(1, ((PtyMessage.Output) queue.next()).lastInputSeq(), "output is untouched");
    assertEquals(
        new PtyMessage.WriterChanged("fde-99999"),
        queue.next(),
        "only the latest writer is pending — a TakeWrite storm cannot grow the queue");
    assertEquals(
        new PtyMessage.Resized(100, 40), queue.next(), "only the latest geometry is pending");
    assertInstanceOf(PtyMessage.SessionEnded.class, queue.next(), "the ending still arrives");
  }
}
