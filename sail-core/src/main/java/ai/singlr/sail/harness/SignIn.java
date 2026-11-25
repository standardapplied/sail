/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import java.util.Objects;

/**
 * A harness's own sign-in, which a person makes inside its session in their workspace with a
 * subscription or the vendor's account, and which Sail never holds: the {@code name} a person knows
 * it by, {@code how} it goes in a sentence the New Session panel says, and the {@code file} under
 * the dev user's home it keeps it in, whose presence says the workspace holds it. That file is
 * never opened.
 */
public record SignIn(String name, Remedy how, String file) {

  public SignIn {
    Strings.requireNonBlank(name, "name");
    Objects.requireNonNull(how, "how");
    Strings.requireNonBlank(file, "file");
  }
}
