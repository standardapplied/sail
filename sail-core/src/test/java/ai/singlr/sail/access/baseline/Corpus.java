/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.Remedy;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The answers of one generated corpus, one per cell: a stable key naming who asks what of which
 * resource, and the answer today's rules give. An answer is {@code allowed}, or {@code denied}, the
 * refusal's kind or door code, its whole message and, after {@code |}, its whole fix, so a change
 * of wording shows as plainly as a change of verdict. Both are spelled the same on every run: an id
 * the fixture minted at random by the name the fixture gave it, one minted while the cell is asked
 * as {@value #NEW_ID}; the fixture's own fixed ids, all {@code 019fee00-0000-7000-8000-…}, as they
 * are.
 */
public final class Corpus {

  /** The answer of a cell its rules let through. */
  public static final String ALLOWED = "allowed";

  /** How an id minted while a cell is asked is spelled in its answer. */
  public static final String NEW_ID = "<new id>";

  private static final Pattern MINTED =
      Pattern.compile(
          "(?!019fee00-0000-7000-8000-)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private final SortedMap<String, String> answers = new TreeMap<>();
  private final Map<String, String> names;

  /** A corpus whose answers name nothing minted at random. */
  public Corpus() {
    this(Map.of());
  }

  /**
   * A corpus whose answers spell each key of {@code names}, an id its fixture minted at random, as
   * its value, so the answer is the same on every run.
   */
  public Corpus(Map<String, String> names) {
    this.names = names;
  }

  /**
   * Records {@code answer} for {@code cell}, each with its ids spelled as the corpus spells them.
   *
   * @throws IllegalArgumentException when the cell is already answered, or its key is blank or
   *     would not stay one field of one line
   */
  public void put(String cell, String answer) {
    var key = named(cell);
    if (key.isBlank() || key.contains("\t") || key.contains("\n")) {
      throw new IllegalArgumentException("A cell key is one non-blank line with no tab: " + key);
    }
    if (answers.putIfAbsent(key, flat(named(answer))) != null) {
      throw new IllegalArgumentException("Cell answered twice: " + key);
    }
  }

  /**
   * Records what {@code ask} answers for {@code cell}: allowed, or the refusal it throws as {@link
   * WriteRefused}. Any other failure is answered with its class and message, so a corpus never
   * stops at the first surprise.
   */
  public void ask(String cell, Supplier<String> ask) {
    String answer;
    try {
      answer = ask.get();
    } catch (WriteRefused refused) {
      answer = denied(refused.refusal());
    } catch (RuntimeException failed) {
      answer = failed(failed);
    }
    put(cell, answer);
  }

  /** The answers, sorted by cell. */
  public SortedMap<String, String> answers() {
    return answers;
  }

  /** The number of cells answered. */
  public int size() {
    return answers.size();
  }

  /** {@code refusal} as an answer: allowed when empty. */
  public static String decided(Optional<Refusal> refusal) {
    return refusal.map(Corpus::denied).orElse(ALLOWED);
  }

  /** The answer of a refused cell: its kind, its message and its fix. */
  public static String denied(Refusal refusal) {
    return denied(refusal.kind().name(), refusal.message(), refusal.fix());
  }

  /** The answer of a cell refused under {@code code} with {@code message} and {@code fix}. */
  public static String denied(String code, String message, Remedy fix) {
    return "denied " + code + " " + message + " | " + fix.plain();
  }

  /** The answer of a cell whose ask failed other than by a refusal. */
  public static String failed(RuntimeException failure) {
    return "failed " + failure.getClass().getSimpleName() + " " + failure.getMessage();
  }

  /** {@code answer} with every line break and tab spelled out, so it stays on its line. */
  private static String flat(String answer) {
    return answer.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
  }

  private String named(String answer) {
    var named = answer;
    for (var name : names.entrySet()) {
      named = named.replace(name.getKey(), name.getValue());
    }
    return MINTED.matcher(named).replaceAll(NEW_ID);
  }
}
