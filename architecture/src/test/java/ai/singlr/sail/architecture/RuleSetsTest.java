/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ls.DOMImplementationLS;
import org.xml.sax.InputSource;

/**
 * The gated rule set and the pending one of each kind are together Helios's rule set: every rule is
 * in exactly one of them and equals Helios's in every attribute, property and XPath, its message
 * aside, whitespace collapsed. {@code helios-pmd-main.xml} and {@code helios-pmd-test.xml} are
 * Helios's files verbatim, read by no build, so a rule is moved from pending to gated unchanged,
 * and one deleted, weakened or in both fails here. Helios's test-helper package reads as Sail's,
 * the one name the two rule sets differ in.
 */
class RuleSetsTest {

  private static final String HELIOS_TEST_PACKAGE = "com.standardapplied.helios.core.test";
  private static final String SAIL_TEST_PACKAGE = "ai.singlr.sail.test";

  @ParameterizedTest(name = "pmd-{0}.xml and pmd-{0}-pending.xml are helios-pmd-{0}.xml")
  @ValueSource(strings = {"main", "test"})
  void theGatedAndPendingRulesAreHeliosRules(String kind) throws Exception {
    var config = Reactor.path("sail.quality.config");
    var helios = Files.readString(config.resolve("helios-pmd-" + kind + ".xml"));
    var gated = Files.readString(config.resolve("pmd-" + kind + ".xml"));
    var pending = Files.readString(config.resolve("pmd-" + kind + "-pending.xml"));

    assertEquals(
        rules(helios.replace(HELIOS_TEST_PACKAGE, SAIL_TEST_PACKAGE)),
        rules(gated, pending),
        "pmd-" + kind + ".xml and pmd-" + kind + "-pending.xml hold each Helios rule once, as is");
  }

  private static Map<String, String> rules(String... ruleSets) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setIgnoringComments(true);
    factory.setCoalescing(true);
    var xpath = XPathFactory.newInstance().newXPath();
    var rules = new TreeMap<String, String>();
    for (var ruleSet : ruleSets) {
      var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(ruleSet)));
      nodes(xpath.evaluate("//text()", document, XPathConstants.NODESET))
          .forEach(
              text ->
                  text.setTextContent(
                      String.join(" ", text.getTextContent().split("\\s+")).strip()));
      var serializer = ((DOMImplementationLS) document.getImplementation()).createLSSerializer();
      serializer.getDomConfig().setParameter("xml-declaration", false);
      nodes(xpath.evaluate("/ruleset/*[not(self::description)]", document, XPathConstants.NODESET))
          .map(Element.class::cast)
          .forEach(
              rule -> {
                rule.removeAttribute("message");
                rules.merge(name(rule), serializer.writeToString(rule), RuleSetsTest::twice);
              });
    }
    return rules;
  }

  private static String name(Element rule) {
    var ref = rule.getAttribute("ref");
    if (!ref.isEmpty()) {
      return ref.substring(ref.lastIndexOf('/') + 1);
    }
    return rule.hasAttribute("name") ? rule.getAttribute("name") : rule.getTagName();
  }

  private static Stream<Node> nodes(Object found) {
    var nodes = (NodeList) found;
    return IntStream.range(0, nodes.getLength()).mapToObj(nodes::item);
  }

  private static String twice(String first, String second) {
    return "in more than one rule set";
  }
}
