/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The gated rule set and the pending one of each kind are together Helios's rule set with Helios's
 * limits: every rule is in exactly one of them, and no limit differs. A rule moves from pending to
 * gated unchanged; one deleted from pending, or moved with a new limit, fails here. The limits are
 * the properties a rule sets; a rule that sets none keeps PMD's default, which is Helios's too.
 */
class RuleSetsTest {

  private static final Path CONFIG = Path.of(System.getProperty("sail.quality.config"));

  private static final Map<String, List<String>> SHARED =
      Map.ofEntries(
          Map.entry("CognitiveComplexity", List.of("reportLevel=15")),
          Map.entry("NPathComplexity", List.of()),
          Map.entry("AvoidDeeplyNestedIfStmts", List.of()),
          Map.entry("NcssCount", List.of("classReportLevel=600", "methodReportLevel=60")),
          Map.entry("ExcessiveParameterList", List.of()),
          Map.entry("TooManyFields", List.of()),
          Map.entry("CouplingBetweenObjects", List.of()),
          Map.entry("SwitchDensity", List.of()),
          Map.entry("OnDemandImport", List.of()));

  private static final Map<String, Map<String, List<String>>> HELIOS =
      Map.of(
          "main",
          with(
              Map.of(
                  "CyclomaticComplexity",
                  List.of("classReportLevel=80", "methodReportLevel=15"),
                  "GodClass",
                  List.of(),
                  "TooManyMethods",
                  List.of("maxmethods=25"))),
          "test",
          with(
              Map.of(
                  "CyclomaticComplexity",
                  List.of("classReportLevel=2147483647", "methodReportLevel=15"),
                  "SleepInTest",
                  List.of(),
                  "PipedStreamInTest",
                  List.of(),
                  "ClockReadInTest",
                  List.of(),
                  "TimedWaitInTest",
                  List.of())));

  @ParameterizedTest(name = "pmd-{0}.xml and pmd-{0}-pending.xml are Helios's {0} rule set")
  @ValueSource(strings = {"main", "test"})
  void theGatedAndPendingRulesAreHeliosRulesWithItsLimits(String kind) throws Exception {
    var rules = new ArrayList<Map.Entry<String, List<String>>>();
    rules.addAll(rules(CONFIG.resolve("pmd-" + kind + ".xml")));
    rules.addAll(rules(CONFIG.resolve("pmd-" + kind + "-pending.xml")));

    var byName = new TreeMap<String, List<String>>();
    rules.forEach(rule -> byName.merge(rule.getKey(), rule.getValue(), RuleSetsTest::twice));

    assertEquals(new TreeMap<>(HELIOS.get(kind)), byName);
  }

  private static List<Map.Entry<String, List<String>>> rules(Path ruleSet) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var found = factory.newDocumentBuilder().parse(ruleSet.toFile()).getElementsByTagName("rule");
    return elements(found).stream().map(rule -> Map.entry(name(rule), limits(rule))).toList();
  }

  private static String name(Element rule) {
    var ref = rule.getAttribute("ref");
    return ref.isEmpty() ? rule.getAttribute("name") : ref.substring(ref.lastIndexOf('/') + 1);
  }

  private static List<String> limits(Element rule) {
    return elements(rule.getElementsByTagName("property")).stream()
        .filter(property -> !property.getAttribute("name").equals("xpath"))
        .map(property -> property.getAttribute("name") + "=" + property.getAttribute("value"))
        .sorted()
        .toList();
  }

  private static List<Element> elements(NodeList nodes) {
    return IntStream.range(0, nodes.getLength())
        .mapToObj(index -> (Element) nodes.item(index))
        .toList();
  }

  private static List<String> twice(List<String> first, List<String> second) {
    return List.of("in more than one rule set");
  }

  private static Map<String, List<String>> with(Map<String, List<String>> own) {
    var all = new TreeMap<>(SHARED);
    all.putAll(own);
    return all;
  }
}
