/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.pty.Account;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The terminal host's door on a person's own machine ({@link LocalPtyHost}): its one person, and
 * the folders they open sessions in.
 *
 * <p>A connection on the owner-only socket that presents no token is the machine's person, the
 * account the host runs as, resolved as every box resolves its own person for a blank token ({@link
 * RoleRule.Credential#box}), with no roster to consult. Any token is refused: nothing here could
 * tell whose it is. Attaching, killing and listing then ask {@link Access} as the server's host
 * does, and opening asks it whether this person operates this machine, as {@code HostAccess.box}
 * asks of a box.
 *
 * <p>The host holds no projects and no rooms, so an open that names either is refused. A session
 * runs in the folder its open names, an existing directory the person may enter, named by its
 * absolute path and run in once resolved. With no command it runs the account's login shell as a
 * login shell, whose own start-up builds {@code PATH}, in the environment a terminal emulator's
 * login gives it, since a service starts with almost none: {@code HOME}, {@code USER}, {@code
 * LOGNAME} and {@code SHELL} from the account database, and a UTF-8 {@code LANG}, the host's own
 * when it names one.
 */
final class LocalPtyAccess implements PtyIdentity.Resolver, PtyAdmission {

  static final String TOKEN_REFUSED =
      "This terminal host serves this machine's own person alone, who presents no token, so it"
          + " takes none: connect again presenting none.";

  static final String FOLDERS_ONLY =
      "The local terminal host opens folders, not projects or rooms: open the session with"
          + " neither, in the folder it runs in.";

  /** The locale a session runs in when the host's own names no UTF-8 one, as Terminal sets. */
  static final String FALLBACK_LANG = "en_US.UTF-8";

  private final Account account;
  private final PtyIdentity.Person person;
  private final Map<String, String> environment;

  /** The door for {@code account}, whose sessions take {@code hostLang} when it is UTF-8. */
  LocalPtyAccess(Account account, String hostLang) {
    this.account = account;
    this.person =
        new PtyIdentity.Person(
            new RoleRule(() -> new SyncConfig(null, null, account.name()), null)
                .principal(RoleRule.Credential.box())
                .orElseThrow());
    this.environment =
        Map.of(
            "HOME", account.home().toString(),
            "USER", account.name(),
            "LOGNAME", account.name(),
            "SHELL", account.shell().toString(),
            "LANG", utf8(hostLang));
  }

  /** The door for the account this process runs as, in this process's locale. */
  static LocalPtyAccess here() throws IOException {
    return new LocalPtyAccess(Account.current(), System.getenv("LANG"));
  }

  Account account() {
    return account;
  }

  private static String utf8(String lang) {
    var named = Strings.isBlank(lang) ? "" : lang.toUpperCase(Locale.ROOT).replace("-", "");
    return named.endsWith(".UTF8") ? lang : FALLBACK_LANG;
  }

  @Override
  public PtyIdentity.Person resolve(String token) throws IOException {
    if (Strings.isNotBlank(token)) {
      throw new IOException(TOKEN_REFUSED);
    }
    return person;
  }

  @Override
  public void box(PtyIdentity.Person who) throws IOException {
    if (Access.decide(who.actor(), Action.OPERATE_BOX, Resources.box(account.name()))
        instanceof Decision.Refused(var refusal)) {
      throw Remedied.io(refusal.message(), refusal.fix());
    }
  }

  @Override
  public void project(String project, PtyIdentity.Person who) throws IOException {
    throw new IOException(FOLDERS_ONLY);
  }

  @Override
  public void room(String room, String project, PtyIdentity.Person who) throws IOException {
    throw new IOException(FOLDERS_ONLY);
  }

  @Override
  public Folder folder(Path named, PtyIdentity.Person who) throws IOException {
    if (!named.isAbsolute()) {
      throw new IOException(
          "The local terminal host opens a session in a folder named by its absolute path, not '"
              + named
              + "'.");
    }
    if (!Files.exists(named)) {
      throw new IOException(
          "There is no folder '" + named + "': name an existing folder to open the session in.");
    }
    if (!Files.isDirectory(named)) {
      throw new IOException(
          "'" + named + "' is not a folder: name the folder to open the session in.");
    }
    if (!Files.isExecutable(named)) {
      throw new IOException("You may not enter the folder '" + named + "': name one you may open.");
    }
    return new Folder(named.toRealPath(), List.of(account.shell().toString(), "-l"), environment);
  }
}
