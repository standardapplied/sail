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
 * passkey session, the box credential, the host CLI, a run credential, a sync session, the SSH
 * gateway and the terminal. This box's operator on main or a standalone box, the FDE its sync
 * handle names, is admin, because they hold root on it. Every other FDE acts with the role main's
 * roster gives it. Either way the credential's own role caps it, so no credential acts beyond what
 * it was minted with. An FDE the roster marks disabled is refused, and so is one it does not know,
 * unless it is the operator. A null {@code roster} is a box that keeps none yet: it knows no FDE
 * but its operator. A credential that names no FDE acts with the role of the box's FDE ({@link
 * #roleOfUnbound}), so no credential on a box acts beyond the FDE whose box it is.
 *
 * <p>A node's writes speak for its FDE: every revision it pushes reaches main on the box's session,
 * which main decides as that FDE's. So on a node a credential naming any other FDE, an admin's
 * included, acts with at most {@link Role#VIEWER}, and the node refuses its writes up front rather
 * than pushing what main would deny.
 */
public final class RoleRule {

  private final Supplier<SyncConfig> box;
  private final FdeStore roster;

  public RoleRule(Supplier<SyncConfig> box, FdeStore roster) {
    this.box = Objects.requireNonNull(box, "box");
    this.roster = roster;
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
    var bound = foreignOnNode(handle) ? cap.cappedBy(Role.VIEWER) : cap;
    return fde.map(found -> Role.fromAttribute(found.role()).cappedBy(bound));
  }

  /** Whether {@code handle} names an FDE other than this node's own. */
  private boolean foreignOnNode(String handle) {
    var config = box.get();
    return config.isNode() && !handle.equals(config.handle());
  }

  /**
   * The role a credential that names no FDE, minted with {@code cap}, acts with: the role of the
   * box's FDE ({@link #boxFde}), so on a node it is refused until the roster knows that FDE. Main
   * or a standalone box with no sync handle has no FDE to be, and the credential acts with {@code
   * cap}; a node with none syncs as no one, and is refused. Empty when the credential is refused.
   */
  public Optional<Role> roleOfUnbound(Role cap) {
    var fde = boxFde();
    if (fde.isPresent()) {
      return roleOf(fde.get(), cap);
    }
    return box.get().isNode() ? Optional.empty() : Optional.of(cap);
  }

  /** The FDE this box is, named by its sync handle; empty on a box with no sync handle. */
  public Optional<String> boxFde() {
    var handle = box.get().handle();
    return Strings.isBlank(handle) ? Optional.empty() : Optional.of(handle);
  }

  private boolean isOperator(String handle) {
    var config = box.get();
    return !config.isNode() && handle.equals(config.handle());
  }
}
