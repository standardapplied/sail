/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ai.singlr.sail.engine.Child;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code ci/rules}, the rules a script decides over the sources, each over one offending and one
 * clean fixture in a real repository holding the script, and over this repository itself: every
 * rule on holds in it, and every rule not yet on still finds something in it, so the pull request
 * that clears a rule is the one that switches it on.
 */
class CiRulesTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String PANE = "clients/SailApp/Sources/SailShell/Pane.swift";
  private static final String KIT = "clients/SailKit/Sources/SailAPI/Kit.swift";
  private static final String TEST = "clients/SailKit/Tests/SailAPITests/KitTests.swift";
  private static final String JAVA = "sail-core/src/main/java/ai/singlr/sail/A.java";
  private static final Map<String, String> SAY =
      Map.of(
          "comments",
          "No inline comments: a documentation comment (/// or /** */) says a non-obvious why;"
              + " otherwise rename or extract.",
          "sleeps",
          "A Swift test waits for the event it needs, never for time: no Task.sleep, Thread.sleep,"
              + " usleep, nanosleep or sleep.",
          "ellipsis",
          "A menu item's or a button's label names its action, with no \"…\" or \"...\"; progress"
              + " text keeps its ellipsis.",
          "tokens",
          "A view takes its colours, fonts, spacing, radii and edge widths from the design system:"
              + " Look (clients/SailKit/Sources/SailDesign/Look.swift) and the fonts of"
              + " clients/SailApp/Sources/SailLook/, never a literal.",
          "components",
          "A view outside clients/SailApp/Sources/SailControls/ neither makes nor subclasses an"
              + " AppKit button, editable field, alert, panel or sheet: it uses SailButton,"
              + " SailTextField or Sheet.",
          "lists",
          "A list is a SailList and its rows are SailList's: a view outside"
              + " clients/SailApp/Sources/SailControls/ neither makes nor subclasses an AppKit"
              + " table, outline, collection, row or cell view.",
          "function-lines",
          "A function is at most 80 code lines (lines that are not blank and not only a comment):"
              + " split it.",
          "parameters",
          "A func or subscript takes at most 6 parameters (an init takes its type's stored"
              + " properties): pass a value that groups them.",
          "nesting",
          "A function's body nests at most 4 blocks deep: extract the inner ones.",
          "file-lines",
          "A file is at most 800 code lines (lines that are not blank and not only a comment):"
              + " split it.");

  @TempDir Path repo;

  private record Ran(int exit, String out, String err) {}

  @BeforeEach
  void aRepositoryHoldingTheScripts() throws Exception {
    git("init", "-q");
    for (var script : List.of("rules", "changelog")) {
      var copy = repo.resolve("ci").resolve(script);
      Files.createDirectories(copy.getParent());
      Files.copy(ROOT.resolve("ci").resolve(script), copy);
      copy.toFile().setExecutable(true);
    }
    write("changes/README.md", "One file per change.\n");
  }

  static Stream<Arguments> rules() {
    return Stream.of(
        Arguments.of(
            "comments",
            KIT,
            """
            /// A kit.
            struct Kit {
                // a note
                let url = "https://example.com//x" /* a block */
            }
            """,
            List.of("3: // a note", "4: /* a block */"),
            """
            /// A kit, whose note is its documentation.
            /** And a block of it. */
            struct Kit {
                let url = "https://example.com//x"
                let said = "/* not a comment */ // nor this"
            }
            """),
        Arguments.of(
            "comments",
            JAVA,
            """
            /*
             * Copyright
             */
            class A {
              char quote = '"';
              int x = 1; // a note
              /* a block */
            }
            """,
            List.of("6: // a note", "7: /* a block */"),
            """
            /*
             * Copyright
             */
            /** A, documented. */
            class A {
              char quote = '"';
              String url = "https://example.com//x";
              String block = \"""
                  /* not a comment */ // nor this \\\"""
                  \""";
            }
            """),
        Arguments.of(
            "comments",
            "clients/SailKit/Package.swift",
            """

            // swift-tools-version: 6.2
            """,
            List.of("2: // swift-tools-version: 6.2"),
            """
            // swift-tools-version: 6.2

            import PackageDescription
            """),
        Arguments.of(
            "sleeps",
            TEST,
            """
            @Test func waits() async throws {
                try await Task.sleep(for: .seconds(1))
                usleep(10); Thread.sleep(forTimeInterval: 1)
                try await ContinuousClock().sleep(for: .seconds(1))
            }
            """,
            List.of(
                "2: Task.sleep(", "3: usleep(", "3: Thread.sleep(", "4: ContinuousClock().sleep("),
            """
            final class Naps {
                func sleep(_ duration: Duration) async throws {}
            }
            @Test func waitsForTheEvent() async throws {
                try await naps.sleep(Duration.seconds(1))
                let script = "sleep 600; sleep(1)"
            }
            """),
        Arguments.of(
            "ellipsis",
            PANE,
            """
            let open = NSMenuItem(title: "Open…", action: nil, keyEquivalent: "")
            button.title = "Save..."
            """,
            List.of("1: \"Open…\"", "2: \"Save...\""),
            """
            banner.show("Reconnecting…", button: "Reconnect Now")
            let open = NSMenuItem(title: "Open", action: nil, keyEquivalent: "")
            let cut = words(kept + "…")
            """),
        Arguments.of(
            "tokens",
            PANE,
            """
            let ink = NSColor(red: 1, green: 0, blue: 0, alpha: 1), shade = Tint(0x0A0E11)
            stack.spacing = 6
            layer.cornerRadius = 3
            label.font = NSFont.systemFont(ofSize: 12)
            box.contentViewMargins = NSEdgeInsets(top: 0, left: 8, bottom: 0, right: 0)
            """,
            List.of(
                "1: NSColor(red:",
                "1: Tint(0x0A0E11",
                "2: spacing = 6",
                "3: cornerRadius = 3",
                "4: NSFont.systemFont(ofSize: 12",
                "5: NSEdgeInsets(top: 0, left: 8, bottom: 0, right: 0)"),
            """
            let ink = NSColor(look.text), none = NSColor.clear
            stack.spacing = Look.Space.s
            layer.cornerRadius = 0
            label.font = NSFont.systemFont(ofSize: Look.Step.body.points)
            box.contentViewMargins = NSEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)
            let said = "NSColor(red: 1) spacing = 6"
            """),
        Arguments.of(
            "components",
            PANE,
            """
            final class RingButton: NSButton {}
            let alert = NSAlert(), field = NSTextField(string: "")
            window.beginSheet(panel)
            """,
            List.of(
                "1: a subclass of NSButton",
                "2: NSAlert(",
                "2: NSTextField(string:",
                "3: beginSheet("),
            """
            let label = NSTextField(labelWithString: ""), menu = NSMenu()
            let button = SailButton(title: "Create", kind: .primary)
            let said = "NSAlert()"
            """),
        Arguments.of(
            "lists",
            PANE,
            """
            final class Row: NSTableRowView {}
            let table = NSTableView()
            """,
            List.of("1: a subclass of NSTableRowView", "2: NSTableView("),
            """
            let list = SailList(style: .main)
            """),
        Arguments.of(
            "function-lines",
            KIT,
            function("long", 79, ""),
            List.of("1: long is 81 code lines, over the cap of 80"),
            function("long", 78, "\n    /// a why\n\n")),
        Arguments.of(
            "parameters",
            KIT,
            """
            func many(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int) {}
            """,
            List.of("1: many takes 7 parameters, over the cap of 6"),
            """
            protocol Asks {
                func asked(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int)
            }
            func six(a: [String: Int], b: (Int, Int) -> Void, c: Dictionary<String, Int>,
                     d: Int = max(1, 2), e: Int, f: Int) {}
            struct Many {
                init(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int) {}
            }
            """),
        Arguments.of(
            "nesting",
            KIT,
            """
            func deep() {
                if a {
                    for b in c {
                        while d {
                            do {
                                let e = f.map { $0 }
                            }
                        }
                    }
                }
            }
            """,
            List.of("1: deep nests 5 blocks deep, over the cap of 4"),
            """
            func deep() {
                if a {
                    for b in c {
                        while d {
                            let e = f.map { "{{{\\(g)" }
                        }
                    }
                }
            }
            """),
        Arguments.of(
            "file-lines",
            KIT,
            lets(801, ""),
            List.of("1: the file is 801 code lines, over the cap of 800"),
            lets(800, "\n/// a why\n")));
  }

  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("rules")
  void aRuleFindsItsOffendingFixtureAndHoldsOverItsCleanOne(
      String rule, String path, String offending, List<String> found, String clean)
      throws Exception {
    write(path, offending);

    var refused = sh("ci/rules", "run", rule);

    assertEquals(1, refused.exit(), refused.err());
    assertEquals(
        found.stream()
            .map(line -> line.split(": ", 2))
            .map(at -> path + ":" + at[0] + ": " + rule + ": " + at[1] + ": " + SAY.get(rule))
            .collect(Collectors.joining("\n", "", "\n")),
        refused.out());
    assertEquals(
        found.size() == 1
            ? "ci/rules: 1 finding; its line names its rule.\n"
            : "ci/rules: " + found.size() + " findings; each line names its rule.\n",
        refused.err());

    write(path, clean);

    var held = sh("ci/rules", "run", rule);

    assertEquals(0, held.exit(), held.out() + held.err());
    assertEquals("", held.out());
  }

  @Test
  void theDesignSystemMayHoldLiteralsAndTheControlsMakeThePrimitives() throws Exception {
    write("clients/SailKit/Sources/SailDesign/Look.swift", "let canvas = RGB(0x0A0E11)\n");
    write("clients/SailKit/Sources/SailVT/Palette.swift", "let ink = RGB(0xF6F1E9)\n");
    write(
        "clients/SailApp/Sources/SailLook/Colours.swift",
        "let c = NSColor(srgbRed: 1, green: 0, blue: 0, alpha: 1)\n");
    write(
        "clients/SailApp/Sources/SailControls/SailButton.swift",
        "final class SailButton: NSButton {}\n");
    write(
        "clients/SailApp/Sources/SailControls/SailList.swift",
        "final class SailList: NSTableView {}\n");
    write(
        "clients/SailApp/Sources/SailDrive/Steps.swift",
        "let gap = NSStackView(); gap.spacing = 40; let a = NSAlert()\n");

    var held = sh("ci/rules", "run", "tokens", "components", "lists");

    assertEquals(0, held.exit(), held.out() + held.err());
  }

  @Test
  void aSleepOutsideTheTestsIsNotThisRules() throws Exception {
    write(KIT, "func pause() async throws { try await Task.sleep(for: .seconds(1)) }\n");

    assertEquals(0, sh("ci/rules", "run", "sleeps").exit());
  }

  @Test
  void codeInsideAnInterpolationIsCodeAndNothingElseInAStringIs() throws Exception {
    write(
        PANE,
        """
        let raw = #"NSAlert() "\\(x)" \\#(NSAlert())"#
        let quoted = "a \\"quote\\" and \\(name.map { "NSPanel(" }) then \\(NSAlert())"
        let shader = \"""
            NSAlert() // and a { brace
            \""";
        let match = line.matches(of: /NSAlert\\(/)
        """);

    var ran = sh("ci/rules", "run", "components", "comments");

    assertEquals(
        PANE
            + ":1: components: NSAlert(: "
            + SAY.get("components")
            + "\n"
            + PANE
            + ":2: components: NSAlert(: "
            + SAY.get("components")
            + "\n",
        ran.out());
  }

  @Test
  void aNestedBlockCommentEndsWhereItsOutermostEnds() throws Exception {
    write(PANE, "/* outer /* inner */ still NSAlert() */\nlet alert = NSAlert()\n");

    var ran = sh("ci/rules", "run", "components");

    assertEquals(PANE + ":2: components: NSAlert(: " + SAY.get("components") + "\n", ran.out());
  }

  @Test
  void checkHoldsOnlyTheRulesSwitchedOn() throws Exception {
    write(KIT, "// a note\n");
    write(PANE, "let table = NSTableView()\n");

    var ran = sh("ci/rules", "check");

    assertEquals(1, ran.exit());
    assertEquals(PANE + ":1: lists: NSTableView(: " + SAY.get("lists") + "\n", ran.out());
  }

  @Test
  void checkWithAPullRequestsBaseHoldsItsEntryToo() throws Exception {
    var base = commit();
    git("checkout", "-q", "-b", "agent/the-spec");
    write(KIT, "let kit = 1\n");
    commit();

    var refused = sh("ci/rules", "check", base);

    assertEquals(1, refused.exit());
    assertEquals(
        "ci/changelog: this change adds no entry: add changes/the-spec.md holding its bullets as"
            + " they would read under the version heading (changes/README.md).\n",
        refused.err());

    write("changes/the-spec.md", "- **Kit.** One.\n");
    commit();

    var held = sh("ci/rules", "check", base);

    assertEquals(0, held.exit(), held.err());
    assertEquals("ci/rules: held: ellipsis lists changes.\n", held.err());
  }

  @Test
  void aRuleThatIsNoneIsRefusedNamingTheList() throws Exception {
    var ran = sh("ci/rules", "run", "spelling");

    assertEquals(2, ran.exit());
    assertEquals("ci/rules: 'spelling' is no rule; 'ci/rules list' names them.\n", ran.err());
  }

  @Test
  void theListSaysOfEveryRuleWhetherItIsOnAndItsSentence() throws Exception {
    var listed = sh("ci/rules", "list").out().lines().toList();

    assertEquals(
        SAY.keySet().stream().sorted().toList(),
        listed.stream()
            .map(line -> line.substring(0, line.indexOf(' ')))
            .filter(SAY::containsKey)
            .sorted()
            .toList());
    for (var line : listed) {
      var rule = line.substring(0, line.indexOf(' '));
      if (SAY.containsKey(rule)) {
        assertEquals(SAY.get(rule), line.substring(line.indexOf("): ") + 3));
      }
    }
  }

  @Test
  void everyRuleOnHoldsInThisRepository() throws Exception {
    var ran = here("check");

    assertEquals(0, ran.exit(), ran.out() + ran.err());
  }

  @Test
  void everyRuleNotYetOnStillFindsSomethingInThisRepository() throws Exception {
    var notYet =
        here("list")
            .out()
            .lines()
            .filter(line -> line.contains(" (not yet on): "))
            .map(line -> line.substring(0, line.indexOf(' ')))
            .toList();

    for (var rule : notYet) {
      assertNotEquals(
          0,
          here("run", rule).exit(),
          rule + " holds in this repository now: switch it on in ci/rules (ON) and in AGENTS.md");
    }
  }

  private static String function(String name, int statements, String between) {
    return "func "
        + name
        + "() {\n"
        + IntStream.range(0, statements)
            .mapToObj(index -> "    let a" + index + " = " + index + "\n")
            .collect(Collectors.joining(between))
        + "}\n";
  }

  private static String lets(int count, String between) {
    return IntStream.range(0, count)
        .mapToObj(index -> "let a" + index + " = " + index + "\n")
        .collect(Collectors.joining(between));
  }

  private Ran here(String... args) throws Exception {
    var command = new ArrayList<>(List.of("bash", ROOT.resolve("ci/rules").toString()));
    command.addAll(Arrays.asList(args));
    return run(command, ROOT);
  }

  private String commit() throws Exception {
    git("add", "-A");
    git("commit", "-q", "--allow-empty", "-m", "change");
    return git("rev-parse", "HEAD").out().strip();
  }

  private void write(String path, String content) throws IOException {
    var file = repo.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private Ran git(String... args) throws Exception {
    var command =
        new ArrayList<>(List.of("git", "-c", "user.name=CI", "-c", "user.email=ci@example.com"));
    command.addAll(Arrays.asList(args));
    var ran = run(command, repo);
    assertEquals(0, ran.exit(), String.join(" ", command) + " failed: " + ran.err());
    return ran;
  }

  private Ran sh(String... args) throws Exception {
    var command = new ArrayList<>(List.of("bash"));
    command.addAll(Arrays.asList(args));
    return run(command, repo);
  }

  private Ran run(List<String> command, Path directory) throws Exception {
    var builder = new ProcessBuilder(command).directory(directory.toFile());
    builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    builder.environment().remove("GITHUB_HEAD_REF");
    var ran = Child.run(builder);
    return new Ran(ran.exit(), ran.stdout(), ran.stderr());
  }
}
