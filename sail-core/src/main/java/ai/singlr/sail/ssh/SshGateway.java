/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.ssh;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides what a forced-command SSH session may run and as whom. When an engineer's key hits the
 * {@code sail} user's forced command, this resolves their FDE (passed as {@code --fde} on the
 * authorized_keys line), classifies the requested command, mints a short-lived session for that
 * FDE, and returns the argument vector to exec with {@code SAIL_TOKEN} set — so the downstream
 * command authenticates to the loopback API as the FDE and {@code Authorizer} enforces its role.
 *
 * <p>Commands are authorized by kind, default-deny:
 *
 * <ul>
 *   <li>{@link #API_COMMANDS} run for every active FDE — they talk to the loopback API, where the
 *       FDE's role decides what each request may do.
 *   <li>{@link #ADMIN_COMMANDS} are database-direct, so no downstream check exists; the gateway
 *       itself requires the {@code admin} role — except {@code fde passkey list|rm} and {@code fde
 *       enroll} on the caller's own pinned handle, which any active FDE may run. A bare {@code fde
 *       enroll} is pinned to the caller's handle first, so a thin client can self-enroll without
 *       knowing it.
 *   <li>Everything else ({@code project}, {@code host}, {@code server}, {@code migrate}, …) runs
 *       with host privileges the {@code sail} user must never have, and is refused.
 * </ul>
 */
public final class SshGateway {

  static final Duration SESSION_TTL = Duration.ofMinutes(10);

  /** Commands the loopback API authorizes per-request by FDE role. */
  public static final Set<String> API_COMMANDS = Set.of("spec", "agent", "events");

  /** Database-direct administration commands; the gateway admits only admin-role FDEs. */
  public static final Set<String> ADMIN_COMMANDS = Set.of("fde");

  /**
   * Database-direct sync RPC. The gateway admits any active FDE — a {@code viewer} may open a
   * session and pull — and the {@code _sync} server itself refuses pushes from read-only roles, so
   * the write gate lives next to the write rather than here. Not a user-typed command; the node's
   * {@code sail sync} opens it.
   */
  public static final Set<String> SYNC_COMMANDS = Set.of("_sync");

  private SshGateway() {}

  public sealed interface Decision permits Authorized, Rejected {}

  /** The command is permitted; exec {@code args} with {@code SAIL_TOKEN} = {@code sessionToken}. */
  public record Authorized(List<String> args, String sessionToken) implements Decision {}

  /** The command is refused; {@code reason} is safe to show the caller. */
  public record Rejected(String reason) implements Decision {}

  public static Decision authorize(
      String originalCommand, String fdeHandle, FdeStore fdes, AuthSessionStore sessions) {
    if (Strings.isBlank(originalCommand)) {
      return new Rejected(
          "No command supplied. Interactive shells are not permitted; run a 'sail' command.");
    }
    List<String> tokens;
    try {
      tokens = CommandTokenizer.split(originalCommand);
    } catch (IllegalArgumentException e) {
      return new Rejected(e.getMessage());
    }
    if (!tokens.isEmpty() && tokens.getFirst().equals("sail")) {
      tokens = tokens.subList(1, tokens.size());
    }
    if (tokens.isEmpty()) {
      return new Rejected("No 'sail' subcommand supplied.");
    }
    tokens = withSelfEnrollHandle(tokens, fdeHandle);
    var subcommand = tokens.getFirst();
    if (!API_COMMANDS.contains(subcommand)
        && !ADMIN_COMMANDS.contains(subcommand)
        && !SYNC_COMMANDS.contains(subcommand)) {
      return new Rejected(
          "'"
              + subcommand
              + "' requires host privileges and is not available over an SSH-key session."
              + " SSH to the host directly to run it.");
    }
    var fde = fdes.byHandle(fdeHandle);
    if (fde.isEmpty() || !"active".equals(fde.get().status())) {
      return new Rejected("Unknown or disabled FDE.");
    }
    if (ADMIN_COMMANDS.contains(subcommand)
        && !"admin".equals(fde.get().role())
        && !isOwnPasskeyCommand(tokens, fdeHandle)
        && !isOwnEnrollCommand(tokens, fdeHandle)) {
      return new Rejected(adminRequiredReason(tokens, fdeHandle, subcommand));
    }
    var session = sessions.create(fde.get().id(), SESSION_TTL);
    return new Authorized(List.copyOf(tokens), session.token());
  }

  /**
   * The one self-service carve-out in the admin-gated {@code fde} family: an FDE may list and
   * revoke its own passkeys ({@code fde passkey list|rm <handle>}). The target handle is compared
   * against the handle pinned to the calling key on the {@code authorized_keys} line, so a
   * non-admin can never reach another FDE's credentials — and only when the handle sits directly
   * after the verb, so an option-first spelling fails closed to the admin gate.
   */
  private static boolean isOwnPasskeyCommand(List<String> tokens, String fdeHandle) {
    return isPasskeyCommand(tokens)
        && tokens.size() >= 4
        && Set.of("list", "rm").contains(tokens.get(2))
        && tokens.get(3).equals(fdeHandle);
  }

  private static boolean isPasskeyCommand(List<String> tokens) {
    return tokens.size() >= 2 && tokens.get(0).equals("fde") && tokens.get(1).equals("passkey");
  }

  /**
   * Pins a bare {@code fde enroll} (no target handle) to the caller. A thin client cannot know its
   * own handle — the handle bound to the calling key on the {@code authorized_keys} line is the
   * identity the gateway trusts — so self-enrollment sends {@code fde enroll} and the gateway fills
   * in the caller before authorization runs.
   */
  private static List<String> withSelfEnrollHandle(List<String> tokens, String fdeHandle) {
    if (isEnrollCommand(tokens) && (tokens.size() == 2 || tokens.get(2).startsWith("-"))) {
      var pinned = new ArrayList<>(tokens);
      pinned.add(2, fdeHandle);
      return List.copyOf(pinned);
    }
    return tokens;
  }

  /**
   * The second self-service carve-out: an FDE may mint an enrollment ticket for itself ({@code fde
   * enroll <own-handle>}), compared against the pinned handle exactly like {@link
   * #isOwnPasskeyCommand}. Minting for anyone else stays admin-only.
   */
  private static boolean isOwnEnrollCommand(List<String> tokens, String fdeHandle) {
    return isEnrollCommand(tokens) && tokens.size() >= 3 && tokens.get(2).equals(fdeHandle);
  }

  private static boolean isEnrollCommand(List<String> tokens) {
    return tokens.size() >= 2 && tokens.get(0).equals("fde") && tokens.get(1).equals("enroll");
  }

  private static String adminRequiredReason(
      List<String> tokens, String fdeHandle, String subcommand) {
    if (isPasskeyCommand(tokens)) {
      return "Managing another FDE's passkeys requires the admin role. You may manage your"
          + " own: sail fde passkey list "
          + fdeHandle;
    }
    if (isEnrollCommand(tokens)) {
      return "Minting an enrollment ticket for another FDE requires the admin role."
          + " Enroll yourself with: sail enroll";
    }
    return "'" + subcommand + "' requires the admin role.";
  }
}
