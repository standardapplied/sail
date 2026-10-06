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
  void aFileWithoutFrontMatterIsItsBodyWhole() {
    assertEquals("# Mine\n\nJudge it my way.", skill("\n# Mine\n\nJudge it my way.\n").body());
  }

  @Test
  void aFirstLineOfFourDashesIsNotFrontMatter() {
    assertEquals("----\nname: mine\n---\nBody.", skill("----\nname: mine\n---\nBody.\n").body());
  }

  @Test
  void aFenceThatIsNotTheFirstLineOpensNoFrontMatter() {
    assertEquals("Body.\n---\nMore.", skill("Body.\n---\nMore.\n").body());
  }

  @Test
  void aLaterFenceInTheBodyStaysInTheBody() {
    assertEquals("One.\n---\nTwo.", skill("---\nname: mine\n---\nOne.\n---\nTwo.\n").body());
  }

  @Test
  void windowsLineEndingsReadAsUnixOnes() {
    var unix = "---\nname: mine\n---\n\nJudge it my way.\n\nTwice.\n";

    assertEquals(skill(unix), skill(unix.replace("\n", "\r\n")));
  }

  @Test
  void aByteOrderMarkIsDropped() {
    var plain = "---\nname: mine\n---\nJudge it my way.\n";

    assertEquals(skill(plain), skill("\uFEFF" + plain));
    assertEquals("Judge it my way.", skill("\uFEFFJudge it my way.").body());
  }

  @Test
  void frontMatterAloneIsRefusedAsBlank() {
    var refused =
        assertThrows(IllegalArgumentException.class, () -> skill("---\nname: mine\n---\n"));

    assertEquals(
        "Skill 'mine' has no instructions: its SKILL.md body is blank.", refused.getMessage());
  }

  @Test
  void anEmptyFileIsRefusedAsBlank() {
    var refused = assertThrows(IllegalArgumentException.class, () -> skill(""));

    assertEquals(
        "Skill 'mine' has no instructions: its SKILL.md body is blank.", refused.getMessage());
  }

  @Test
  void frontMatterNeverClosedIsRefused() {
    var refused =
        assertThrows(IllegalArgumentException.class, () -> skill("---\nname: mine\nBody.\n"));

    assertEquals(
        "Skill 'mine' has front matter that is never closed: end it with a line that is exactly"
            + " ---.",
        refused.getMessage());
  }

  @Test
  void aNameIsAShortLowercaseSlug() {
    assertTrue(StageSkill.isName("a"));
    assertTrue(StageSkill.isName("0-review-2"));
    assertTrue(StageSkill.isName("a".repeat(64)));
    assertFalse(StageSkill.isName("a".repeat(65)));
    assertFalse(StageSkill.isName("-review"));
    assertFalse(StageSkill.isName("Review"));
    assertFalse(StageSkill.isName("my skill"));
    assertFalse(StageSkill.isName("my.skill"));
    assertFalse(StageSkill.isName("a/b"));
    assertFalse(StageSkill.isName(""));
    assertFalse(StageSkill.isName(null));
  }

  @Test
  void aBadNameIsRefusedNamingItAndThePattern() {
    assertEquals(
        "Skill name 'Bad Name' must match [a-z0-9][a-z0-9-]{0,63}.",
        refusal("Bad Name", "Body.", List.of(MANIFEST)));
    assertEquals(
        "Skill name 'null' must match [a-z0-9][a-z0-9-]{0,63}.",
        refusal(null, "Body.", List.of(MANIFEST)));
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
  void theBlockOfASkillWithOnlyItsManifestNamesNoFolder() {
    var skill = new StageSkill("mine", "Judge it my way.", List.of(MANIFEST));

    assertEquals(
        "## How to do this work (skill: mine)\n\nJudge it my way.",
        skill.block("~/.claude/skills/mine/"));
  }

  @Test
  void theBlockOfASkillWithOtherFilesSaysWhereTheyAre() {
    var skill =
        new StageSkill("mine", "Judge it my way.", List.of(MANIFEST, file("scripts/check.sh", 1)));

    assertEquals(
        """
        ## How to do this work (skill: mine)

        Judge it my way.

        The skill's other files are in ~/.agents/skills/mine/.""",
        skill.block("~/.agents/skills/mine/"));
  }
}
