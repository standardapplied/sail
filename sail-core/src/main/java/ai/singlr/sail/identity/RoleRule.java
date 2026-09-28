/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.store.FdeStore;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The role a credential that names an FDE acts with: one rule for every door — an API token, a
 * passkey session, the box credential, the host CLI, a sync session, the SSH gateway and the
 * terminal. This box's operator on main or a standalone box, the FDE its sync handle names, is
 * admin, because they hold root on it. Every other FDE acts with the role main's roster gives it.
 * Either way the credential's own role caps it, so no credential acts beyond what it was minted
 * with. An FDE the roster marks disabled is refused, and so is one it does not know, unless it is
 * the operator. A null {@code roster} is a box that keeps none yet: it knows no FDE but its
 * operator.
 */
public record RoleRule(Supplier<SyncConfig> box, FdeStore roster) {

  public RoleRule {
    Objects.requireNonNull(box, "box");
  }

  /**
   * The role a credential naming FDE {@code handle} and minted with {@code cap} acts with; empty
   * when the credential is refused.
   */
  public Optional<Role> roleOf(String handle, Role cap) {
    if (Strings.isBlank(handle)) {
      return Optional.empty();
    }
    var fde = roster == null ? Optional.<FdeStore.Fde>empty() : roster.byHandle(handle);
    if (fde.isPresent() && !fde.get().active()) {
      return Optional.empty();
    }
    if (isOperator(handle)) {
      return Optional.of(Role.ADMIN.cappedBy(cap));
    }
    return fde.map(found -> Role.fromAttribute(found.role()).cappedBy(cap));
  }

  private boolean isOperator(String handle) {
    var config = box.get();
    return !config.isNode() && handle.equals(config.handle());
  }
}
