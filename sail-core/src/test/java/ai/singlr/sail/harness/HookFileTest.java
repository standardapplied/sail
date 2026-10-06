/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class HookFileTest {

  private static HookFile.Group group(String event, SailHook... hooks) {
    return new HookFile.Group(event, null, List.of(hooks));
  }

  @Test
  void aFileMissingARequiredHookIsRefusedNamingIt() {
    var groups =
        List.of(
            group("SessionStart", SailHook.SESSION_STARTED, SailHook.SESSION_REPORT),
            group("PreToolUse", SailHook.TOOL_STARTED),
            group("PostToolUse", SailHook.TOOL_FINISHED),
            group("Stop", SailHook.STOP_GATE));

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new HookFile("/home/dev/.x/hooks.json", new LinkedHashMap<>(), groups));

    assertEquals(
        "Hook file /home/dev/.x/hooks.json names no event that runs ROOM_RELAY, which every"
            + " harness must run.",
        ex.getMessage());
  }

  @Test
  void aGroupThatRunsNothingIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> group("Stop"));
  }

  @Test
  void settingsAndGroupsKeepTheirOrder() {
    var settings = new LinkedHashMap<String, Object>();
    settings.put("b", 1);
    settings.put("a", 2);
    var groups =
        List.of(
            group("SessionStart", SailHook.SESSION_STARTED, SailHook.SESSION_REPORT),
            group("PreToolUse", SailHook.TOOL_STARTED),
            group("PostToolUse", SailHook.TOOL_FINISHED, SailHook.ROOM_RELAY),
            group("Stop", SailHook.STOP_GATE));

    var file = new HookFile("/home/dev/.x/hooks.json", settings, groups);

    assertEquals(List.of("b", "a"), List.copyOf(file.settings().keySet()));
    assertEquals(groups, file.groups());
  }
}
