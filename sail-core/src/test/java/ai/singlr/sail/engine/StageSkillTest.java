/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StageSkillTest {

  private static final String HASH = "a".repeat(64);
  private static final StageSkill.File MANIFEST = file(StageSkill.MANIFEST, 10);

  private static StageSkill.File file(String path, long size) {
    return new StageSkill.File(path, HASH, size, 0644);
  }

  private static StageSkill skill(String skillMd) {
    return StageSkill.of("mine", skillMd, List.of(MANIFEST));
  }

  private static final String HEAD = "---\nname: mine\ndescription: d\n---\n";

  private static String parseRefusal(String skillMd) {
    return assertThrows(IllegalArgumentException.class, () -> skill(skillMd)).getMessage();
  }

  private static String refusal(String name, String body, List<StageSkill.File> files) {
    return assertThrows(IllegalArgumentException.class, () -> new StageSkill(name, body, files))
        .getMessage();
  }

  @Test
  void theBodyIsWhatFollowsTheFrontMatterStripped() {
    var skill = skill("---\nname: mine\ndescription: d\n---\n\n  Judge it my way.\n\nTwice.\n\n");

    assertEquals("Judge it my way.\n\nTwice.", skill.body());
  }

  @Test
  void aFileWithoutFrontMatterIsRefused() {
    var refused =
        "Skill 'mine' has no front matter: start SKILL.md with a line '---', then its name and"
            + " description, then '---'.";

    assertEquals(refused, parseRefusal("\n# Mine\n\nJudge it my way.\n"));
    assertEquals(refused, parseRefusal("----\nname: mine\n---\nBody.\n"));
    assertEquals(refused, parseRefusal("Body.\n---\nMore.\n"));
    assertEquals(refused, parseRefusal(""));
    assertEquals(refused, parseRefusal("\uFEFFJudge it my way."));
  }

  @Test
  void aLaterFenceInTheBodyStaysInTheBody() {
    assertEquals("One.\n---\nTwo.", skill(HEAD + "One.\n---\nTwo.\n").body());
  }

  @Test
  void windowsLineEndingsReadAsUnixOnes() {
    var unix = HEAD + "\nJudge it my way.\n\nTwice.\n";

    assertEquals(skill(unix), skill(unix.replace("\n", "\r\n")));
  }

  @Test
  void aByteOrderMarkIsDropped() {
    var plain = HEAD + "Judge it my way.\n";

    assertEquals(skill(plain), skill("\uFEFF" + plain));
  }

  @Test
  void frontMatterAloneIsRefusedAsBlank() {
    assertEquals(
        "Skill 'mine' has no instructions: its SKILL.md body is blank.", parseRefusal(HEAD));
  }

  @Test
  void frontMatterNeverClosedIsRefused() {
    assertEquals(
        "Skill 'mine' has front matter that is never closed: end it with a line that is exactly"
            + " ---.",
        parseRefusal("---\nname: mine\nBody.\n"));
  }

  @Test
  void frontMatterThatIsNotYamlIsRefusedWithTheParsersProblemAndItsLine() {
    assertEquals(
        "Skill 'mine' has front matter that is not YAML: mapping values are not allowed here at"
            + " line 2 of the front matter.",
        parseRefusal("---\nname: mine\ndescription: a: b: c\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has front matter that is not YAML: found duplicate key name at line 2 of"
            + " the front matter.",
        parseRefusal("---\nname: mine\nname: mine\ndescription: d\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has front matter that is not YAML: expected ',' or ']', but got <stream"
            + " end> at line 1 of the front matter.",
        parseRefusal("---\nname: [a\n---\nBody.\n"));
  }

  @Test
  void theFrontMatterMustNameTheFoldersSkill() {
    assertEquals(
        "Skill 'mine' has no name in its front matter: the Agent Skills standard requires one.",
        parseRefusal("---\ndescription: d\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has front matter that is not a map of fields: write each as 'field: value'.",
        parseRefusal("---\n- name\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has no name in its front matter: the Agent Skills standard requires one.",
        parseRefusal("---\n# a comment\n\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' names itself 'yours' in its front matter: a skill's name is its folder's.",
        parseRefusal("---\nname: yours\ndescription: d\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has a name that is not text: quote it.",
        parseRefusal("---\nname: 12\ndescription: d\n---\nBody.\n"));
  }

  @Test
  void theDescriptionIsRequiredAndBounded() {
    assertEquals(
        "Skill 'mine' has no description in its front matter: the Agent Skills standard requires"
            + " one.",
        parseRefusal("---\nname: mine\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has a blank description: say what it does and when to use it.",
        parseRefusal("---\nname: mine\ndescription: \"\"\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has a description that is not text: quote it.",
        parseRefusal("---\nname: mine\ndescription: [a]\n---\nBody.\n"));
    var atTheLimit = "😀".repeat(StageSkill.MAX_DESCRIPTION);
    assertEquals(
        "Body.", skill("---\nname: mine\ndescription: " + atTheLimit + "\n---\nBody.\n").body());
    assertEquals(
        "Skill 'mine' has a description of 1025 characters; the limit is 1024.",
        parseRefusal("---\nname: mine\ndescription: " + atTheLimit + "x\n---\nBody.\n"));
  }

  @Test
  void theOptionalFieldsAreCheckedWhenPresent() {
    var withAll =
        "---\nname: mine\ndescription: d\nlicense: MIT\ncompatibility: "
            + "c".repeat(StageSkill.MAX_COMPATIBILITY)
            + "\nmetadata:\n  owner: uday\n  version: \"2\"\nallowed-tools: Bash(git:*) Read\n"
            + "argument-hint: \"[file]\"\n---\nBody.\n";
    assertEquals("Body.", skill(withAll).body());
    assertEquals(
        "Skill 'mine' has a compatibility of 501 characters; the limit is 500.",
        parseRefusal(
            "---\nname: mine\ndescription: d\ncompatibility: "
                + "c".repeat(StageSkill.MAX_COMPATIBILITY + 1)
                + "\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has metadata.version that is not text: quote it.",
        parseRefusal(
            "---\nname: mine\ndescription: d\nmetadata: {owner: uday, version: 2}\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has metadata.1 that is not text: quote it.",
        parseRefusal("---\nname: mine\ndescription: d\nmetadata: {1: one}\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has metadata that is not a map of text to text.",
        parseRefusal("---\nname: mine\ndescription: d\nmetadata: [a]\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has a license that is not text: quote it.",
        parseRefusal("---\nname: mine\ndescription: d\nlicense: 2\n---\nBody.\n"));
    assertEquals(
        "Skill 'mine' has a allowed-tools that is not text: quote it.",
        parseRefusal("---\nname: mine\ndescription: d\nallowed-tools: [Read]\n---\nBody.\n"));
  }

  @Test
  void aLongBodyIsAdvisedAgainstNotRefused() {
    var atTheLimit = skill(HEAD + "line\n".repeat(StageSkill.ADVISED_LINES));
    var over = skill(HEAD + "line\n".repeat(StageSkill.ADVISED_LINES + 1));

    assertEquals(Optional.empty(), atTheLimit.advice());
    assertEquals(
        Optional.of(
            "Skill 'mine' has a SKILL.md body of 501 lines; the Agent Skills standard advises"
                + " under 500, with detail moved to files under references/."),
        over.advice());
  }

  @Test
  void aNameFollowsTheStandardsRule() {
    assertTrue(StageSkill.isName("a"));
    assertTrue(StageSkill.isName("0-review-2"));
    assertTrue(StageSkill.isName("pdf-processing"));
    assertTrue(StageSkill.isName("a".repeat(64)));
    assertFalse(StageSkill.isName("a".repeat(65)));
    assertFalse(StageSkill.isName("-review"));
    assertFalse(StageSkill.isName("-pdf"));
    assertFalse(StageSkill.isName("pdf-"));
    assertFalse(StageSkill.isName("pdf--processing"));
    assertFalse(StageSkill.isName("PDF-Processing"));
    assertFalse(StageSkill.isName("Review"));
    assertFalse(StageSkill.isName("my skill"));
    assertFalse(StageSkill.isName("my.skill"));
    assertFalse(StageSkill.isName("a/b"));
    assertFalse(StageSkill.isName(""));
    assertFalse(StageSkill.isName(null));
  }

  @Test
  void aBadNameIsRefusedNamingItAndTheRule() {
    var rule =
        "' must be 1 to 64 lowercase letters, digits and hyphens, with no hyphen first, last or"
            + " next to another.";
    assertEquals("Skill name 'Bad Name" + rule, refusal("Bad Name", "Body.", List.of(MANIFEST)));
    assertEquals("Skill name 'lint--fix" + rule, refusal("lint--fix", "Body.", List.of(MANIFEST)));
    assertEquals("Skill name 'null" + rule, refusal(null, "Body.", List.of(MANIFEST)));
  }

  @Test
  void aBlankBodyIsRefused() {
    var blank = "Skill 'mine' has no instructions: its SKILL.md body is blank.";

    assertEquals(blank, refusal("mine", " \n\t", List.of(MANIFEST)));
    assertEquals(blank, refusal("mine", null, List.of(MANIFEST)));
  }

  @Test
  void aBodyIsBoundedInCodePointsNotChars() {
    var atTheLimit = "😀".repeat(PromptConversation.MAX_CODE_POINTS);

    assertEquals(atTheLimit, new StageSkill("mine", atTheLimit, List.of(MANIFEST)).body());
    assertEquals(
        "Skill 'mine' has a body of 32001 code points; the limit is 32000.",
        refusal("mine", atTheLimit + "x", List.of(MANIFEST)));
  }

  @Test
  void aFolderHoldsAtMostThirtyTwoFiles() {
    var files = new ArrayList<>(List.of(MANIFEST));
    IntStream.range(1, StageSkill.MAX_FILES).forEach(i -> files.add(file("ref/" + i + ".md", 1)));

    assertEquals(32, new StageSkill("mine", "Body.", files).files().size());

    files.add(file("one-too-many.md", 1));
    assertEquals("Skill 'mine' has 33 files; the limit is 32.", refusal("mine", "Body.", files));
  }

  @Test
  void aFolderHoldsAtMostOneMebibyte() {
    var atTheLimit = List.of(MANIFEST, file("big.bin", StageSkill.MAX_BYTES - MANIFEST.size()));

    assertEquals(2, new StageSkill("mine", "Body.", atTheLimit).files().size());
    assertEquals(
        "Skill 'mine' is 1048577 bytes; the limit is 1048576.",
        refusal(
            "mine",
            "Body.",
            List.of(MANIFEST, file("big.bin", StageSkill.MAX_BYTES - MANIFEST.size() + 1))));
  }

  @Test
  void filesAreHeldInPathOrder() {
    var skill =
        new StageSkill(
            "mine", "Body.", List.of(file("scripts/b.sh", 1), MANIFEST, file("a.md", 1)));

    assertEquals(
        List.of("SKILL.md", "a.md", "scripts/b.sh"),
        skill.files().stream().map(StageSkill.File::path).toList());
  }

  @Test
  void aFolderWithoutItsManifestIsRefused() {
    assertEquals(
        "Skill 'mine' has no SKILL.md.", refusal("mine", "Body.", List.of(file("a.md", 1))));
    assertEquals(
        "Skill 'mine' has no SKILL.md.",
        refusal("mine", "Body.", List.of(file("docs/SKILL.md", 1))));
    assertThrows(NullPointerException.class, () -> new StageSkill("mine", "Body.", null));
  }

  @Test
  void aPathHeldTwiceIsRefused() {
    assertEquals(
        "Skill 'mine' holds the path 'a.md' twice.",
        refusal("mine", "Body.", List.of(MANIFEST, file("a.md", 1), file("a.md", 2))));
  }

  @Test
  void aPathThatLeavesTheFolderIsRefused() {
    for (var path :
        List.of(
            "/etc/passwd", "", "a//b", "a/", "./a", "a/./b", "../a", "a/..", "a\nb", "a\u0000")) {
      assertEquals(
          "Skill 'mine' holds the path '" + path + "', which is not a path inside its folder.",
          refusal("mine", "Body.", List.of(MANIFEST, file(path, 1))),
          path);
    }
    assertEquals(
        "Skill 'mine' holds the path 'null', which is not a path inside its folder.",
        refusal("mine", "Body.", List.of(MANIFEST, file(null, 1))));
  }

  @Test
  void aPathWithDotsInsideASegmentIsKept() {
    var skill =
        new StageSkill("mine", "Body.", List.of(MANIFEST, file("..a/.b/c..", 1), file("x", 1)));

    assertEquals(3, skill.files().size());
  }

  @Test
  void theStampsNameIsRefusedAsAFileAtAnyDepth() {
    assertEquals(
        "Skill 'mine' holds the path '.sail-skill'; .sail-skill is the name sail stamps an"
            + " installed skill with.",
        refusal("mine", "Body.", List.of(MANIFEST, file(".sail-skill", 1))));
    assertEquals(
        "Skill 'mine' holds the path 'ref/.sail-skill'; .sail-skill is the name sail stamps an"
            + " installed skill with.",
        refusal("mine", "Body.", List.of(MANIFEST, file("ref/.sail-skill", 1))));
    assertEquals(
        2,
        new StageSkill("mine", "Body.", List.of(MANIFEST, file("x.sail-skill", 1))).files().size());
  }

  @Test
  void aProjectsSkillLivesInAFolderNamedForItUnderTheSkillsRoot() {
    assertEquals(".sail/skills/mine/", StageSkill.projectFolder("mine"));
  }
}
