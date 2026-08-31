/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PtyWireTest {

  private static PtyMessage roundTrip(PtyMessage message) throws IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    PtyWire.write(java.nio.channels.Channels.newChannel(bytes), message);
    return PtyWire.read(
        java.nio.channels.Channels.newChannel(
            new java.io.ByteArrayInputStream(bytes.toByteArray())));
  }

  @Test
  void everyMessageShapeSurvivesTheWire() throws Exception {
    var create =
        (PtyMessage.Create)
            roundTrip(
                new PtyMessage.Create(
                    "lounge", List.of("bash", "-l"), "/home/dev", "chorus", "design", 132, 43));
    assertEquals("lounge", create.session());
    assertEquals(List.of("bash", "-l"), create.command());
    assertEquals("chorus", create.project());
    assertEquals("design", create.room());
    assertEquals(132, create.cols());
    assertEquals(43, create.rows());

    var unbound =
        (PtyMessage.Create)
            roundTrip(new PtyMessage.Create("s", List.of(), "/tmp", "", null, 80, 24));
    assertEquals("", unbound.room(), "an absent room reads back blank, never null");

    var input =
        (PtyMessage.Input)
            roundTrip(new PtyMessage.Input(42, "ls\u00e9\n".getBytes(StandardCharsets.UTF_8)));
    assertEquals(42, input.seq());
    assertArrayEquals("ls\u00e9\n".getBytes(StandardCharsets.UTF_8), input.bytes());

    var output =
        (PtyMessage.Output) roundTrip(new PtyMessage.Output(42, new byte[] {0, 1, 27, (byte) 255}));
    assertEquals(42, output.lastInputSeq(), "the prediction enabler rides every output frame");
    assertArrayEquals(new byte[] {0, 1, 27, (byte) 255}, output.bytes());

    var sessions =
        (PtyMessage.Sessions)
            roundTrip(
                new PtyMessage.Sessions(
                    List.of(
                        new PtyMessage.SessionInfo(
                            "a", true, 2, "uday", "design", List.of("claude", "--resume")),
                        new PtyMessage.SessionInfo("b", false, 0, "", "", List.of("bash", "-l"))),
                    "b"));
    assertEquals(2, sessions.sessions().size());
    assertEquals("b", sessions.next(), "the page cursor rides the listing");
    assertEquals("uday", sessions.sessions().getFirst().writerFde());
    assertEquals("design", sessions.sessions().getFirst().room());
    assertEquals(List.of("claude", "--resume"), sessions.sessions().getFirst().command());
    assertEquals("", sessions.sessions().getLast().room());
    assertEquals(List.of("bash", "-l"), sessions.sessions().getLast().command());

    var listing = (PtyMessage.ListSessions) roundTrip(new PtyMessage.ListSessions("after-me", 7));
    assertEquals("after-me", listing.after());
    assertEquals(7, listing.limit());
    assertEquals(
        "", ((PtyMessage.Sessions) roundTrip(new PtyMessage.Sessions(List.of(), ""))).next());
    assertEquals("tok", ((PtyMessage.Hello) roundTrip(new PtyMessage.Hello("tok"))).token());
    assertInstanceOf(PtyMessage.TakeWrite.class, roundTrip(new PtyMessage.TakeWrite()));
    assertInstanceOf(PtyMessage.ReplayEnd.class, roundTrip(new PtyMessage.ReplayEnd()));
    assertEquals(
        "gone",
        ((PtyMessage.SessionEnded) roundTrip(new PtyMessage.SessionEnded("gone"))).reason());
    assertEquals("boom", ((PtyMessage.Err) roundTrip(new PtyMessage.Err("boom"))).message());
  }

  @Test
  void wireSizeCountsThePrefixesSoAFullPageOfCappedCommandsFitsOneFrame() throws Exception {
    var confetti = new java.util.ArrayList<>(List.of("/bin/true"));
    for (var i = 0; i < 20_000; i++) {
      confetti.add("x");
    }
    assertTrue(
        PtyWire.wireSize(confetti) > PtyMessage.MAX_COMMAND_BYTES,
        "20,000 one-byte arguments cost 100 KiB on the wire, not 20 KiB");

    var densest = new java.util.ArrayList<String>();
    while (PtyWire.wireSize(densest) + 5 <= PtyMessage.MAX_COMMAND_BYTES) {
      densest.add("x");
    }
    var name = "n".repeat(255);
    var page = new java.util.ArrayList<PtyMessage.SessionInfo>();
    for (var i = 0; i < PtyMessage.PAGE_LIMIT; i++) {
      page.add(new PtyMessage.SessionInfo(name + i, true, 3, name, name, densest));
    }

    var listed = (PtyMessage.Sessions) roundTrip(new PtyMessage.Sessions(page, name));
    assertEquals(PtyMessage.PAGE_LIMIT, listed.sessions().size());
    assertEquals(densest, listed.sessions().getLast().command());
  }

  @Test
  void handshakeAcceptsItselfAndRefusesStrangers() throws Exception {
    var aToB = Pipe.open();
    var bToA = Pipe.open();
    var serverDone = new java.util.concurrent.CompletableFuture<Void>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                PtyWire.handshake(aToB.source(), bToA.sink());
                serverDone.complete(null);
              } catch (IOException e) {
                serverDone.completeExceptionally(e);
              }
            });
    PtyWire.handshake(bToA.source(), aToB.sink());
    serverDone.join();

    var evil = Pipe.open();
    var reply = Pipe.open();
    evil.sink().write(ByteBuffer.wrap("NOTSAIL1".getBytes(StandardCharsets.US_ASCII)));
    assertThrows(IOException.class, () -> PtyWire.handshake(evil.source(), reply.sink()));
  }

  @Test
  void oversizedAndUnknownFramesAreRefusedLoudly() throws Exception {
    var pipe = Pipe.open();
    pipe.sink().write(ByteBuffer.allocate(4).putInt(PtyWire.MAX_FRAME + 1).flip());
    assertThrows(IOException.class, () -> PtyWire.read(pipe.source()));

    var unknown = Pipe.open();
    unknown.sink().write(ByteBuffer.allocate(5).putInt(1).put((byte) 99).flip());
    var error = assertThrows(IOException.class, () -> PtyWire.read(unknown.source()));
    org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("99"));
  }

  @Test
  void aClosedChannelMidFrameIsEofNotGarbage() throws Exception {
    var pipe = Pipe.open();
    pipe.sink().write(ByteBuffer.allocate(4).putInt(100).flip());
    pipe.sink().close();
    assertThrows(java.io.EOFException.class, () -> PtyWire.read(pipe.source()));
  }

  @Test
  void aLyingInnerLengthIsRefusedNotAllocated() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            readFramed(ByteBuffer.allocate(13).put((byte) 3).putLong(7).putInt(Integer.MAX_VALUE)),
        "a tiny Input frame claiming 2 GiB of bytes must fail loud, never allocate");

    assertThrows(
        IOException.class,
        () -> readFramed(ByteBuffer.allocate(5).put((byte) 29).putInt(Integer.MAX_VALUE)),
        "a tiny Sessions frame claiming 2 billion entries must fail loud, never pre-size a list");

    assertThrows(
        IOException.class,
        () -> readFramed(ByteBuffer.allocate(13).put((byte) 3).putLong(7).putInt(-1)),
        "a negative inner length must fail loud, never throw NegativeArraySizeException");
  }

  private static void readFramed(ByteBuffer payload) throws IOException {
    payload.flip();
    var frame = ByteBuffer.allocate(4 + payload.remaining());
    frame.putInt(payload.remaining()).put(payload).flip();
    var pipe = Pipe.open();
    pipe.sink().write(frame);
    pipe.sink().close();
    PtyWire.read(pipe.source());
  }
}
