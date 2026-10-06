/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class HookFileTest {

  private static final String PATH = "/home/dev/.x/hooks.json";

  private static HookFile.Group group(String event, SailHook... hooks) {
    return new HookFile.Group(event, null, List.of(hooks));
  }

  private static List<HookFile.Group> everyRequiredHook() {
    return List.of(
        group("SessionStart", SailHook.SESSION_STARTED, SailHook.SESSION_REPORT),
        group("PreToolUse", SailHook.TOOL_STARTED),
        group("PostToolUse", SailHook.TOOL_FINISHED, SailHook.ROOM_RELAY),
        group("Stop", SailHook.STOP_GATE));
  }

  @Test
  void theRequiredHooksAreExactlyTheSixTheLoopCannotRunWithout() {
    assertEquals(
        EnumSet.of(
            SailHook.SESSION_STARTED,
            SailHook.SESSION_REPORT,
            SailHook.TOOL_STARTED,
            SailHook.TOOL_FINISHED,
            SailHook.ROOM_RELAY,
            SailHook.STOP_GATE),
        SailHook.required());
    assertThrows(
        UnsupportedOperationException.class, () -> SailHook.required().add(SailHook.SESSION_ENDED));
  }

  @ParameterizedTest
  @EnumSource(
      value = SailHook.class,
      names = {
        "SESSION_STARTED",
        "SESSION_REPORT",
        "TOOL_STARTED",
        "TOOL_FINISHED",
        "ROOM_RELAY",
        "STOP_GATE"
      })
  void aFileMissingARequiredHookIsRefusedNamingIt(SailHook missing) {
    var groups =
        List.of(
            new HookFile.Group(
                "Anything",
                null,
                SailHook.required().stream().filter(hook -> hook != missing).toList()));

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new HookFile(PATH, new LinkedHashMap<>(), groups));

    assertEquals(
        "Hook file "
            + PATH
            + " names no event that runs "
            + missing
            + ", which every harness must run.",
        ex.getMessage());
  }

  @Test
  void aFileNeedsNeitherOfTheTwoHooksAHarnessMayLack() {
    assertDoesNotThrow(
        () -> new HookFile(PATH, new LinkedHashMap<>(), everyRequiredHook()),
        "no batch-resolved and no session-ended hook is asked of a harness");
  }

  @Test
  void aGroupThatRunsNothingIsRefusedNamingItsEvent() {
    var ex = assertThrows(IllegalArgumentException.class, () -> group("Stop"));

    assertEquals("Hook group for Stop runs nothing.", ex.getMessage());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  "})
  void aGroupWithNoEventIsRefused(String event) {
    assertThrows(IllegalArgumentException.class, () -> group(event, SailHook.STOP_GATE));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "  ",
        "hooks.json",
        "relative/hooks.json",
        "/hooks.json",
        "/dir/",
        "//hooks.json",
        "/dir//hooks.json"
      })
  void aFileWhosePathIsNotAbsoluteInsideADirectoryIsRefused(String path) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HookFile(path, new LinkedHashMap<>(), everyRequiredHook()));
  }

  @Test
  void aFileKnowsTheDirectoryItLivesIn() {
    var file =
        new HookFile("/home/dev/.x/sub/hooks.json", new LinkedHashMap<>(), everyRequiredHook());

    assertEquals("/home/dev/.x/sub", file.directory());
  }

  @Test
  void settingsAndGroupsKeepTheirOrder() {
    var settings = new LinkedHashMap<String, Object>();
    settings.put("b", 1);
    settings.put("a", 2);
    var groups = everyRequiredHook();

    var file = new HookFile(PATH, settings, groups);

    assertEquals(List.of("b", "a"), List.copyOf(file.settings().keySet()));
    assertEquals(groups, file.groups());
  }

  @Test
  void whatACallerDoesToItsMapAndListsAfterwardsChangesNothing() {
    var settings = new LinkedHashMap<String, Object>();
    settings.put("a", 1);
    var hooks = new ArrayList<>(List.of(SailHook.STOP_GATE));
    var stop = new HookFile.Group("Stop", null, hooks);
    var groups = new ArrayList<>(everyRequiredHook());
    groups.add(stop);

    var file = new HookFile(PATH, settings, groups);
    settings.put("b", 2);
    hooks.add(SailHook.SESSION_ENDED);
    groups.clear();

    assertEquals(List.of("a"), List.copyOf(file.settings().keySet()));
    assertEquals(5, file.groups().size());
    assertEquals(List.of(SailHook.STOP_GATE), stop.hooks());
    assertThrows(UnsupportedOperationException.class, () -> file.settings().put("c", 3));
    assertThrows(UnsupportedOperationException.class, () -> file.groups().add(stop));
    assertThrows(UnsupportedOperationException.class, () -> stop.hooks().add(SailHook.STOP_GATE));
  }
}
