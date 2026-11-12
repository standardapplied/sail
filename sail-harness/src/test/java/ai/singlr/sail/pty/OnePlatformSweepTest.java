/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.ModuleClasses;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.StringEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * P1, one path, swept: of every class in {@code ai.singlr.sail.pty}, in the core module and in this
 * one, only {@link Native} and its nested classes know which system they run on; every other class
 * reads the values {@code Native} selected. Read from the compiled classes, a class whose constants
 * name {@code os.name}, or probe {@code /proc/self}, branches on the system itself.
 */
class OnePlatformSweepTest {

  private static final String PTY = "ai/singlr/sail/pty/";
  private static final String NATIVE = PTY + "Native";

  @Test
  void noPtyClassButNativeReadsWhichSystemItRunsOn() throws Exception {
    var classes = new ArrayList<byte[]>();
    classes.addAll(ModuleClasses.of(Pty.class));
    classes.addAll(ModuleClasses.of(PtySessionHost.class));
    var pty = new TreeSet<String>();
    var readers = new TreeSet<String>();
    for (var bytes : classes) {
      var name = ClassFile.of().parse(bytes).thisClass().asInternalName();
      if (name.startsWith(PTY)) {
        pty.add(name);
      }
      if (asksWhichSystem(bytes)) {
        readers.add(name);
      }
    }

    assertTrue(pty.contains(PTY + "PtySessionHost"), "both modules' pty classes were read: " + pty);
    assertEquals(Set.of(), readers);
  }

  @Test
  void theSweepSeesTheSystemAskedEitherWayButNotByNative() throws IOException {
    assertTrue(asksWhichSystem(bytesOf(AsksTheName.class)), "an os.name read is seen");
    assertTrue(asksWhichSystem(bytesOf(ProbesProc.class)), "a /proc/self probe is seen");
    assertFalse(asksWhichSystem(bytesOf(Native.class)), "Native is where it is asked");
  }

  private static byte[] bytesOf(Class<?> type) throws IOException {
    var name = type.getName().substring(type.getPackageName().length() + 1);
    try (var bytes = type.getResourceAsStream(name + ".class")) {
      return bytes.readAllBytes();
    }
  }

  /** What no pty class but {@link Native} may do: ask the system its name. */
  private static final class AsksTheName {
    boolean onMac() {
      return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }
  }

  /** Nor tell the systems apart by what {@code /proc} holds. */
  private static final class ProbesProc {
    boolean onLinux() {
      return Files.exists(Path.of("/proc/self/exe"));
    }
  }

  private static boolean asksTheSystem(String constant) {
    return constant.equals("os.name") || constant.startsWith("/proc/self");
  }

  /**
   * Whether the class {@code bytes} holds is a pty class outside {@link Native} asking the system.
   */
  private static boolean asksWhichSystem(byte[] bytes) {
    var model = ClassFile.of().parse(bytes);
    var name = model.thisClass().asInternalName();
    if (!name.startsWith(PTY) || name.equals(NATIVE) || name.startsWith(NATIVE + "$")) {
      return false;
    }
    for (var entry : model.constantPool()) {
      if (entry instanceof StringEntry string && asksTheSystem(string.stringValue())) {
        return true;
      }
    }
    return false;
  }
}
