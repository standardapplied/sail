/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.ssh;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import java.time.Duration;
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
 *       itself requires the {@code admin} role.
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
    if (ADMIN_COMMANDS.contains(subcommand) && !"admin".equals(fde.get().role())) {
      return new Rejected("'" + subcommand + "' requires the admin role.");
    }
    var session = sessions.create(fde.get().id(), SESSION_TTL);
    return new Authorized(List.copyOf(tokens), session.token());
  }
}
