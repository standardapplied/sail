/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class LocalesTest {

  @Test
  void theCLocaleIsEverywhereAndAnInventedOneNowhere() {
    assertTrue(Locales.installed("C"));
    assertTrue(Locales.installed("POSIX"));
    assertFalse(Locales.installed("xx_XX.NOWHERE"));
  }

  /** glibc builds {@code C.UTF-8} in, from 2.35. */
  @Test
  @EnabledOnOs(OS.LINUX)
  void linuxHasCUtf8() {
    assertTrue(Locales.installed("C.UTF-8"));
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void macOsHasEnUsUtf8() {
    assertTrue(Locales.installed("en_US.UTF-8"));
  }
}
