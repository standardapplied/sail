/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.engine.Child;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code ci/exclusions}, which keeps the PMD exclusions file a subset of its base's and lets no
 * source silence PMD, over a real repository: a base commit listing two classes and a change on top
 * of it. Its Java fixtures spell the annotation and the marker through placeholders, so this file
 * is not itself a line the check refuses.
 */
class CiExclusionsTest {

  private static final Path SCRIPT = Path.of("..", "ci", "exclusions").toAbsolutePath();
  private static final String FILE = "config/quality/pmd-exclusions.properties";
  private static final String BASE_LIST =
      """
      # the classes the gate found
      a.Big=GodClass,TooManyMethods
      a.Wide=CouplingBetweenObjects
      """;

  @TempDir Path repo;
  private String base;

  private static final String JAVA = "src/main/java/a/Quiet.java";

  private record Ran(int exit, String out, String err) {}

  @BeforeEach
  void aBaseListingTwoClasses() throws Exception {
    sh("git init -q");
    base = commitList(BASE_LIST);
  }

  @Test
  void aListThatOnlyShrinksPasses() throws Exception {
    commitList("# the classes the gate found\na.Big=TooManyMethods\n");

    assertEquals(
        new Ran(
            0, "ci/exclusions: every class=rule pair of " + FILE + " is in " + base + "'s.\n", ""),
        check(base));
  }

  @Test
  void anAddedClassFailsNamingItsPair() throws Exception {
    commitList(BASE_LIST + "a.New=TooManyMethods\n");

    assertEquals(refusedAsAdded("a.New=TooManyMethods"), check(base));
  }

  @Test
  void aRuleAddedToAnEntryFailsNamingItsPair() throws Exception {
    commitList(
        BASE_LIST.replace(
            "a.Wide=CouplingBetweenObjects", "a.Wide=CouplingBetweenObjects,GodClass"));

    assertEquals(refusedAsAdded("a.Wide=GodClass"), check(base));
  }

  @Test
  void aClassNamedOnTwoLinesFailsSinceTheLastLineWouldReplaceTheFirst() throws Exception {
    commitList(BASE_LIST + "a.Big=GodClass\n");

    assertEquals(
        new Ran(
            1,
            "",
            "ci/exclusions: "
                + FILE
                + " names a class on more than one line, and the last line replaces the others:\n"
                + "  a.Big\n"
                + "Keep one line per class, listing only the rules the base branch lists for it.\n"),
        check(base));
  }

