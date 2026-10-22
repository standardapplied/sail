/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

/**
 * One sync session's transport: an {@code ssh sail@main sail _sync} subprocess whose stdio is the
 * RPC pipe. The remote {@code _sync} server reads requests from this channel's {@link #writer} and
 * writes responses to its {@link #reader}; a {@link ai.singlr.sail.sync.SyncSession} drives both
 * ends. Like the rest of the gateway lane, password and keyboard-interactive auth are disabled so a
 * missing key fails fast instead of dangling a prompt for the locked {@code sail} account.
 */
public final class SshSyncChannel implements SyncOperations.Channel {

  private static final Duration EXIT_GRACE = Duration.ofSeconds(5);
  private static final String LINE = "authorize_command";

  private final Process process;
  private final InputStream reader;
  private final OutputStream writer;
  private final Thread drain;
  private final Function<String, Optional<String>> authorisation;
  private volatile String lastLine = "";

  /**
   * Wraps a started {@code ssh} whose stderr is piped, forwarding it as it arrives; {@code
   * authorisation} is the line that authorises the key it presented, for a handle.
   */
  SshSyncChannel(Process process, Function<String, Optional<String>> authorisation) {
    this.process = process;
    this.authorisation = authorisation;
    this.reader = new BufferedInputStream(process.getInputStream());
    this.writer = process.getOutputStream();
    this.drain = Thread.ofVirtual().start(() -> drain(process.getErrorStream()));
  }

  /** Opens a sync channel to {@code target} (e.g. {@code sail@maindevbox}). */
  public static SshSyncChannel open(String target) throws IOException {
    var key = SyncIdentity.resolve();
    return new SshSyncChannel(
        new ProcessBuilder(sshCommand(target, key.orElse(null))).start(),
        handle ->
            key.isEmpty()
                ? Optional.empty()
                : authorisation(
                    handle,
                    SailPaths.syncPublicKeyPath(),
                    target,
                    MainHostKey.here(),
                    receipt(SailPaths.hostConfigPath())));
  }

  @Override
  public Optional<String> authorisation(String handle) {
    return authorisation.apply(handle);
  }

  /** Where a join keeps the line it printed, beside the host config {@code hostConfig}. */
  public static Path receipt(Path hostConfig) {
    return hostConfig.resolveSibling("join.yaml");
  }

  /**
   * Keeps, in {@code receipt}, the line a join printed for main's operator to authorise {@code
   * publicKey} as {@code handle} on {@code target}, whose host key it pinned as {@code
   * mainHostKey}, and returns that line.
   */
  public static String keep(
      Path receipt,
      String target,
      String handle,
      String name,
      String email,
      String publicKey,
      String mainHostKey)
      throws IOException {
    var line = authorizeLine(handle, name, email, publicKey, mainHostKey);
    var kept = new LinkedHashMap<String, Object>(joined(target, handle, publicKey, mainHostKey));
    kept.put(LINE, line);
    YamlUtil.dumpToFile(kept, receipt);
    return line;
  }

  /**
   * The line the join kept in {@code receipt}, while it still names this box: {@code handle}, the
   * key at {@code publicKey}, and the fingerprint of main's host key this box pinned for {@code
   * target}. Empty when the box holds no key, no pin or no such line to name, a receipt a crash cut
   * short included.
   */
  static Optional<String> authorisation(
      String handle, Path publicKey, String target, MainHostKey mainHostKey, Path receipt) {
    try {
      var pinned = mainHostKey.pinned(target);
      if (Strings.isBlank(handle)
          || pinned.isEmpty()
          || !Files.isRegularFile(publicKey)
          || !Files.isRegularFile(receipt)) {
        return Optional.empty();
      }
      var kept = YamlUtil.parseFile(receipt);
      var current = joined(target, handle, Files.readString(publicKey).strip(), pinned.get());
      return current.entrySet().stream().allMatch(e -> e.getValue().equals(kept.get(e.getKey())))
              && kept.get(LINE) instanceof String line
          ? Optional.of(line)
          : Optional.empty();
    } catch (IOException | TimeoutException | YamlEngineException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private static Map<String, String> joined(
      String target, String handle, String publicKey, String mainHostKey) {
    return Map.of(
        "target", target, "handle", handle, "public_key", publicKey, "main_host_key", mainHostKey);
  }

  /**
   * The {@code sail fde add} line main's operator runs to authorise a node's key, carrying the host
   * key this node pinned for main so main refuses it when another box answered the join. Every
   * value is one shell word, so a name main's operator pastes runs nothing.
   */
  public static String authorizeLine(
      String handle, String name, String email, String publicKey, String mainHostKey) {
    var line =
        new StringBuilder(
            OperatorCommand.SUDO.sail("fde add " + Strings.shellWord(handle) + " --role member"));
    if (Strings.isNotBlank(name)) {
      line.append(" --name ").append(Strings.shellWord(name));
    }
    if (Strings.isNotBlank(email)) {
      line.append(" --email ").append(Strings.shellWord(email));
    }
    line.append(" --key ").append(Strings.shellWord(publicKey));
    line.append(" --main-host-key ").append(Strings.shellWord(mainHostKey));
    return line.toString();
  }

  /** Forwards each line ssh writes to stderr, as an inherited stderr did, keeping the last one. */
  private void drain(InputStream stderr) {
    try (var lines = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
      for (var line = lines.readLine(); line != null; line = lines.readLine()) {
        System.err.println(line);
        if (!line.isBlank()) {
          lastLine = line.strip();
        }
      }
    } catch (IOException closed) {
      System.err.println("ssh's stderr closed: " + closed.getMessage());
    }
  }

  /**
   * The last line ssh wrote to stderr, once it has exited and that stream is drained. A channel
   * that closed before main replied is an ssh on its way out, so the wait is short.
   */
  @Override
  public String diagnostic() {
    try {
      if (process.waitFor(EXIT_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
        drain.join(EXIT_GRACE);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return lastLine;
  }

  /**
   * The {@code ssh} argument vector for a sync session. Like the rest of the gateway lane it
   * forbids password and keyboard-interactive auth, so a missing key fails fast rather than
   * dangling a prompt for the locked {@code sail} account. When {@code identity} is non-null the
   * lane pins {@code sail join}'s managed key with {@code IdentitiesOnly} so it never silently
   * falls back to an unrelated agent key.
   */
  static List<String> sshCommand(String target, Path identity) {
    var command =
        new ArrayList<>(
            List.of(
                "ssh",
                "-o",
                "PasswordAuthentication=no",
                "-o",
                "KbdInteractiveAuthentication=no",
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=10",
                "-o",
                "ConnectionAttempts=1",
                "-o",
                "ServerAliveInterval=10",
                "-o",
                "ServerAliveCountMax=2"));
    if (identity != null) {
      command.add("-o");
      command.add("IdentitiesOnly=yes");
      command.add("-i");
      command.add(identity.toString());
    }
    command.add(target);
    command.add("sail");
    command.add("_sync");
    return List.copyOf(command);
  }

  public InputStream reader() {
    return reader;
  }

  public OutputStream writer() {
    return writer;
  }

  /** Closes this node's end of the pipe and waits for the remote {@code _sync} to exit. */
  @Override
  public void close() throws IOException {
    writer.close();
    reader.close();
    try {
      process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroy();
    }
  }
}
