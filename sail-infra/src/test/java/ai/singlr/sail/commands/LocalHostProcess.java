/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.pty.Account;
import ai.singlr.sail.pty.Locales;
import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code sail _pty-host --local}'s process, for this account at home in {@code args[0]}: the same
 * process shell ({@link PtyHostCommand#serveThisMachine}, {@link PtyHostCommand#untilTerminated}),
 * in a home of the test's own, since the account database's is the account's real one.
 */
final class LocalHostProcess {

  private LocalHostProcess() {}

  public static void main(String[] args) throws Exception {
    var account = Account.current();
    var access =
        new LocalPtyAccess(
            new Account(account.name(), Path.of(args[0]), account.shell()),
            System.getenv(),
            Locales::installed);
    try {
      System.exit(PtyHostCommand.untilTerminated(PtyHostCommand.serveThisMachine(access)));
    } catch (IOException refused) {
      System.err.println(refused.getMessage());
      System.exit(1);
    }
  }
}
