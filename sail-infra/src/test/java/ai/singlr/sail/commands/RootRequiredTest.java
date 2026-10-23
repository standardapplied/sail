/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.singlr.sail.Sail;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

/**
 * A non-root caller refused a verb only root runs is told their own command, every word as typed,
 * with sudo: the re-run does what they asked, not a shorter command that does something else.
 */
class RootRequiredTest {

  private static final Map<String, List<String>> EVERY_OPTION =
      Map.ofEntries(
          Map.entry(
              "init",
              List.of(
                  "--main", "sail@m", "--handle", "ada", "--name", "Ada L", "--email", "a@b.io")),
          Map.entry(
              "host init",
              List.of(
                  "--storage",
                  "dir",
                  "--disk",
                  "50GiB",
                  "--pool",
                  "sail",
                  "--server-ip",
                  "10.0.0.2",
                  "--ssh-public-key",
                  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHzE4dW5kSzqQ0UClK/KaUnW7m79n7yVMhV6E+CPjFFt ada@mac",
                  "--yes",
                  "--json")),
          Map.entry("host keys sync", List.of()),
          Map.entry("host ssh-identity", List.of()),
          Map.entry("host status", List.of("--json")),
          Map.entry("host config set", List.of("sync-handle", "ada", "--json")),
          Map.entry(
              "host sync",
              List.of(
                  "--as-main",
                  "--handle",
                  "ada",
                  "--name",
                  "Ada L",
                  "--email",
                  "a@b.io",
                  "--json")),
          Map.entry("project destroy", List.of("gamma", "--purge", "--yes", "--json")),
          Map.entry("project rename", List.of("gamma", "delta", "--yes", "--json")),
          Map.entry(
              "project resources set",
              List.of("gamma", "--cpu", "4", "--memory", "8GiB", "--disk", "40GiB", "--json")),
          Map.entry("project demo", List.of("--json")));

  @BeforeEach
  void asACallerWhoIsNotRoot() {
    assumeFalse(SailPaths.isRoot(), "the refusal is for a caller who is not root");
  }

  @Test
  void aRefusedResourcesChangeKeepsTheChangeItWasAskedToMake() {
    assertEquals(
        "Root privileges required. Run with: sudo sail project resources set gamma --cpu 4",
        refusal("project", "resources", "set", "gamma", "--cpu", "4"));
  }

  @Test
  void aRefusedPurgeKeepsThePurgeAndItsConfirmation() {
    assertEquals(
        "Root privileges required. Run with: sudo sail project destroy gamma --purge --yes",
        refusal("project", "destroy", "gamma", "--purge", "--yes"));
  }

  @Test
  void aRefusedHostSyncKeepsTheFdeItNamesWithTheNameQuoted() {
    assertEquals(
        "Root privileges required. Run with: sudo sail host sync --as-main --handle ada --name"
            + " 'Ada L'",
        refusal("host", "sync", "--as-main", "--handle", "ada", "--name", "Ada L"));
  }

  @Test
  void aRefusedInitKeepsItsMainAndHandle() {
    assertEquals(
        "Root privileges required. Run with: sudo sail init --main sail@m --handle ada",
        refusal("init", "--main", "sail@m", "--handle", "ada"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "agent stream acme --token=s3cret --run r1|agent stream acme --token=<token> --run r1",
        "agent stream acme --token s3cret --run r1|agent stream acme --token <token> --run r1",
        "project demo --json --git-token ghp_x|project demo --json --git-token <token>",
        "project demo --git-token=ghp_x --json|project demo --git-token=<token> --json"
      })
  void aCommandAsTypedNeverEchoesAToken(String argv, String typed) {
    var parsed = new CommandLine(new Sail()).parseArgs(argv.split(" "));
    while (parsed.subcommand() != null) {
      parsed = parsed.subcommand();
    }

    assertEquals(typed, RootRequired.typed(parsed.commandSpec()).line());
  }

  @Test
  void aRefusedDemoKeepsItsGitTokenOptionAndNotItsValue() {
    assertEquals(
        "Root privileges required. Run with: sudo sail project demo --git-token <token>",
        refusal("project", "demo", "--git-token", "ghp_x"));
  }

  @Test
  void everyRootOnlyVerbIsCovered() {
    var verbs = new TreeSet<>(OperatorCommand.ROOT_ONLY);
    verbs.remove("project apply");

    assertEquals(verbs, new TreeSet<>(EVERY_OPTION.keySet()));
  }

  @Test
  void everyRootOnlyVerbIsToldToRunWhatTheCallerTyped() {
    var mismatched = new ArrayList<String>();
    EVERY_OPTION.forEach(
        (verb, options) -> {
          var typed = new ArrayList<>(Arrays.asList(verb.split(" ")));
          typed.addAll(options);
          var refusal = refusal(typed.toArray(String[]::new));
          var rerun = refusal.substring(refusal.indexOf("Run with: ") + "Run with: ".length());
          var words = shellWords(rerun);
          if (!words.subList(0, 2).equals(List.of("sudo", "sail"))
              || !words.subList(2, words.size()).equals(typed)
              || !parsedAlike(typed, words.subList(2, words.size()))) {
            mismatched.add(verb + " -> " + refusal);
          }
        });

    assertEquals(List.of(), mismatched);
  }

  private String refusal(String... args) {
    var ran = SailCli.run(args);
    assertEquals(1, ran.exit(), String.join(" ", args));
    var printed = ran.err().strip().lines().findFirst().orElse("");
    return printed.substring(printed.indexOf(' ') + 1);
  }

  private static boolean parsedAlike(List<String> typed, List<String> rerun) {
    return matched(typed).equals(matched(rerun));
  }

  private static List<String> matched(List<String> args) {
    var matched = new ArrayList<String>();
    for (var parsed = new CommandLine(new Sail()).parseArgs(args.toArray(String[]::new));
        parsed != null;
        parsed = parsed.subcommand()) {
      parsed
          .matchedOptions()
          .forEach(option -> matched.add(option.longestName() + "=" + option.getValue()));
      parsed.matchedPositionals().forEach(positional -> matched.add("" + positional.getValue()));
    }
    return matched;
  }

  /** Splits {@code line} as a POSIX shell does, for the single quotes a refusal uses. */
  private static List<String> shellWords(String line) {
    var words = new ArrayList<String>();
    var word = new StringBuilder();
    var quoted = false;
    var pending = false;
    for (var c : line.toCharArray()) {
      if (c == '\'') {
        quoted = !quoted;
        pending = true;
      } else if (c == ' ' && !quoted) {
        if (pending) {
          words.add(word.toString());
          word.setLength(0);
          pending = false;
        }
      } else {
        word.append(c);
        pending = true;
      }
    }
    if (pending) {
      words.add(word.toString());
    }
    return words;
  }
}
