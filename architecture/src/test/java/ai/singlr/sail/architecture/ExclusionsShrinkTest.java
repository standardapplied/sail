/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * The PMD exclusions only shrink: every entry of {@code config/quality/pmd-exclusions.properties}
 * names a class that still breaks every rule the entry lists. The PMD reports record a listed
 * class's violations, since only the checks read the exclusions file, so an entry the reports no
 * longer bear out is one a change made obsolete, and it is deleted with that change.
 */
class ExclusionsShrinkTest {

  private static final Path ROOT = Path.of(System.getProperty("sail.root", ".."));
  private static final List<String> MODULES = List.of("sail-core", "sail-harness", "sail-infra");
  private static final List<String> REPORTS = List.of("target/pmd.xml", "target/pmd-test/pmd.xml");

  @Test
  void everyListedClassStillBreaksEveryRuleItsEntryNames() throws Exception {
    assumeFalse(
        Boolean.getBoolean("sail.pmd.skip"), "PMD is skipped, so no report says what breaks");
    var broken = reported();

    var obsolete = listed().stream().filter(entry -> !broken.contains(entry)).toList();

    assertEquals(
        List.of(),
        obsolete,
        "these classes no longer break the rule their entry lists: delete the rule from the"
            + " entry, and the entry once it lists none, in config/quality/pmd-exclusions.properties");
  }

  private static List<String> listed() throws IOException {
    var entries = new Properties();
    try (Reader reader =
        Files.newBufferedReader(ROOT.resolve("config/quality/pmd-exclusions.properties"))) {
      entries.load(reader);
    }
    return entries.stringPropertyNames().stream()
        .sorted()
        .flatMap(
            type ->
                Stream.of(entries.getProperty(type).split(","))
                    .map(rule -> type + "=" + rule.strip()))
        .toList();
  }

  private static Set<String> reported() throws Exception {
    var broken = new HashSet<String>();
    for (var module : MODULES) {
      for (var report : REPORTS) {
        var file = ROOT.resolve(module).resolve(report);
        assertTrue(
            Files.isRegularFile(file),
            "no PMD report at "
                + file
                + ": build "
                + module
                + " in this reactor, or install it, without -Dpmd.skip");
        broken.addAll(violations(file));
      }
    }
    return broken;
  }

  private static Set<String> violations(Path report) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var found =
        factory
            .newDocumentBuilder()
            .parse(report.toFile())
            .getElementsByTagNameNS("*", "violation");
    var violations = new HashSet<String>();
    IntStream.range(0, found.getLength())
        .mapToObj(index -> (Element) found.item(index))
        .forEach(
            violation ->
                violations.add(
                    violation.getAttribute("package")
                        + "."
                        + violation.getAttribute("class")
                        + "="
                        + violation.getAttribute("rule")));
    return violations;
  }
}
