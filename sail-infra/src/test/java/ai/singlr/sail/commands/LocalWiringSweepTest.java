/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.ModuleClasses;
import ai.singlr.sail.pty.Account;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * L3, by construction: the classes of the local wiring ({@link LocalPtyHost}, {@link
 * LocalPtyAccess} and the {@link Account} they read) name no store, no control plane, no sync, no
 * Incus and no network type: not as a type, not as the owner of a member they call or read, not as
 * a class constant. {@code LocalPtyHostProcessTest} proves the same of a running host.
 */
class LocalWiringSweepTest {

  private static final String LOCAL = "ai/singlr/sail/commands/LocalPty";
  private static final String ACCOUNT = "ai/singlr/sail/pty/Account";

  private static final List<String> UNREACHABLE =
      List.of(
          "ai/singlr/sail/store/",
          "ai/singlr/sail/api/",
          "ai/singlr/sail/sync/",
          "ai/singlr/sail/engine/ContainerExec",
          "ai/singlr/sail/engine/Incus",
          "ai/singlr/sail/engine/ShellExec",
          "ai/singlr/sail/commands/PtyHostAccess",
          "ai/singlr/sail/commands/PtyHostEvents",
          "ai/singlr/sail/commands/SystemdStop",
          "java/net/Socket",
          "java/net/ServerSocket",
          "java/net/Inet",
          "java/net/URL",
          "java/net/http/");

  @Test
  void theLocalWiringNamesNoStoreIncusOrNetworkType() throws Exception {
    var classes = new ArrayList<byte[]>();
    classes.addAll(ModuleClasses.of(LocalPtyHost.class));
    classes.addAll(ModuleClasses.of(Account.class));
    var swept = new TreeSet<String>();
    var reached = new TreeMap<String, Set<String>>();
    for (var bytes : classes) {
      var name = ClassFile.of().parse(bytes).thisClass().asInternalName();
      if (name.startsWith(LOCAL) || name.equals(ACCOUNT)) {
        swept.add(name);
        var unreachable = unreachableNamedBy(bytes);
        if (!unreachable.isEmpty()) {
          reached.put(name, unreachable);
        }
      }
    }

    assertTrue(
        swept.containsAll(Set.of(LOCAL + "Host", LOCAL + "Access", ACCOUNT)), swept.toString());
    assertEquals(new TreeMap<>(), reached);
  }

  @Test
  void theSweepSeesAStoreOrASocketNamed() throws IOException {
    assertEquals(
        Set.of("ai/singlr/sail/store/SpecStore"), unreachableNamedBy(bytesOf(Reads.class)));
    assertEquals(Set.of("java/net/Socket"), unreachableNamedBy(bytesOf(Dials.class)));
  }

  private static byte[] bytesOf(Class<?> type) throws IOException {
    var name = type.getName().substring(type.getPackageName().length() + 1);
    try (var bytes = type.getResourceAsStream(name + ".class")) {
      return bytes.readAllBytes();
    }
  }

  /** What no local class may name: a store. */
  private static final class Reads {
    Class<?> store() {
      return SpecStore.class;
    }
  }

  /** Nor the network. */
  private static final class Dials {
    Object dial() throws IOException {
      return new Socket();
    }
  }

  private static Set<String> unreachableNamedBy(byte[] bytes) {
    var named = new TreeSet<String>();
    for (var entry : ClassFile.of().parse(bytes).constantPool()) {
      if (entry instanceof ClassEntry type) {
        var name = type.asInternalName();
        if (UNREACHABLE.stream().anyMatch(name::startsWith)) {
          named.add(name);
        }
      }
    }
    return named;
  }
}