  @ParameterizedTest(name = "{index}: refused")
  @ValueSource(
      strings = {
        "a.New:TooManyMethods",
        "a.New TooManyMethods",
        " a.New=TooManyMethods",
        "a.New =TooManyMethods",
        "a.Wide=CouplingBetweenObjects,\\\n  GodClass",
        "a.N\\u0065w=TooManyMethods",
        "!a.New=TooManyMethods",
        "# a note\ra.New=TooManyMethods",
        "a.Big=GodClass\r",
      })
  void aLineThatPropertiesCouldReadOtherwiseFailsNamingIt(String stray) throws Exception {
    commitList(BASE_LIST + stray + "\n");

    var lines = stray.split("\n");
    assertEquals(
        new Ran(
            1,
            "",
            "ci/exclusions: "
                + FILE
                + " holds lines that are not a comment, a blank line or one Class=Rule[,Rule]"
                + " entry, which java.util.Properties, as PMD reads the file, could read as"
                + " entries this check does not see:\n"
                + IntStream.range(0, lines.length)
                    .mapToObj(index -> "  " + (4 + index) + ":" + lines[index] + "\n")
                    .collect(Collectors.joining())
                + "Write each entry as fully.qualified.Class=Rule1,Rule2 from the line's first"
                + " column, with no space, escape or continuation, and each comment after a # in"
                + " the first column.\n"),
        check(base));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @ValueSource(
      strings = {
        "int quiet; // NO_PMD",
        "@SW(\"PMD.TooManyMethods\")",
        "@SW(\"PMD\")",
        "@SW({\"unchecked\", \"PMD.GodClass\"})",
        "@SW(\"all\")",
        "@SW(\"P\" + \"MD\")",
        "@SW(Names.QUIET)",
        "@SW(value = \"unchecked\")",
        "@java.lang.SW(\"unchecked\")",
        "@SW({",
        "@SW(\"\\U0050MD\")",
        "/* NO\\U0050MD */",
      })
  void aLineThatCouldSilencePmdFailsNamingIt(String quiet) throws Exception {
    var line = java(quiet);
    commitJava("package a;\n\n" + line + "\nclass Quiet {}\n");

    assertEquals(
        new Ran(
            1,
            "",
            "ci/exclusions: these Java lines could silence PMD, which honours NO"
                + "PMD in a comment and a Suppress"
                + "Warnings naming PMD, PMD.<rule> or all, however"
                + " spelled:\n  "
                + JAVA
                + ":3:"
                + line
                + "\nFix the code instead: name only javac's warnings, in one-line literal form such"
                + " as @Suppress"
                + "Warnings(\"unchecked\"), and spell no letter as a unicode escape.\n"),
        check(base));
  }

  @Test
  void javacWarningsNamedOnOneLinePass() throws Exception {
    commitJava(
        java(
            """
            package a;

            @SW("unchecked")
            class Quiet {
              @SW({"unchecked", "rawtypes"})
              Object raw() {
                return "\\\\u0041 is no escape, \\U001b is not a letter";
              }
            }
            """));

    assertEquals(
        new Ran(
            0, "ci/exclusions: every class=rule pair of " + FILE + " is in " + base + "'s.\n", ""),
        check(base));
  }

  @Test
  void aBaseWithoutTheFileIsTheChangeThatAddsIt() throws Exception {
    sh(
        "git rm -q "
            + FILE
            + " && git -c user.name=CI -c user.email=ci@example.com commit -q -m gone");
    var without = sh("git rev-parse HEAD").out().strip();
    commitList(BASE_LIST + "a.New=TooManyMethods\n");

    assertEquals(
        new Ran(
            0, "ci/exclusions: " + without + " holds no " + FILE + "; this change adds it.\n", ""),
        check(without));
  }

  @Test
  void aBaseThatIsNoCommitFails() throws Exception {
    assertEquals(
        new Ran(
            2,
            "",
            "ci/exclusions: 'nowhere' is not a commit of this repository; name the base branch's"
                + " commit.\n"),
        check("nowhere"));
  }

  private Ran refusedAsAdded(String pair) {
    return new Ran(
        1,
        "",
        "ci/exclusions: "
            + FILE
            + " only shrinks, and these class=rule pairs are not in "
            + base
            + "'s:\n  "
            + pair
            + "\nFix the class instead: no class is added to the list and no rule to an entry.\n");
  }

  private static String java(String text) {
    return text.replace("SW(", "Suppress" + "Warnings(")
        .replace("NO_PMD", "NO" + "PMD")
        .replace("\\U", "\\" + "u");
  }

  private void commitJava(String source) throws Exception {
    var file = repo.resolve(JAVA);
    Files.createDirectories(file.getParent());
    Files.writeString(file, source);
    sh("git add -A && git -c user.name=CI -c user.email=ci@example.com commit -q -m java");
  }

  private String commitList(String list) throws Exception {
    var file = repo.resolve(FILE);
    Files.createDirectories(file.getParent());
    Files.writeString(file, list);
    sh("git add -A && git -c user.name=CI -c user.email=ci@example.com commit -q -m list");
    return sh("git rev-parse HEAD").out().strip();
  }

  private Ran check(String from) throws Exception {
    return run(List.of("bash", SCRIPT.toString(), from));
  }

  private Ran sh(String script) throws Exception {
    var ran = run(List.of("bash", "-c", script));
    assertEquals(0, ran.exit(), script + " failed: " + ran.err());
    return ran;
  }

  private Ran run(List<String> command) throws Exception {
    var builder = new ProcessBuilder(command).directory(repo.toFile());
    builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    var ran = Child.run(builder);
    return new Ran(ran.exit(), ran.stdout(), ran.stderr());
  }
}
