/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;

/**
 * What {@code sail init} is being asked to make this box: the org's {@link Main} source of truth,
 * or a {@link Node} that joins an existing main. Resolved without I/O so the validation is
 * unit-tested.
 */
sealed interface InitIntent {

  record Main() implements InitIntent {}

  record Node(String target) implements InitIntent {}

  static InitIntent resolve(boolean asMain, String mainTarget) {
    var joining = Strings.isNotBlank(mainTarget);
    if (asMain && joining) {
      throw new IllegalArgumentException("Choose either --as-main or --main <target>, not both.");
    }
    if (asMain) {
      return new Main();
    }
    if (joining) {
      return new Node(mainTarget.strip());
    }
    throw new IllegalArgumentException(
        "Say what this box is: --as-main (it coordinates the org) or --main <target> (it joins an"
            + " existing main, e.g. --main sail@maindevbox).");
  }
}
