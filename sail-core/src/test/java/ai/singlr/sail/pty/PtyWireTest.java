/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PtyWireTest {

  private static PtyMessage roundTrip(PtyMessage message) throws IOException {
    var pipe = Pipe.open();
    PtyWire.write(pipe.sink(), message);
    pipe.sink().close();
    return PtyWire.read(pipe.source());
  }

  @Test
  void everyMessageShapeSurvivesTheWire() throws Exception {
    var create =
        (PtyMessage.Create)
            roundTrip(new PtyMessage.Create("lounge", List.of("bash", "-l"), "/home/dev", 132, 43));
    assertEquals("lounge", create.session());
    assertEquals(List.of("bash", "-l"), create.command());
    assertEquals(132, create.cols());

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
                        new PtyMessage.SessionInfo("a", true, 2, "uday"),
                        new PtyMessage.SessionInfo("b", false, 0, ""))));
    assertEquals(2, sessions.sessions().size());
    assertEquals("uday", sessions.sessions().getFirst().writerFde());

    assertInstanceOf(PtyMessage.TakeWrite.class, roundTrip(new PtyMessage.TakeWrite()));
    assertInstanceOf(PtyMessage.ReplayEnd.class, roundTrip(new PtyMessage.ReplayEnd()));
    assertEquals(
        "gone",
        ((PtyMessage.SessionEnded) roundTrip(new PtyMessage.SessionEnded("gone"))).reason());
    assertEquals("boom", ((PtyMessage.Err) roundTrip(new PtyMessage.Err("boom"))).message());
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
}
