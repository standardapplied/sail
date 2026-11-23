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

import ai.singlr.sail.common.Remedy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PtyWireTest {

  private final List<Pipe> pipes = new ArrayList<>();

  @AfterEach
  void closePipes() throws IOException {
    for (var pipe : pipes) {
      pipe.source().close();
      pipe.sink().close();
    }
  }

  /** A pipe both of whose ends close once the test has run. */
  private Pipe pipe() throws IOException {
    var pipe = Pipe.open();
    pipes.add(pipe);
    return pipe;
  }

  private static PtyMessage roundTrip(PtyMessage message) throws IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    PtyWire.write(java.nio.channels.Channels.newChannel(bytes), message);
    return PtyWire.read(
        java.nio.channels.Channels.newChannel(
            new java.io.ByteArrayInputStream(bytes.toByteArray())));
  }

  @Test
  void aRefusalKeepsItsReasonApartFromItsRemedyAndItsCommandsAsData() throws Exception {
    var refusal =
        new PtyMessage.Err(
            "This box has no FDE identity.",
            Remedy.of(
                "Set one with '",
                Remedy.sail("host config set sync-handle", "<handle>"),
                "' or run '",
                Remedy.program("ssh-keygen", "-R", "main"),
                "'."));
    var bytes = new java.io.ByteArrayOutputStream();
    PtyWire.write(java.nio.channels.Channels.newChannel(bytes), refusal);

    assertEquals(
        refusal,
        PtyWire.read(
            java.nio.channels.Channels.newChannel(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))));
    var frame = ByteBuffer.wrap(bytes.toByteArray(), 5, bytes.size() - 5);
    var reason = new byte[frame.getInt()];
    frame.get(reason);
    assertEquals(
        "This box has no FDE identity.",
        new String(reason, StandardCharsets.UTF_8),
        "the reason names no command; the remedy follows as data");
    assertEquals(
        "This box has no FDE identity. Set one with 'sail host config set sync-handle <handle>'"
            + " or run 'ssh-keygen -R main'.",
        refusal.message());
  }

  @Test
  void aRefusalWithoutItsRemedyIsTruncatedNotReadAsWords() {
    assertThrows(BufferUnderflowException.class, () -> readPayload(err("No live session.")));
  }

  @Test
  void everyUploadFrameSurvivesTheWireWithItsEdgeValues() throws Exception {
    var chunk = new byte[64 << 10];
    chunk[chunk.length - 1] = (byte) 255;
    var sha = new byte[32];
    sha[0] = 1;
    assertEquals(
        new PtyMessage.PutBegin(0, "", Long.MAX_VALUE),
        roundTrip(new PtyMessage.PutBegin(0, "", Long.MAX_VALUE)));
    var sent = (PtyMessage.PutChunk) roundTrip(new PtyMessage.PutChunk(-1, Long.MAX_VALUE, chunk));
    assertEquals(-1, sent.id());
    assertEquals(Long.MAX_VALUE, sent.offset());
    assertArrayEquals(chunk, sent.bytes());
    var end = (PtyMessage.PutEnd) roundTrip(new PtyMessage.PutEnd(Integer.MAX_VALUE, sha));
    assertEquals(Integer.MAX_VALUE, end.id());
    assertArrayEquals(sha, end.sha256());
    assertEquals(new PtyMessage.PutCancel(3), roundTrip(new PtyMessage.PutCancel(3)));
    assertEquals(new PtyMessage.PutAck(3, 65536), roundTrip(new PtyMessage.PutAck(3, 65536)));
    assertEquals(
        new PtyMessage.PutStored(3, "/tmp/sail-paste/i/1-a.png"),
        roundTrip(new PtyMessage.PutStored(3, "/tmp/sail-paste/i/1-a.png")));
    for (var kind : PtyMessage.PutRefusal.values()) {
      assertEquals(
          new PtyMessage.PutRefused(3, kind, 7), roundTrip(new PtyMessage.PutRefused(3, kind, 7)));
    }
  }

  @Test
  void everyFilesFrameSurvivesTheWireWithItsEdgeValues() throws Exception {
    var path = "/home/dev/workspace/a\tb\nc é";
    assertEquals(new PtyMessage.FilesList(-1, path), roundTrip(new PtyMessage.FilesList(-1, path)));
    assertEquals(
        new PtyMessage.FilesStat(Integer.MAX_VALUE, path),
        roundTrip(new PtyMessage.FilesStat(Integer.MAX_VALUE, path)));
    assertEquals(
        new PtyMessage.FilesRead(3, path, Long.MAX_VALUE, 20L << 20),
        roundTrip(new PtyMessage.FilesRead(3, path, Long.MAX_VALUE, 20L << 20)));
    assertEquals(new PtyMessage.FilesCancel(3), roundTrip(new PtyMessage.FilesCancel(3)));
    var watch =
        new PtyMessage.FilesWatch(
            List.of(
                new PtyMessage.Watched("/home/dev/workspace", "51:9:4096:1.5:1.5"),
                new PtyMessage.Watched(path, "")));
    assertEquals(watch, roundTrip(watch));
    assertEquals(
        new PtyMessage.FilesWatch(List.of()), roundTrip(new PtyMessage.FilesWatch(List.of())));
    var entries = new java.util.ArrayList<PtyMessage.FileEntry>();
    for (var kind : PtyMessage.FileKind.values()) {
      entries.add(
          new PtyMessage.FileEntry(
              "n-" + kind, PtyMessage.FileKind.LINK, kind, Long.MAX_VALUE, -1_500_000_000L, 0777));
    }
    var listing = new PtyMessage.FilesListing(4, "51:9:4096:1.5:1.5", entries, true, false);
    assertEquals(listing, roundTrip(listing));
    var last = new PtyMessage.FilesListing(4, "v", List.of(), false, true);
    assertEquals(last, roundTrip(last));
    var entry =
        new PtyMessage.FilesEntry(
            5,
            new PtyMessage.FileEntry(
                "dev", PtyMessage.FileKind.FOLDER, PtyMessage.FileKind.NONE, 0, 0, 0755),
            "v");
    assertEquals(entry, roundTrip(entry));
    var bytes = new byte[PtyMessage.FILE_CHUNK];
    bytes[bytes.length - 1] = (byte) 255;
    var digest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    var data =
        (PtyMessage.FilesData)
            roundTrip(new PtyMessage.FilesData(6, "v", bytes, true, true, digest));
    assertTrue(data.whole(), "the last frame says the read was whole");
    assertEquals(digest, data.digest(), "the last frame carries the read's content digest");
    assertEquals(6, data.id());
    assertEquals("v", data.version());
    assertArrayEquals(bytes, data.bytes());
    assertTrue(data.eof());
    assertEquals(
        new PtyMessage.FilesChanged(List.of(path, "/home/dev")),
        roundTrip(new PtyMessage.FilesChanged(List.of(path, "/home/dev"))));
    assertInstanceOf(PtyMessage.FilesTick.class, roundTrip(new PtyMessage.FilesTick()));
    for (var kind : PtyMessage.FilesRefusal.values()) {
      assertEquals(
          new PtyMessage.FilesRefused(7, kind, "No file at x."),
          roundTrip(new PtyMessage.FilesRefused(7, kind, "No file at x.")));
    }
    assertEquals(
        new PtyMessage.YieldWorkspace("acme", "dock", "is being stopped", "was stopped"),
        roundTrip(
            new PtyMessage.YieldWorkspace("acme", "dock", "is being stopped", "was stopped")));
    assertEquals(
        new PtyMessage.IssueTicket(PtyMessage.TicketAction.FILES, "acme", "sail_x", "dock"),
        roundTrip(
            new PtyMessage.IssueTicket(PtyMessage.TicketAction.FILES, "acme", "sail_x", "dock")));
    var stopped =
        new PtyMessage.TicketRefused(PtyMessage.TicketRefusal.STOPPED, "dock is stopped.");
    assertEquals(stopped, roundTrip(stopped));
  }

  /** A repository's changes, and a diff, cross the wire with every state and their edge values. */
  @Test
  void theChangesAndADiffSurviveTheWire() throws Exception {
    var path = "/home/dev/workspace/a\tb\nc é";
    assertEquals(
        new PtyMessage.FilesChanges(Integer.MAX_VALUE),
        roundTrip(new PtyMessage.FilesChanges(Integer.MAX_VALUE)));
    var changes = new java.util.ArrayList<PtyMessage.Change>();
    for (var state : PtyMessage.ChangeState.values()) {
      changes.add(
          new PtyMessage.Change(
              path + state, state, state == PtyMessage.ChangeState.RENAMED ? path : ""));
    }
    var list =
        new PtyMessage.FilesChangeList(
            3,
            Long.MAX_VALUE,
            "/home/dev/workspace",
            "(detached)",
            "",
            changes,
            List.of("/home/dev/workspace/node_modules", path),
            true,
            true);
    assertEquals(list, roundTrip(list));
    var empty =
        new PtyMessage.FilesChangeList(
            4,
            0,
            "/home/dev/workspace/api",
            "main",
            "git could not read /home/dev/workspace/api: fatal: bad index",
            List.of(),
            List.of(),
            false,
            false);
    assertEquals(empty, roundTrip(empty));
    assertEquals(new PtyMessage.FilesDiff(5, path), roundTrip(new PtyMessage.FilesDiff(5, path)));
    var bytes = new byte[PtyMessage.FILE_CHUNK];
    bytes[0] = (byte) 0xff;
    var diff = (PtyMessage.FilesDiffData) roundTrip(new PtyMessage.FilesDiffData(6, bytes, true));
    assertEquals(6, diff.id());
    assertArrayEquals(bytes, diff.bytes());
    assertTrue(diff.eof());
    assertThrows(IOException.class, () -> PtyMessage.ChangeState.of("staged"));
    assertEquals(PtyMessage.ChangeState.UNTRACKED, PtyMessage.ChangeState.of("untracked"));
  }

  /**
   * Every frame that carries a request's id is classed: a request the client sends, or an answer
   * that says by its own type and flags whether it is the last, so a new frame with an id cannot be
   * added unclassed. An answer made of empty values is the last unless it is an acknowledgement or
   * a read's frame without its digest; a listing with more to come is not.
   */
  @Test
  void everyFrameWithAnIdIsARequestOrAnAnswerThatSaysWhetherItIsTheLast() throws Exception {
    var requests =
        java.util.Set.<Class<?>>of(
            PtyMessage.FilesList.class,
            PtyMessage.FilesStat.class,
            PtyMessage.FilesRead.class,
            PtyMessage.FilesCancel.class,
            PtyMessage.FilesWrite.class,
            PtyMessage.FilesCreate.class,
            PtyMessage.FilesMove.class,
            PtyMessage.FilesRemove.class,
            PtyMessage.PutBegin.class,
            PtyMessage.PutChunk.class,
            PtyMessage.PutEnd.class,
            PtyMessage.PutCancel.class,
            PtyMessage.FilesChanges.class,
            PtyMessage.FilesDiff.class);
    var notLastWhenEmpty =
        java.util.Set.<Class<?>>of(
            PtyMessage.PutAck.class, PtyMessage.FilesData.class, PtyMessage.FilesDiffData.class);
    var answers = 0;
    for (var type : allFrames(PtyMessage.class)) {
      var components = type.getRecordComponents();
      var hasId = false;
      for (var component :
          components == null ? new java.lang.reflect.RecordComponent[0] : components) {
        var name = component.getName().toLowerCase(java.util.Locale.ROOT);
        var integral =
            List.<Class<?>>of(
                    int.class, long.class, short.class, byte.class, Integer.class, Long.class)
                .contains(component.getType());
        if (integral && (name.equals("id") || name.endsWith("id"))) {
          assertEquals(
              "id int",
              component.getName() + " " + component.getType().getName(),
              type.getSimpleName() + ": a request's id is always int id");
          hasId = true;
        }
      }
      if (!hasId) {
        continue;
      }
      if (!PtyMessage.Answer.class.isAssignableFrom(type)) {
        assertTrue(
            requests.contains(type), type.getSimpleName() + " is neither request nor answer");
        continue;
      }
      answers++;
      var empty = (PtyMessage.Answer) emptyOf(type);
      assertEquals(!notLastWhenEmpty.contains(type), empty.last(), type.getSimpleName());
    }
    assertEquals(12, answers, "every answer frame was walked");
    assertTrue(
        !new PtyMessage.FilesChangeList(1, 0, "/r", "b", "", List.of(), List.of(), false, true)
            .last());
    assertTrue(new PtyMessage.FilesDiffData(1, new byte[0], true).last());
    assertTrue(
        !new PtyMessage.FilesListing(1, "v", List.of(), true, false).last()
            && new PtyMessage.FilesListing(1, "v", List.of(), false, false).last());
    assertTrue(new PtyMessage.FilesData(1, "v", new byte[0], false, false, "d").last());
  }

  private static List<Class<?>> allFrames(Class<?> sealed) {
    var all = new java.util.ArrayList<Class<?>>();
    for (var type : sealed.getPermittedSubclasses()) {
      if (type.isSealed()) {
        all.addAll(allFrames(type));
      } else {
        all.add(type);
      }
    }
    return all;
  }

  /** A record of {@code type} made of empty values: zeros, falses, blanks, empty lists, nulls. */
  private static Object emptyOf(Class<?> type) throws Exception {
    var components = type.getRecordComponents();
    var types = new Class<?>[components.length];
    var values = new Object[components.length];
    for (var i = 0; i < components.length; i++) {
      var kind = components[i].getType();
      types[i] = kind;
      values[i] =
          kind == int.class
              ? 0
              : kind == long.class
                  ? 0L
                  : kind == boolean.class
                      ? false
                      : kind == String.class
                          ? ""
                          : kind == List.class
                              ? List.of()
                              : kind == byte[].class
                                  ? new byte[0]
                                  : kind.isEnum() ? kind.getEnumConstants()[0] : null;
    }
    return type.getDeclaredConstructor(types).newInstance(values);
  }

  /** m3: a name that is not UTF-8 crosses the wire as its bytes, in every files frame. */
  @Test
  void aNameThatIsNotUtf8CrossesTheWireAsItsBytes() throws Exception {
    var bad = FileNames.decode(new byte[] {'/', 'h', (byte) 0xff, 'n'});
    var entry =
        new PtyMessage.FileEntry(bad, PtyMessage.FileKind.FILE, PtyMessage.FileKind.NONE, 0, 0, 0);
    for (var message :
        List.<PtyMessage>of(
            new PtyMessage.FilesList(1, bad),
            new PtyMessage.FilesStat(1, bad),
            new PtyMessage.FilesRead(1, bad, 0, 1),
            new PtyMessage.FilesWatch(List.of(new PtyMessage.Watched(bad, "v"))),
            new PtyMessage.FilesListing(1, "v", List.of(entry), false, false),
            new PtyMessage.FilesEntry(1, entry, "v"),
            new PtyMessage.FilesChanged(List.of(bad)),
            new PtyMessage.FilesChangeList(1, 0, bad, bad, "", List.of(), List.of(), false, false),
            new PtyMessage.FilesChangeList(
                1,
                0,
                "/r",
                "b",
                "",
                List.of(new PtyMessage.Change(bad, PtyMessage.ChangeState.MODIFIED, "")),
                List.of(),
                false,
                false),
            new PtyMessage.FilesChangeList(
                1,
                0,
                "/r",
                "b",
                "",
                List.of(new PtyMessage.Change("/r/n", PtyMessage.ChangeState.RENAMED, bad)),
                List.of(),
                false,
                false),
            new PtyMessage.FilesChangeList(
                1, 0, "/r", "b", "", List.of(), List.of(bad), false, false),
            new PtyMessage.FilesDiff(1, bad))) {
      assertEquals(message, roundTrip(message), message.getClass().getSimpleName());
      var bytes = new java.io.ByteArrayOutputStream();
      PtyWire.write(Channels.newChannel(bytes), message);
      var wire = bytes.toByteArray();
      var found = false;
      for (var i = 0; i + 3 < wire.length; i++) {
        found |= wire[i] == '/' && wire[i + 1] == 'h' && wire[i + 2] == (byte) 0xff;
      }
      assertTrue(found, "the raw byte 0xff on the wire: " + message.getClass().getSimpleName());
    }
  }

  @Test
  void aFileKindAndAFilesRefusalAreOnesTheWireNamesOrRefused() {
    var kind = ByteBuffer.allocate(64);
    kind.putInt(1 + 4 + 4 + 1 + 4 + 4)
        .put((byte) 51)
        .putInt(1)
        .putInt(1)
        .put((byte) 'n')
        .putInt(4)
        .put("rude".getBytes(StandardCharsets.UTF_8))
        .flip();
    var refusedKind = assertThrows(IOException.class, () -> PtyWire.read(channelOf(kind)));
    assertEquals("Corrupt pty frame: a file of kind 'rude'.", refusedKind.getMessage());

    var refusal = ByteBuffer.allocate(64);
    refusal
        .putInt(1 + 4 + 4 + 4 + 4)
        .put((byte) 55)
        .putInt(3)
        .putInt(4)
        .put("rude".getBytes(StandardCharsets.UTF_8))
        .putInt(0)
        .flip();
    var refused = assertThrows(IOException.class, () -> PtyWire.read(channelOf(refusal)));
    assertEquals("Corrupt pty frame: a files refusal of kind 'rude'.", refused.getMessage());
    assertEquals("not_a_folder", PtyMessage.FilesRefusal.NOT_A_FOLDER.wire());
    assertEquals("", PtyMessage.FileKind.NONE.wire());
  }

  @Test
  void anUploadRefusalIsOneTheWireNamesOrRefused() {
    var unknown = ByteBuffer.allocate(64);
    unknown
        .putInt(1 + 4 + 4 + 4 + 8)
        .put((byte) 37)
        .putInt(3)
        .putInt(4)
        .put("rude".getBytes(StandardCharsets.UTF_8))
        .putLong(0)
        .flip();
    var refused = assertThrows(IOException.class, () -> PtyWire.read(channelOf(unknown)));
    assertTrue(refused.getMessage().contains("'rude'"), refused.getMessage());
    assertEquals("shared_container", PtyMessage.PutRefusal.SHARED_CONTAINER.wire());
  }

  @Test
  void aRefusalWhoseRemedyIsCorruptIsRefusedLoudly() {
    var unknownKind = err("m").putInt(1).put((byte) 7);
    var unknownRunner =
        err("m")
            .putInt(1)
            .put((byte) 1)
            .putInt(1)
            .put((byte) 'X')
            .putInt(1)
            .put((byte) 'v')
            .putInt(0);

    for (var payload : List.of(unknownKind, unknownRunner)) {
      var refused = assertThrows(IOException.class, () -> readPayload(payload));
      assertTrue(
          refused.getMessage().startsWith("Corrupt pty frame: a refusal"), refused.getMessage());
    }
  }

  private static ByteBuffer err(String message) {
    var text = message.getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocate(64).put((byte) 31).putInt(text.length).put(text);
  }

  private static PtyMessage readPayload(ByteBuffer payload) throws IOException {
    var body = new byte[payload.flip().remaining()];
    payload.get(body);
    var frame = ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array();
    return PtyWire.read(
        java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(frame)));
  }

  /**
   * A create names the harness the host starts, its mode and whether it signs in inside; a listed
   * session the harness it runs, alone and in a page; a shell's is none.
   */
  @Test
  void theHarnessASessionRunsSurvivesTheWire() throws Exception {
    var agent = new PtyMessage.Agent("claude-code", "bypassPermissions", true);
    var create = new PtyMessage.Create("s", List.of(), "/", "acme", "", 80, 24, "dock", agent);
    assertEquals(create, roundTrip(create));
    var shell = new PtyMessage.Create("s", List.of("bash"), "/", "acme", "", 80, 24, "dock");
    assertEquals(PtyMessage.Agent.NONE, ((PtyMessage.Create) roundTrip(shell)).agent());
    var info =
        new PtyMessage.SessionInfo("s", "i", true, 1, "uday", "", List.of("claude"))
            .placed("acme", "dock", "uday")
            .lived(7, "")
            .running(new PtyMessage.Agent("codex", "full-access", false));
    var plain = new PtyMessage.SessionInfo("t", "j", false, 0, "", "", List.of("bash", "-l"));
    assertEquals(info, roundTrip(info));
    assertEquals(
        new PtyMessage.Sessions(List.of(info, plain), ""),
        roundTrip(new PtyMessage.Sessions(List.of(info, plain), "")));
    var coded =
        new PtyMessage.Err(
            "No Codex CLI login stored for ana.",
            Remedy.of("Store one: ", Remedy.sail("agent login", "codex"), "."),
            "login_not_stored");
    assertEquals(coded, roundTrip(coded), "a refusal's code survives the wire");
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
                            "a",
                            "inst-a",
                            true,
                            2,
                            "uday",
                            "design",
                            List.of("claude", "--resume")),
                        new PtyMessage.SessionInfo(
                            "b", "inst-b", false, 0, "", "", List.of("bash", "-l"))),
                    "b"));
    assertEquals(2, sessions.sessions().size());
    assertEquals(
        "inst-a",
        sessions.sessions().getFirst().instanceId(),
        "the incarnation id rides the listing beside the reusable name");
    assertEquals("inst-b", sessions.sessions().getLast().instanceId());
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
    assertEquals(
        "boot-7",
        ((PtyMessage.Welcome) roundTrip(new PtyMessage.Welcome("boot-7"))).hostBootId(),
        "the host's boot id rides the Hello reply");
    assertEquals(
        new PtyMessage.Yield("resume-1", "yielded to dispatch 2", "Dispatch 2 took it over."),
        roundTrip(
            new PtyMessage.Yield("resume-1", "yielded to dispatch 2", "Dispatch 2 took it over.")),
        "a yield's record crosses the wire apart from its line");
    assertEquals(
        new PtyMessage.Attach("lounge", true, "pane-7f", false),
        roundTrip(new PtyMessage.Attach("lounge", true, "pane-7f", false)),
        "an attach names its client and whether it takes the keyboard now");
    assertEquals(
        new PtyMessage.Attach("lounge", false, "", true),
        roundTrip(new PtyMessage.Attach("lounge", false)));
    assertEquals(
        new PtyMessage.WriterChanged("uday", true),
        roundTrip(new PtyMessage.WriterChanged("uday", true)),
        "a writer change says whether the keyboard is the receiving connection's");
    assertEquals(
        new PtyMessage.WriterChanged("", false),
        roundTrip(new PtyMessage.WriterChanged("", false)));
    assertInstanceOf(PtyMessage.TakeWrite.class, roundTrip(new PtyMessage.TakeWrite()));
    assertInstanceOf(PtyMessage.ReplayEnd.class, roundTrip(new PtyMessage.ReplayEnd()));
    assertEquals(
        "gone",
        ((PtyMessage.SessionEnded) roundTrip(new PtyMessage.SessionEnded("gone"))).reason());
    assertEquals("boom", ((PtyMessage.Err) roundTrip(new PtyMessage.Err("boom"))).message());
    assertEquals(
        new PtyMessage.IssueTicket(PtyMessage.TicketAction.ATTACH, "lounge", "sail_secret"),
        roundTrip(
            new PtyMessage.IssueTicket(PtyMessage.TicketAction.ATTACH, "lounge", "sail_secret")));
    assertEquals(new PtyMessage.Ticket("tkt_1"), roundTrip(new PtyMessage.Ticket("tkt_1")));
    var refusal =
        new PtyMessage.TicketRefused(
            PtyMessage.TicketRefusal.LIMITED,
            "Too many tickets are waiting.",
            Remedy.of("Wait, then run '", Remedy.sail("session ls"), "'."));
    assertEquals(refusal, roundTrip(refusal));
    assertEquals(
        "IssueTicket[action=ATTACH, target=lounge]",
        new PtyMessage.IssueTicket(PtyMessage.TicketAction.ATTACH, "lounge", "sail_secret")
            .toString(),
        "a credential in flight is never in a frame's words");
    assertEquals("Ticket[]", new PtyMessage.Ticket("tkt_1").toString());
  }

  private static ReadableByteChannel channelOf(ByteBuffer bytes) {
    return Channels.newChannel(new ByteArrayInputStream(bytes.array(), 0, bytes.limit()));
  }

  @Test
  void aTicketRefusalKindIsOneTheWireNamesOrRefused() {
    var unknown = ByteBuffer.allocate(64);
    unknown
        .putInt(1 + 4 + 4 + 4)
        .put((byte) 34)
        .putInt(4)
        .put("rude".getBytes(StandardCharsets.UTF_8));
    unknown.putInt(0).flip();
    assertEquals(
        "Corrupt pty frame: a ticket refusal of kind 'rude'.",
        assertThrows(IOException.class, () -> PtyWire.read(channelOf(unknown))).getMessage());
  }

  @Test
  void aTicketActionIsOneTheWireNamesOrRefused() {
    var unknown = ByteBuffer.allocate(64);
    unknown
        .putInt(1 + 4 + 5 + 4)
        .put((byte) 11)
        .putInt(5)
        .put("shell".getBytes(StandardCharsets.UTF_8));
    unknown.putInt(0).flip();
    assertEquals(
        "A ticket opens, attaches or reads files; 'shell' is none of them.",
        assertThrows(IOException.class, () -> PtyWire.read(channelOf(unknown))).getMessage());
  }

  @Test
  void aFrameLongerThanTheCapTheReaderSetsIsRefusedBeforeItsPayloadIsRead() throws Exception {
    var pipe = pipe();
    pipe.sink().write(ByteBuffer.allocate(4).putInt(257).flip());

    assertEquals(
        "Refusing pty frame of 257 bytes; the cap is 256 bytes.",
        assertThrows(IOException.class, () -> PtyWire.read(pipe.source(), 256)).getMessage());
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
      page.add(new PtyMessage.SessionInfo(name + i, name, true, 3, name, name, densest));
    }

    var listed = (PtyMessage.Sessions) roundTrip(new PtyMessage.Sessions(page, name));
    assertEquals(PtyMessage.PAGE_LIMIT, listed.sessions().size());
    assertEquals(densest, listed.sessions().getLast().command());
  }

  @Test
  void aFixedWidthFieldLandingOnTheBufferBoundaryStillEncodes() throws Exception {
    // The writer starts at 512 bytes and doubles; a session listing whose live flag and attach
    // count straddle a boundary overflowed and refused the whole listing, for that node, forever.
    for (var pad = 0; pad < 1_200; pad++) {
      var info = new PtyMessage.SessionInfo("s".repeat(pad), "i", true, 7, "uday", "", List.of());
      var listed = (PtyMessage.Sessions) roundTrip(new PtyMessage.Sessions(List.of(info), ""));
      assertEquals(7, listed.sessions().getFirst().attached(), "pad " + pad);
      var created =
          (PtyMessage.Create)
              roundTrip(new PtyMessage.Create("n", List.of("x".repeat(pad)), "", "", "", 80, 24));
      assertEquals(24, created.rows(), "pad " + pad);
    }
  }

  @Test
  void handshakeAcceptsItselfAndRefusesStrangers() throws Exception {
    var aToB = pipe();
    var bToA = pipe();
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

    var evil = pipe();
    var reply = pipe();
    evil.sink().write(ByteBuffer.wrap("NOTSAIL1".getBytes(StandardCharsets.US_ASCII)));
    assertThrows(IOException.class, () -> PtyWire.handshake(evil.source(), reply.sink()));

    var v2 = pipe();
    var v2Reply = pipe();
    v2.sink().write(ByteBuffer.wrap("SAILPTY6".getBytes(StandardCharsets.US_ASCII)));
    var skew =
        assertThrows(IOException.class, () -> PtyWire.handshake(v2.source(), v2Reply.sink()));
    assertTrue(
        skew.getMessage().contains("v7"),
        "a v6 peer, which knows no files frames and no files ticket, is refused by name: "
            + skew.getMessage());
  }

  @Test
  void oversizedAndUnknownFramesAreRefusedLoudly() throws Exception {
    var pipe = pipe();
    pipe.sink().write(ByteBuffer.allocate(4).putInt(PtyWire.MAX_FRAME + 1).flip());
    assertThrows(IOException.class, () -> PtyWire.read(pipe.source()));

    var unknown = pipe();
    unknown.sink().write(ByteBuffer.allocate(5).putInt(1).put((byte) 99).flip());
    var error = assertThrows(IOException.class, () -> PtyWire.read(unknown.source()));
    org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("99"));
  }

  @Test
  void aClosedChannelMidFrameIsEofNotGarbage() throws Exception {
    var pipe = pipe();
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

  private void readFramed(ByteBuffer payload) throws IOException {
    payload.flip();
    var frame = ByteBuffer.allocate(4 + payload.remaining());
    frame.putInt(payload.remaining()).put(payload).flip();
    var pipe = pipe();
    pipe.sink().write(frame);
    pipe.sink().close();
    PtyWire.read(pipe.source());
  }

  /**
   * R16's wire: a create, a ticket request and a listed session carry the workspace (and the
   * listing its opener); the default workspace {@code main} is the project's own container, and a
   * frame a peer sent before the field existed reads as none.
   */
  @Test
  void theWorkspaceRidesTheCreateTheTicketAndTheListingAndMainIsTheContainer() throws IOException {
    var create =
        (PtyMessage.Create)
            roundTrip(new PtyMessage.Create("s", List.of(), "/tmp", "acme", "", 80, 24, "try"));
    var ticket =
        (PtyMessage.IssueTicket)
            roundTrip(
                new PtyMessage.IssueTicket(PtyMessage.TicketAction.OPEN, "acme", "tok", "try"));
    var info =
        (PtyMessage.SessionInfo)
            roundTrip(
                new PtyMessage.SessionInfo("s", "i", true, 1, "ada", "", List.of("bash"))
                    .placed("acme", "try", "ada")
                    .lived(1_763_460_000_000L, ""));

    assertEquals("try", create.workspace());
    assertEquals("try", ticket.workspace());
    assertEquals(
        List.of("acme", "try", "ada"), List.of(info.project(), info.workspace(), info.opener()));
    assertEquals(1_763_460_000_000L, info.createdAtMillis());
    assertEquals(
        "", new PtyMessage.Create("s", List.of(), "/tmp", "acme", "", 80, 24, "main").workspace());
    assertEquals("", new PtyMessage.Create("s", List.of(), "/tmp", "acme", "", 80, 24).workspace());
  }

  /** A session's fields before its place, which a page writes after its cursor. */
  private static ByteBuffer unplacedSession(ByteBuffer out, String name) {
    put(out, name);
    put(out, "i");
    out.put((byte) 1).putInt(0);
    put(out, "");
    put(out, "");
    return out.putInt(0);
  }

  private static void put(ByteBuffer out, String text) {
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    out.putInt(bytes.length).put(bytes);
  }

  /**
   * A page's places follow its cursor, one for each of its sessions; a page placing other than its
   * sessions is refused, never misread.
   */
  @Test
  void aPagePlacesEachOfItsSessionsAfterItsCursorAndNoOtherCount() throws IOException {
    var misplaced = ByteBuffer.allocate(256).put((byte) 29).putInt(1);
    unplacedSession(misplaced, "a");
    put(misplaced, "");
    misplaced.putInt(2);

    assertEquals(
        "Corrupt pty frame: a page places 2 sessions but holds 1.",
        assertThrows(IOException.class, () -> readPayload(misplaced)).getMessage());
    assertEquals(
        List.of("acme", "try", "ada", "1763460000000", "exited(0)"),
        ((PtyMessage.Sessions)
                roundTrip(
                    new PtyMessage.Sessions(
                        List.of(
                            new PtyMessage.SessionInfo("s", "i", false, 0, "", "", List.of())
                                .placed("acme", "try", "ada")
                                .lived(1_763_460_000_000L, "exited(0)")),
                        "")))
            .sessions().stream()
                .flatMap(
                    placed ->
                        Stream.of(
                            placed.project(),
                            placed.workspace(),
                            placed.opener(),
                            String.valueOf(placed.createdAtMillis()),
                            placed.endedReason()))
                .toList());
  }
}
