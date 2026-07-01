/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.PlaceholderResolver;
import ai.singlr.sail.engine.LocalIdentity;
import java.io.PrintStream;
import java.util.function.UnaryOperator;
import picocli.CommandLine.Help.Ansi;

/**
 * Answers a synced descriptor's per-developer placeholders when {@code project create} provisions
 * it. Git identity is prompted — offering this box's {@code git config} as a default to accept or
 * override — so each engineer's containers commit as them even when their box has a different (or
 * no) global identity. {@code ${SSH_PUBLIC_KEY}} stays box-resolved from the box owner's registered
 * workstation key, never typed. When the box cannot prompt ({@code --yes}/{@code --json}/no TTY) it
 * falls back to the box's own identity and fails loud with the one command that fixes it.
 */
public final class InteractiveIdentity implements UnaryOperator<String> {

  private final LocalIdentity box;
  private final boolean canPrompt;
  private final PrintStream out;
  private final Ansi ansi;

  public InteractiveIdentity(LocalIdentity box, boolean canPrompt) {
    this(box, canPrompt, System.out, Ansi.AUTO);
  }

  InteractiveIdentity(LocalIdentity box, boolean canPrompt, PrintStream out, Ansi ansi) {
    this.box = box;
    this.canPrompt = canPrompt;
    this.out = out;
    this.ansi = ansi;
  }

  /** Whether create may prompt: interactive, non-JSON, not a dry run, with a real console. */
  public static boolean canPrompt(boolean yes, boolean json, boolean dryRun, boolean hasConsole) {
    return !yes && !json && !dryRun && hasConsole;
  }

  @Override
  public String apply(String placeholder) {
    return switch (placeholder) {
      case PlaceholderResolver.GIT_NAME, PlaceholderResolver.GIT_EMAIL -> identity(placeholder);
      default -> box.valueFor(placeholder);
    };
  }

  private String identity(String placeholder) {
    if (!canPrompt) {
      return box.valueFor(placeholder);
    }
    var label = PlaceholderResolver.promptFor(placeholder);
    return box.gitValue(placeholder)
        .map(current -> ConsoleHelper.promptWithDefault(out, ansi, label, current))
        .orElseGet(() -> ConsoleHelper.promptRequired(out, ansi, label));
  }
}
