/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.origin;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.targetOwner;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMembers;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.Main;
import ai.singlr.sail.common.Ids;
import ai.singlr.sail.engine.GuardrailChecker;
import ai.singlr.sail.engine.LiveChat;
import ai.singlr.sail.engine.LiveReply;
import ai.singlr.sail.engine.LiveStream;
import ai.singlr.sail.harness.Harnesses;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The one allowed way to do each thing, as build-failing rules over the main classes of every Sail
 * module. A spec that establishes or consolidates a pattern adds its rule here; each rule's {@code
 * because} names the allowed way. Packages such as {@code engine} and {@code pty} span modules, so
 * a module is told by where its classes were loaded from, never by package.
 */
class ArchitectureRulesTest {

  private static final String SAIL = "ai.singlr.sail";

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(SAIL);

  private static final Set<String> JDK_PACKAGES =
      ModuleFinder.ofSystem().findAll().stream()
          .flatMap(module -> module.descriptor().packages().stream())
          .collect(Collectors.toUnmodifiableSet());

  private static final DescribedPredicate<JavaClass> BELONG_TO_THE_JDK =
      describe("belong to the JDK", type -> JDK_PACKAGES.contains(type.getPackageName()));

  private static final DescribedPredicate<JavaClass> IN_SAIL_CORE = inModuleOf(Ids.class);
  private static final DescribedPredicate<JavaClass> IN_SAIL_HARNESS =
      inModuleOf(GuardrailChecker.class);
  private static final DescribedPredicate<JavaClass> IN_SAIL_INFRA = inModuleOf(Main.class);

  /** The room's live members, the one part of sail-infra that speaks Claude Code's own stream. */
  private static final DescribedPredicate<JavaClass> THE_ROOMS_LIVE_MEMBERS =
      describe(
          "are the room's live members",
          type ->
              type.getName().equals(SAIL + ".api.LiveMembers")
                  || type.getName().startsWith(SAIL + ".api.LiveMembers$"));

  /** Claude Code's own classes: its harness and the stream its chat and live members speak. */
  private static final DescribedPredicate<JavaClass> CLAUDE_CODES =
      describe(
          "are Claude Code's",
          type ->
              type.getName().startsWith(SAIL + ".harness.ClaudeCode")
                  || Stream.of(LiveStream.class, LiveChat.class, LiveReply.class)
                      .anyMatch(
                          own ->
                              type.getName().equals(own.getName())
                                  || type.getName().startsWith(own.getName() + "$")));

  private static final DescribedPredicate<JavaCodeUnit> A_CONSTRUCTOR_OR_STATIC_INITIALISER =
      describe(
          "a constructor or static initialiser",
          unit -> unit.isConstructor() || unit instanceof JavaStaticInitializer);

  @Test
  void sailCoreDependsOnNoOtherSailModule() {
    noClasses()
        .that(IN_SAIL_CORE)
        .should()
        .dependOnClassesThat(IN_SAIL_HARNESS.or(IN_SAIL_INFRA))
        .because(
            "sail-core is the base every module builds on: code both other modules need moves"
                + " down into sail-core")
        .check(MAIN);
  }

  @Test
  void sailHarnessDependsOnNoSailInfra() {
    noClasses()
        .that(IN_SAIL_HARNESS)
        .should()
        .dependOnClassesThat(IN_SAIL_INFRA)
        .because(
            "sail-harness builds on sail-core only: sail-infra wires the harness, never the"
                + " reverse")
        .check(MAIN);
  }

  @Test
  void mainCodeDependsOnTheJdkPicocliAndSnakeYamlEngineOnly() {
    classes()
        .should()
        .onlyDependOnClassesThat(
            resideInAnyPackage(SAIL + "..", "picocli..", "org.snakeyaml.engine..")
                .or(BELONG_TO_THE_JDK))
        .because(
            "Sail ships two runtime libraries, picocli and SnakeYAML Engine, and no more: what"
                + " else it needs it writes on the JDK")
        .check(MAIN);
  }

  @Test
  void onlySailInfraDependsOnPicocli() {
    noClasses()
        .that(not(IN_SAIL_INFRA))
        .should()
        .dependOnClassesThat()
        .resideInAPackage("picocli..")
        .because(
            "the command line is sail-infra's: sail-core and sail-harness take parsed values,"
                + " never a picocli type")
        .check(MAIN);
  }

