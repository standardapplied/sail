/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.Child;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
 * clean fixture in a real repository holding the scripts, and over this repository itself, where
 * {@code ci/rules check} holds every rule on and fails a rule not yet on that finds nothing.
 */
class CiRulesTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String PANE = "clients/SailApp/Sources/SailShell/Pane.swift";
  private static final String KIT = "clients/SailKit/Sources/SailAPI/Kit.swift";
  private static final String TEST = "clients/SailKit/Tests/SailAPITests/KitTests.swift";
  private static final String JAVA = "sail-core/src/main/java/ai/singlr/sail/A.java";
  private static final String LOCAL = "ci/check rules \"$(git merge-base origin/main HEAD)\"";
  private static final Pattern CI_STEP =
      Pattern.compile(
          "- name: The rules a script decides\n\\s+env:\n\\s+BASE: (.+)\n\\s+run: (.+)\n");
  private static final String CHANGES =
      "A pull request adds or edits its own entry, changes/<name>.md of its branch agent/<name>,"
          + " adds or deletes no other, and never edits CHANGELOG.md.";
  private static final String MANY =
      "func many(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int) {}\n";
  private static final String DEEP =
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
      """;
  private static final String HELD = "ci/rules: held: ellipsis lists.\n";
  private static final String HELD_WITH_ENTRY = "ci/rules: held: ellipsis lists changes.\n";
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
    for (var script : List.of("rules", "changelog", "check")) {
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
            let delete = SailButton("Delete…", kind: .destructive, look: look, target: nil, action: nil)
            let more = SailButton(icon: .more, label: "More...", look: look, target: nil, action: nil)
            let help = LinkButton("Help\\u{2026}", look: look, target: nil, action: nil)
            let rename = SailList.Action("Rename…") { rename() }
            let sheet = Sheet(title: "Delete it?", body: "It goes.", confirm: "Delete…", kind: .destructive, look: look, titleFont: titleFont)
            let asked = Question(project: p, name: n, action: .stop, title: "Stop it?", body: "It stops.", confirm: "Stop…", terminals: nil, detail: nil, asked: 0)
            banner.show("It ended.", tone: .warning, button: "Open Again…", second: "Close…")
            Confirmation.ask("End it?", detail: "It ends.", action: "End…", on: window) { _ in }
            let copy = Command(title: "Copy…", action: #selector(NSText.copy(_:)), key: "c")
            let items = [item("Account…", #selector(ShellApp.showAccount(_:)), ","), directed("Select Split Above…", select, .up, [.command])]
            let file = submenu("File…", [])
            let open = NSMenuItem(title: "Open…", action: nil, keyEquivalent: "")
            let view = NSMenu(title: "View…")
            let wrap = NSButton(checkboxWithTitle: "Wrap…", target: nil, action: nil), tabs = NSButton(radioButtonWithTitle: "Tabs…", target: nil, action: nil)
            let push = NSButton(title: "Push…", target: nil, action: nil)
            menu.addItem(withTitle: "Quit…", action: nil, keyEquivalent: "q"); menu.insertItem(withTitle: "Hide…", action: nil, keyEquivalent: "h", at: 0)
            alert.addButton(withTitle: "Save…")
            let saveAs = NSMenuItem(title:
                "Save As…", action: nil, keyEquivalent: "")
            let later = SailButton(
                "Later…", kind: .quiet, look: look, target: nil, action: nil)
            let print = NSMenuItem(title: \"""
                Print…
                \""", action: nil, keyEquivalent: "")
            let find = NSMenuItem.init(title: "Find…", action: nil, keyEquivalent: "f")
            let add = SailButton(
                icon: .plus,
                label: "New…", look: look, target: nil, action: nil)
            let shell = SailButton(open ? "Open a Shell…" : "Close", kind: .secondary, look: look, target: nil, action: nil)
            let close = SailButton(open ? "Close" : "Close All…", kind: .secondary, look: look, target: nil, action: nil)
            let retry = LinkButton(button ?? "Try Again…", look: look, target: nil, action: nil)
            let stop = NSMenuItem(title: busy ? "Stopping" : "Stop…", action: nil, keyEquivalent: "")
            let name = NSMenuItem(title: tab?.title ?? "Untitled…", action: nil, keyEquivalent: "")
            """,
            List.of(
                "1: \"Delete…\"",
                "2: \"More...\"",
                "3: \"Help\\u{2026}\"",
                "4: \"Rename…\"",
                "5: \"Delete…\"",
                "6: \"Stop…\"",
                "7: \"Open Again…\"",
                "7: \"Close…\"",
                "8: \"End…\"",
                "9: \"Copy…\"",
                "10: \"Account…\"",
                "10: \"Select Split Above…\"",
                "11: \"File…\"",
                "12: \"Open…\"",
                "13: \"View…\"",
                "14: \"Wrap…\"",
                "14: \"Tabs…\"",
                "15: \"Push…\"",
                "16: \"Quit…\"",
                "16: \"Hide…\"",
                "17: \"Save…\"",
                "19: \"Save As…\"",
                "21: \"Later…\"",
                "22: \"\\n    Print…\\n    \"",
                "25: \"Find…\"",
                "28: \"New…\"",
                "29: \"Open a Shell…\"",
                "30: \"Close All…\"",
                "31: \"Try Again…\"",
                "32: \"Stop…\"",
                "33: \"Untitled…\""),
            """
            banner.show("Reconnecting…", button: "Reconnect Now")
            let open = NSMenuItem(title: "Open", action: nil, keyEquivalent: "")
            let cut = words(kept + "…")
            let note = Banner(.info, title: "Connecting…", look: look)
            let empty = EmptyState(icon: .box, title: "Loading…", body: "Asking the runner…", actions: [], look: look, titleFont: titleFont, mono: mono)
            window.title = "Opening…"
            let name = SailTextField(label: "Name…", placeholder: "my-project…", look: look, mono: mono)
            let queue = DispatchQueue(label: "sh.standardapplied.sail...")
            let sheet = Sheet(title: "Delete…?", body: "Deleting…", confirm: "Delete", kind: .destructive, look: look, titleFont: titleFont)
            let back = SailButton(#"Back\\u{2026}"#, kind: .quiet, look: look, target: nil, action: nil)
            let more = SailButton(icon: .more, label: "More", look: look, target: nil, action: nil)
            banner.show(lost ? "Lost…" : "Reconnecting…", button: retrying ? "Retry" : "Reconnect Now")
            let ellipsis = item("Ellipsis", #selector(TerminalView.insertEllipsis(_:)), "…")
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
            let button = SailButton("Create", kind: .primary, look: look, target: nil, action: nil)
            let said = "NSAlert()"
            """),
        Arguments.of(
            "lists",
            PANE,
            """
            final class Row: NSTableRowView {}
            final class Cell:
                NSTableCellView {}
            let table = NSTableView(), outline = AppKit.NSOutlineView()
            let grid = NSCollectionView.init(frame: .zero)
            let typed: NSTableView = .init()
            final class Grid<T>: NSCollectionView, Sendable {}
            final class Tree: AppKit.NSOutlineView {}
            let cell = NSTableCellView(), row = NSTableRowView(frame: .zero)
            final class Table: NSTableView {}
            """,
            List.of(
                "1: a subclass of NSTableRowView",
                "3: a subclass of NSTableCellView",
                "4: NSTableView(",
                "4: AppKit.NSOutlineView(",
                "5: NSCollectionView.init(",
                "6: NSTableView = .init(",
                "7: a subclass of NSCollectionView",
                "8: a subclass of NSOutlineView",
                "9: NSTableCellView(",
                "9: NSTableRowView(",
                "10: a subclass of NSTableView"),
            """
            let list = SailList(rowStyle: .tree, look: look, mono: mono)
            let said = "NSTableView()"
            func rows(in table: NSTableView) -> Int { table.numberOfRows }
            let view = table.makeView(withIdentifier: id, owner: nil) as? NSTableCellView
            final class Holder: NSView {
                let table: NSTableView? = nil
            }
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
            MANY,
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
            DEEP,
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
  void checkHoldsTheRulesOnAndOnlyCountsTheRulesNotYetOn() throws Exception {
    notYetRulesFindOneEach();

    assertEquals(new Ran(0, "", HELD), sh("ci/rules", "check"));

    write(PANE, "let table = NSTableView()\n");

    assertEquals(
        new Ran(
            1,
            PANE + ":1: lists: NSTableView(: " + SAY.get("lists") + "\n",
            "ci/rules: 1 finding; its line names its rule.\n"),
        sh("ci/rules", "check"));
  }

  @Test
  void aRuleNotYetOnThatFindsNothingFailsCheckUntilItIsSwitchedOn() throws Exception {
    notYetRulesFindOneEach();
    Files.delete(repo.resolve(TEST));

    assertEquals(
        new Ran(
            1,
            "",
            "ci/rules: sleeps is not yet on and finds nothing now: switch it on, moving it from"
                + " NOT_YET to ON in ci/rules and its row in AGENTS.md to the rules on.\n"),
        sh("ci/rules", "check"));
  }

  @Test
  void theStepBothWorkflowsRunHoldsAPullRequestToItsEntryAndAPushToTheRulesOn() throws Exception {
    var step = ciStep(".github/workflows/ci.yml");
    assertEquals(step, ciStep(".github/workflows/clients.yml"));
    assertEquals("${{ github.event.pull_request.base.sha }}", step.get(0));
    notYetRulesFindOneEach();
    var base = commit();
    git("checkout", "-q", "-b", "agent/the-spec");
    write(PANE, "let pane = 1\n");
    commit();

    assertEquals(
        new Ran(
            1,
            "",
            "ci/changelog: this change neither adds nor edits changes/the-spec.md, the entry its"
                + " branch agent/the-spec names (a branch agent/<name> keeps its entry in"
                + " changes/<name>.md): add it, holding its bullets as they would read under the"
                + " version heading (changes/README.md).\n"),
        bash(step.get(1), Map.of("BASE", base)));

    write("changes/the-spec.md", "- **Pane.** One.\n");
    commit();

    assertEquals(new Ran(0, "", HELD_WITH_ENTRY), bash(step.get(1), Map.of("BASE", base)));
    assertEquals(new Ran(0, "", HELD), bash(step.get(1), Map.of("BASE", "")));
  }

  @Test
  void theReleasesFoldPassesTheStepAPushToMainRuns() throws Exception {
    var step = ciStep(".github/workflows/ci.yml");
    notYetRulesFindOneEach();
    write("CHANGELOG.md", "# Changelog\n");
    write("changes/one.md", "- **One.** Unreleased.\n");
    write("changes/two.md", "- **Two.** Unreleased.\n");
    commit();

    assertEquals(0, sh("ci/changelog", "0.77.0").exit());
    commit();

    assertEquals(new Ran(0, "", HELD), bash(step.get(1), Map.of("BASE", "")));
    assertEquals(0, sh("ci/changelog", "released", "0.77.0").exit());
  }

  @Test
  void theLocalCommandHoldsABranchThatLeftMainBeforeItsLastRelease() throws Exception {
    assertTrue(Files.readString(ROOT.resolve("CONTRIBUTING.md")).contains(LOCAL));
    assertTrue(Files.readString(ROOT.resolve("AGENTS.md")).contains(LOCAL));
    notYetRulesFindOneEach();
    write("CHANGELOG.md", "# Changelog\n");
    write("changes/old.md", "- **Old.** Unreleased.\n");
    var left = commit();
    git("checkout", "-q", "-b", "agent/fresh");
    write("changes/fresh.md", "- **Fresh.** New.\n");
    commit();
    git("checkout", "-q", "-b", "released", left);
    git("rm", "-q", "changes/old.md");
    write("CHANGELOG.md", "# Changelog\n\n## 0.76.0\n\n- **Old.** Unreleased.\n");
    git("update-ref", "refs/remotes/origin/main", commit());
    git("checkout", "-q", "agent/fresh");

    assertEquals(new Ran(0, "", HELD_WITH_ENTRY), bash(LOCAL, Map.of()));
    assertEquals(1, sh("ci/check", "rules", "origin/main").exit());
  }

  @Test
  void aBaseThatNamesNoCommitIsRefusedNeverHeld() throws Exception {
    notYetRulesFindOneEach();

    assertEquals(
        new Ran(
            1,
            "",
            "ci/changelog: BASE no-such-ref names no commit in this clone: fetch it, or give the"
                + " commit the change left, git merge-base origin/main HEAD.\n"),
        sh("ci/check", "rules", "no-such-ref"));
  }

  @Test
  void aRuleThatIsNoneIsRefusedNamingTheList() throws Exception {
    var ran = sh("ci/rules", "run", "spelling");

    assertEquals(2, ran.exit());
    assertEquals("ci/rules: 'spelling' is no rule; 'ci/rules list' names them.\n", ran.err());
  }

  @Test
  void theListSaysOfEveryRuleWhetherItIsOnAndItsSentence() throws Exception {
    assertEquals(
        new Ran(
            0,
            Stream.of(
                    "ellipsis (on)",
                    "lists (on)",
                    "changes (on)",
                    "comments (not yet on)",
                    "sleeps (not yet on)",
                    "tokens (not yet on)",
                    "components (not yet on)",
                    "function-lines (not yet on)",
                    "parameters (not yet on)",
                    "nesting (not yet on)",
                    "file-lines (not yet on)")
                .map(
                    rule ->
                        rule
                            + ": "
                            + SAY.getOrDefault(rule.substring(0, rule.indexOf(' ')), CHANGES))
                .collect(Collectors.joining("\n", "", "\n")),
            ""),
        sh("ci/rules", "list"));
  }

  @Test
  void everyRuleOnHoldsInThisRepositoryAndEveryRuleNotYetOnStillFindsSomething() throws Exception {
    var ran = here("check");

    assertEquals(new Ran(0, "", HELD), ran);
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

  private void notYetRulesFindOneEach() throws IOException {
    write(TEST, "@Test func waits() async throws { try await Task.sleep(for: .seconds(1)) }\n");
    write(
        KIT,
        "// a note\nlet ink = NSColor(white: 0, alpha: 1)\nlet alert = NSAlert()\n"
            + MANY
            + DEEP
            + function("long", 79, "")
            + lets(801, ""));
  }

  private static List<String> ciStep(String workflow) throws IOException {
    var step = CI_STEP.matcher(Files.readString(ROOT.resolve(workflow)));
    assertTrue(step.find(), workflow + " has no step 'The rules a script decides'");
    return List.of(step.group(1), step.group(2));
  }

  private Ran here(String... args) throws Exception {
    var command = new ArrayList<>(List.of("bash", ROOT.resolve("ci/rules").toString()));
    command.addAll(Arrays.asList(args));
    return run(command, ROOT, Map.of());
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
    var ran = run(command, repo, Map.of());
    assertEquals(0, ran.exit(), String.join(" ", command) + " failed: " + ran.err());
    return ran;
  }

  private Ran sh(String... args) throws Exception {
    var command = new ArrayList<>(List.of("bash"));
    command.addAll(Arrays.asList(args));
    return run(command, repo, Map.of());
  }

  private Ran bash(String line, Map<String, String> env) throws Exception {
    return run(List.of("bash", "-c", line), repo, env);
  }

  private Ran run(List<String> command, Path directory, Map<String, String> env) throws Exception {
    var builder = new ProcessBuilder(command).directory(directory.toFile());
    builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    builder.environment().remove("GITHUB_HEAD_REF");
    builder.environment().putAll(env);
    var ran = Child.run(builder);
    return new Ran(ran.exit(), ran.stdout(), ran.stderr());
  }
}
