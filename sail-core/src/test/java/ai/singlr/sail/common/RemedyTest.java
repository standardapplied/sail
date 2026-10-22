/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemedyTest {

  private static final Remedy SCOPE =
      Remedy.of(
          "Scope it first with '",
          Remedy.sail("fde update", "ada", "--scoped"),
          "', or '",
          Remedy.program("ssh-keygen", "-R", "main"),
          "' from '",
          Remedy.client("login"),
          "'.");

  @Test
  void aRemedyIsWordsAndCommandsKeptAsData() {
    assertEquals(
        List.of(
            Remedy.sail("fde update", "ada", "--scoped"),
            Remedy.program("ssh-keygen", "-R", "main"),
            Remedy.client("login")),
        SCOPE.commands());
    assertEquals("fde update ada --scoped", SCOPE.commands().getFirst().line());
    assertEquals("login", Remedy.client("login").line());
  }

  @Test
  void aReaderWhoTypesPlainSailReadsThePlainForm() {
    assertEquals(
        "Scope it first with 'sail fde update ada --scoped', or 'ssh-keygen -R main' from 'sail"
            + " login'.",
        SCOPE.plain());
  }

  @Test
  void aReaderWhoTypesNothingReadsEachCommandAsItsVerb() {
    assertEquals(
        "Scope it first with 'fde update ada --scoped', or 'ssh-keygen -R main' from 'login'.",
        SCOPE.described());
  }

  @Test
  void adjacentWordsMergeAndEmptyWordsVanish() {
    assertEquals(
        List.of(new Remedy.Words("a b"), Remedy.sail("sync")),
        Remedy.of("a", "", " b", Remedy.sail("sync")).parts());
    assertEquals(Remedy.of("a b"), Remedy.of("a").then(" b"));
    assertEquals(
        Remedy.of("x ", Remedy.sail("sync")), Remedy.of("x ").then(Remedy.of(Remedy.sail("sync"))));
    assertTrue(Remedy.of("").isEmpty());
    assertTrue(Remedy.NONE.isEmpty());
  }

  @Test
  void aRemedyRoundTripsThroughTheMapsAClientReads() {
    assertEquals(
        List.of(
            Map.of("text", "Scope it first with '"),
            Map.of("run", "sail", "verb", "fde update", "args", List.of("ada", "--scoped")),
            Map.of("text", "', or '"),
            Map.of("run", "program", "verb", "ssh-keygen", "args", List.of("-R", "main")),
            Map.of("text", "' from '"),
            Map.of("run", "client", "verb", "login", "args", List.of()),
            Map.of("text", "'.")),
        SCOPE.toMaps());
    assertEquals(SCOPE, Remedy.fromMaps(SCOPE.toMaps()));
  }

  @Test
  void aPartThatIsNeitherWordsNorACommandIsRefused() {
    assertEquals(
        "A remedy part is an object, not 3",
        assertThrows(IllegalArgumentException.class, () -> Remedy.fromMaps(List.of(3)))
            .getMessage());
    assertEquals(
        "A remedy part is words or a command, not {verb=sync}",
        assertThrows(
                IllegalArgumentException.class,
                () -> Remedy.fromMaps(List.of(Map.of("verb", "sync"))))
            .getMessage());
    assertEquals(
        "A remedy is words and commands, not 3",
        assertThrows(IllegalArgumentException.class, () -> Remedy.of(3)).getMessage());
    assertEquals(
        "A command names its verb.",
        assertThrows(IllegalArgumentException.class, () -> Remedy.sail(" ")).getMessage());
  }

  @Test
  void aRemediedFailureSaysWhatHappenedApartFromWhatToDo() {
    var state =
        Remedied.state(
            "Gone.", Remedy.of("Run '", Remedy.sail("migrate"), "'."), new IOException());
    var argument = Remedied.argument("Bad.", Remedy.of("Try '", Remedy.sail("sync"), "'."));

    assertEquals("Gone. Run 'sail migrate'.", state.getMessage());
    assertEquals("Gone.", ((Remedied) state).reason());
    assertEquals(IOException.class, state.getCause().getClass());
    assertEquals("Bad. Try 'sail sync'.", argument.getMessage());
    assertEquals(Remedy.of("Try '", Remedy.sail("sync"), "'."), ((Remedied) argument).remedy());
    assertEquals("Bad. Try 'sync'.", ((Remedied) argument).render(Remedy.Command::line));
    assertNull(Remedied.state("Plain.", Remedy.NONE).getCause());
    assertEquals("Plain.", Remedied.state("Plain.", Remedy.NONE).getMessage());
    assertEquals(
        "Run 'sail sync'.",
        Remedied.state("", Remedy.of("Run '", Remedy.sail("sync"), "'.")).getMessage());
  }

  @Test
  void aWrappedFailureKeepsItsReasonAndItsRemedyApart() {
    var remedied = Remedied.state("Gone.", Remedy.of("Run '", Remedy.sail("migrate"), "'."));
    assertEquals("Gone.", Remedied.reasonOf(remedied));
    assertEquals(Remedy.of("Run '", Remedy.sail("migrate"), "'."), Remedied.remedyOf(remedied));
    assertEquals("boom", Remedied.reasonOf(new IllegalStateException("boom")));
    assertEquals(Remedy.NONE, Remedied.remedyOf(new IllegalStateException("boom")));
    assertEquals("IOException", Remedied.reasonOf(new IOException()));
    assertEquals("Not valid: Gone.", Remedied.joined("Not valid:", "Gone."));
    assertEquals("Not valid:", Remedied.joined("Not valid:", ""));
    assertEquals("Gone.", Remedied.joined("", "Gone."));
  }

  @Test
  void aFailureIsOneRemedyWhetherItNamesOneOrNot() {
    assertEquals(
        Remedy.of("Gone. Run '", Remedy.sail("migrate"), "'."),
        Remedied.whole(Remedied.state("Gone.", Remedy.of("Run '", Remedy.sail("migrate"), "'."))));
    assertEquals(Remedy.of("Plain."), Remedied.whole(Remedied.state("Plain.", Remedy.NONE)));
    assertEquals(
        Remedy.of("Run '", Remedy.sail("sync"), "'."),
        Remedied.whole(Remedied.argument("", Remedy.of("Run '", Remedy.sail("sync"), "'."))));
    assertEquals(Remedy.of("boom"), Remedied.whole(new IllegalStateException("boom")));
    assertEquals(Remedy.of("IOException"), Remedied.whole(new IOException()));
  }
}