  @Test
  void theSystemClockIsOnlyADefault() {
    noClasses()
        .should()
        .accessTargetWhere(
            targetOwner(type(Clock.class))
                .and(target(nameStartingWith("system").or(nameStartingWith("tick"))))
                .and(not(origin(A_CONSTRUCTOR_OR_STATIC_INITIALISER))))
        .because(
            "the time is DateTimeUtils.now(), whose clock a test pins; a class with a clock of"
                + " its own reads Clock.system* only where it is built, in a constructor or a"
                + " static initialiser (a field's initial value), never inside a method")
        .check(MAIN);
  }

  @Test
  void aUuidIsMintedOnlyByIds() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Ids.class.getName())
        .should()
        .accessTargetWhere(
            targetOwner(type(UUID.class))
                .and(target(name("randomUUID").or(name("nameUUIDFromBytes")))))
        .because(
            "every identifier is a time-ordered UUIDv7 from Ids.newId(); a UUID built from bytes"
                + " someone else minted is decoding, not minting, and stays allowed")
        .check(MAIN);
  }

  @Test
  void aChatsHostSpeaksAHarnessOnlyThroughItsWire() throws IOException {
    noClasses()
        .that(IN_SAIL_INFRA.and(not(THE_ROOMS_LIVE_MEMBERS)).and(not(CLAUDE_CODES)))
        .should()
        .dependOnClassesThat(CLAUDE_CODES)
        .because(
            "a chat's host, and every other class of sail-infra but the room's live members,"
                + " speaks a harness only through the per-process wire its HarnessChat makes, so a"
                + " second harness is its own adapter and wire, never a branch of the host")
        .check(MAIN);
    var api = Reactor.path("sail.root").resolve("sail-infra/src/main/java/ai/singlr/sail/api");
    var named = new ArrayList<String>();
    try (var sources = Files.list(api)) {
      for (var source : sources.filter(ArchitectureRulesTest::ofTheChatsHost).sorted().toList()) {
        var text = Files.readString(source).toLowerCase(Locale.ROOT);
        Harnesses.all().stream()
            .flatMap(harness -> Stream.of(harness.yamlName(), harness.displayName().split(" ")[0]))
            .map(name -> name.toLowerCase(Locale.ROOT))
            .filter(text::contains)
            .forEach(name -> named.add(source.getFileName() + ": " + name));
      }
    }
    assertEquals(List.of(), named, "the chat's host names no harness: its words come from one");
  }

  @Test
  void nothingInMainCodeIsDeprecated() {
    var oneWay =
        "there is one way to do each thing: a superseded type or member is deleted with every"
            + " caller moved to its replacement, never kept behind @Deprecated";
    noClasses().should().beAnnotatedWith(Deprecated.class).because(oneWay).check(MAIN);
    noMembers().should().beAnnotatedWith(Deprecated.class).because(oneWay).check(MAIN);
  }

  @Test
  void eachModuleIsToldByWhereItsClassesWereLoadedFrom() {
    var anchors =
        List.of(MAIN.get(Ids.class), MAIN.get(GuardrailChecker.class), MAIN.get(Main.class));
    var modules = List.of(IN_SAIL_CORE, IN_SAIL_HARNESS, IN_SAIL_INFRA);

    var seen = modules.stream().map(module -> anchors.stream().map(module::test).toList()).toList();

    assertEquals(
        List.of(
            List.of(true, false, false), List.of(false, true, false), List.of(false, false, true)),
        seen);
  }

  private static boolean ofTheChatsHost(Path source) {
    var name = source.getFileName().toString();
    return name.endsWith(".java") && name.startsWith("Chat");
  }

  private static DescribedPredicate<JavaClass> inModuleOf(Class<?> anchor) {
    var location = codeSource(anchor);
    return describe(
        "are in the module of " + anchor.getName(),
        type ->
            type.getSource()
                .map(source -> source.getUri().toString())
                .orElse("")
                .startsWith(location));
  }

  private static String codeSource(Class<?> anchor) {
    var location = anchor.getProtectionDomain().getCodeSource().getLocation().toString();
    return location.endsWith(".jar") ? "jar:" + location + "!/" : location;
  }
}
