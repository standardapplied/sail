/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.singlr.sail.access.baseline.GoldenFiles;
import ai.singlr.sail.config.YamlUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * What a ticket of each action admits, asked of the host's own {@link PtyTickets.Ticket#refusal}
 * for a sample of every frame the clients' frames golden holds, each aimed at the ticket's own
 * target: written where the clients read it, so their fake door admits exactly what the host does,
 * and a frame the host comes to admit is a golden change, not a second list to remember.
 */
class TicketAdmitsGoldenTest {

  private static final Path GOLDENS = Path.of("..", "clients", "SailKit", "Tests", "Goldens");

  @Test
  void whatEachTicketAdmitsIsTheHosts() throws IOException {
    var samples = samples();
    var create =
        samples.stream()
            .filter(PtyMessage.Create.class::isInstance)
            .map(PtyMessage.Create.class::cast)
            .findFirst()
            .orElseThrow();
    var admits = new TreeMap<String, Object>();
    for (var action : PtyMessage.TicketAction.values()) {
      var names = new TreeSet<String>();
      for (var message : samples) {
        var own = own(message);
        var ticket =
            switch (action) {
              case OPEN ->
                  new PtyTickets.Ticket(action, create.project(), "tok-uday", create.workspace());
              case ATTACH ->
                  new PtyTickets.Ticket(action, own == null ? "lounge" : own, "tok-uday");
              case FILES -> new PtyTickets.Ticket(action, "harbor", "tok-uday", "dock");
            };
        if (ticket.refusal(message, own, own) == null) {
          names.add(message.getClass().getSimpleName());
        }
      }
      assertFalse(names.isEmpty(), action.wire() + " admits nothing");
      admits.put(action.wire(), List.copyOf(names));
    }
    GoldenFiles.check(
        GOLDENS.resolve("tickets"),
        new TreeMap<>(Map.of("admits.json", YamlUtil.dumpJsonPretty(admits) + "\n")));
  }

  /** A sample of every pty frame, as the frames golden holds its wire bytes. */
  @SuppressWarnings("unchecked")
  private static List<PtyMessage> samples() throws IOException {
    var golden =
        YamlUtil.parseMap(Files.readString(GOLDENS.resolve("frames").resolve("frames.json")));
    var samples = new ArrayList<PtyMessage>();
    for (var frame : (List<Map<String, Object>>) golden.get("frames")) {
      var wire = HexFormat.of().parseHex((String) frame.get("hex"));
      samples.add(PtyWire.read(Channels.newChannel(new ByteArrayInputStream(wire))));
    }
    return samples;
  }

  /** The session a frame names as its own, which a ticket aimed at it holds. */
  private static String own(PtyMessage message) {
    return switch (message) {
      case PtyMessage.Attach attach -> attach.session();
      case PtyMessage.Kill kill -> kill.session();
      default -> null;
    };
  }
}
