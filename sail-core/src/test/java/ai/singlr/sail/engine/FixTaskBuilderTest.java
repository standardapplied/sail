/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.Finding;
import java.util.List;
import org.junit.jupiter.api.Test;

class FixTaskBuilderTest {

  @Test
  void emptyFindingsReturnsNoActionMessage() {
    var task = FixTaskBuilder.build("OAuth flow", List.of());
    assertTrue(task.contains("No review findings"));
    assertTrue(task.contains("OAuth flow"));
  }

  @Test
  void singleFindingIncludesAllDetails() {
    var finding =
        Finding.create(
            Finding.Severity.CRITICAL,
            Finding.Category.SECURITY,
            "src/Auth.java",
            42,
            42,
            "SQL injection",
            "User input directly concatenated into SQL query.",
            "Input flows from getParam() to execute()",
            new Finding.Suggestion(
                "db.exec(sql + id)", "db.exec(sql, id)", "Use parameterized queries"),
            0.95);

    var task = FixTaskBuilder.build("OAuth flow", List.of(finding));

    assertTrue(task.contains("1 review finding(s)"));
    assertTrue(task.contains("[CRITICAL] SECURITY"));
    assertTrue(task.contains("SQL injection"));
    assertTrue(task.contains("src/Auth.java:42"));
    assertTrue(task.contains("Input flows from getParam()"));
    assertTrue(task.contains("Use parameterized queries"));
    assertTrue(task.contains("db.exec(sql + id)"));
    assertTrue(task.contains("db.exec(sql, id)"));
  }

  @Test
  void multiLineRangeShowsStartAndEnd() {
    var finding =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.LOGIC,
            "Service.java",
            10,
            25,
            "Off-by-one",
            "Loop bound incorrect",
            "",
            new Finding.Suggestion("", "", "Fix the loop bound"),
            0.8);

    var task = FixTaskBuilder.build("Payment", List.of(finding));
    assertTrue(task.contains("Service.java:10-25"));
  }

  @Test
  void multipleFindingsNumberedSequentially() {
    var f1 =
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "a.java",
            1,
            1,
            "First issue",
            "Description 1",
            "",
            null,
            0.9);
    var f2 =
        Finding.create(
            Finding.Severity.MEDIUM,
            Finding.Category.LOGIC,
            "b.java",
            5,
            5,
            "Second issue",
            "Description 2",
            "",
            null,
            0.7);

    var task = FixTaskBuilder.build("Spec", List.of(f1, f2));
    assertTrue(task.contains("Finding 1"));
    assertTrue(task.contains("Finding 2"));
    assertTrue(task.contains("2 review finding(s)"));
  }

  @Test
  void findingWithoutFileOmitsFileLine() {
    var finding =
        Finding.create(
            Finding.Severity.LOW,
            Finding.Category.API_CONTRACT,
            null,
            0,
            0,
            "API contract issue",
            "Desc",
            "",
            null,
            0.5);

    var task = FixTaskBuilder.build("Spec", List.of(finding));
    assertFalse(task.contains("File:"));
  }

  @Test
  void findingWithoutSuggestionOmitsFixSection() {
    var finding =
        Finding.create(
            Finding.Severity.MEDIUM,
            Finding.Category.PERFORMANCE,
            "a.java",
            1,
            1,
            "Slow query",
            "N+1 query pattern",
            "Evidence",
            null,
            0.7);

    var task = FixTaskBuilder.build("Spec", List.of(finding));
    assertFalse(task.contains("Fix:"));
  }

  @Test
  void taskIncludesSpecTitleInHeader() {
    var finding =
        Finding.create(
            Finding.Severity.LOW,
            Finding.Category.LOGIC,
            "a.java",
            1,
            1,
            "Issue",
            "",
            "",
            null,
            0.3);

    var task = FixTaskBuilder.build("Payment Integration", List.of(finding));
    assertTrue(task.contains("\"Payment Integration\""));
  }
}
