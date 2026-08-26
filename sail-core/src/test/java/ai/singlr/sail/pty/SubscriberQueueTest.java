/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class SubscriberQueueTest {

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
  void forcedMessagesArriveEvenWhilePaused() throws Exception {
    var queue = new SubscriberQueue(1);
    queue.enqueue(out(1));
    queue.enqueue(out(2));
    queue.enqueue(out(3));
    queue.force(new PtyMessage.SessionEnded("gone"));

    assertInstanceOf(PtyMessage.Paused.class, queue.next());
    assertInstanceOf(PtyMessage.SessionEnded.class, queue.next(), "an ending outranks the pause");
  }
}
