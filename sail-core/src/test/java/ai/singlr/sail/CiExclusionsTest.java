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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ci/exclusions}, which keeps the PMD exclusions file a subset of its base's, over a real
 * repository: a base commit listing two classes and a change on top of it.
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
