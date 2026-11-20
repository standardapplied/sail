/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The strip and the check themselves, run by a real bash, grep and git over a container's root
 * rebuilt under a temporary directory: the paths a container has are moved under it, {@code
 * runuser} and {@code mountpoint} are stand-ins that run as this account and see no mount, {@code
 * id} says there is a dev user, as a container has one whatever account runs the test, and {@code
 * gh} is one that says it is logged in exactly when its {@code hosts.yml} is there. What each
 * script does with the files is the production text's own.
 */
@EnabledOnOs(OS.LINUX)
class WorkspaceCredentialsTest {

  private static final String TOKEN = "ghp_builderSecret123";

  @TempDir Path root;
  private Path bin;
  private Path planted;

  @BeforeEach
  void aContainerRoot() throws IOException {
    bin = Files.createDirectories(root.resolve("sail-bin"));
    stub("runuser", "shift 3\nexec \"$@\"\n");
    stub("id", "[ \"$*\" = \"-u dev\" ] && echo 1000 && exit 0\nexec /usr/bin/id \"$@\"\n");
    stub("mountpoint", "exit 1\n");
    stub(
        "gh",
        "if [ -e \"$HOME/.config/gh/hosts.yml\" ]; then echo 'Logged in to github.com'; exit 0; fi\n"
            + "echo 'You are not logged into any GitHub hosts.' >&2\nexit 1\n");
    Files.createDirectories(root.resolve("home/dev/.ssh"));
    Files.createDirectories(root.resolve("etc"));
    Files.createDirectories(root.resolve("usr/share"));
    Files.writeString(root.resolve("usr/share/readme"), "nothing secret\n");
  }

  private void stub(String name, String body) throws IOException {
    var file = bin.resolve(name);
    Files.writeString(file, "#!/bin/sh\n" + body);
    file.toFile().setExecutable(true);
  }

  /**
   * How the script ended, and all it said: its answer on stdout, why it could not tell on stderr.
   */
  private record Ran(int exit, String said) {}

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void git(String... args) throws Exception {
    var command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    var process =
        environment(new ProcessBuilder(command))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    process.getOutputStream().close();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), String.join(" ", command));
  }

  private ProcessBuilder environment(ProcessBuilder builder) {
    var env = builder.environment();
    env.put("PATH", (planted == null ? "" : planted + ":") + bin + ":" + System.getenv("PATH"));
    env.put("HOME", root.resolve("home/dev").toString());
    env.put("GIT_CONFIG_SYSTEM", root.resolve("etc/gitconfig").toString());
    return builder;
  }

  /**
   * {@code script} as it would run in the container, with {@code args} after it and {@code stdin}
   * on its input, run over {@link #root} with the stand-ins and the moved git config.
   */
  private Ran script(String script, String stdin, String... args) throws Exception {
    var rooted =
        script
            .replace("for d in /* /.[!.]*; do", "for d in " + root + "/*; do")
            .replace("/home/*", root + "/home/*")
            .replace("/home/dev", root + "/home/dev")
            .replace("/root/", root + "/root/")
            .replace("-o dev -g dev ", "")
            .replace("PATH=/usr/bin:/bin", "PATH=" + bin + ":/usr/bin:/bin");
    var command = new ArrayList<>(List.of("bash", "-c", rooted, "sail-test"));
    command.addAll(List.of(args));
    var said = Files.createTempFile(bin, "said", ".txt");
    var process =
        environment(new ProcessBuilder(command))
            .redirectErrorStream(true)
            .redirectOutput(said.toFile())
            .start();
    try (var in = process.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    }
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the script ends");
    return new Ran(process.exitValue(), Files.readString(said));
  }

  private void builderLeftEverything() throws Exception {
    Files.writeString(
        root.resolve("home/dev/.git-credentials"), "https://oauth2:" + TOKEN + "@github.com\n");
    Files.createDirectories(root.resolve("home/dev/.config/gh"));
    Files.writeString(
        root.resolve("home/dev/.config/gh/hosts.yml"),
        "github.com:\n  oauth_token: " + TOKEN + "\n");
    Files.writeString(root.resolve("home/dev/.ssh/authorized_keys"), "ssh-ed25519 AAAAbuilder b\n");
    git("config", "--global", "user.name", "Builder");
    git("config", "--global", "user.email", "builder@sail.invalid");
  }

  /**
   * A template's build cut short before its dev user was made (step 6) is stripped all the same:
   * there is no git identity of anyone's to remove, and nothing asks for a user that is not there.
   */
  @Test
  void theStripOfATemplateWhoseDevUserWasNeverMadeEndsCleanly() throws Exception {
    stub("id", "exit 1\n");
    stub("runuser", "echo 'runuser: user dev does not exist' >&2\nexit 1\n");
    Files.writeString(
        root.resolve("home/dev/.git-credentials"), "https://oauth2:" + TOKEN + "@github.com\n");

    var stripped = script(WorkspaceCredentials.STRIP, "");

    assertEquals(0, stripped.exit(), stripped.said());
    assertFalse(Files.exists(root.resolve("home/dev/.git-credentials")));
  }

  /** The strip takes all four out, and the check then finds nothing. */
  @Test
  void theStripTakesTheBuildersTokenGhLoginIdentityAndKeysOutAndTheCheckFindsNone()
      throws Exception {
    builderLeftEverything();

    assertEquals(0, script(WorkspaceCredentials.STRIP, "").exit());
    var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");

    assertEquals(0, checked.exit(), checked.said());
    assertFalse(Files.exists(root.resolve("home/dev/.git-credentials")));
    assertFalse(Files.exists(root.resolve("home/dev/.config/gh/hosts.yml")));
    assertEquals("", Files.readString(root.resolve("home/dev/.ssh/authorized_keys")));
    assertFalse(Files.readString(root.resolve("home/dev/.gitconfig")).contains("email"));
  }

  /**
   * F4: a token change killed between its write and its rename, or a gh login cut short, leaves the
   * previous owner's token beside the real files; the strip of a re-own takes those too.
   */
  @Test
  void theStripTakesWhatATokenChangeCutShortLeftBeside() throws Exception {
    Files.writeString(
        root.resolve("home/dev/.git-credentials.sail-new"),
        "https://oauth2:" + TOKEN + "@github.com\n");
    Files.createDirectories(root.resolve("home/dev/.sail"));
    Files.writeString(root.resolve("home/dev/.sail/gh-token"), TOKEN + "\n");

    assertEquals(0, script(WorkspaceCredentials.STRIP, "", "Ana", "ana@sail.invalid").exit());

    assertFalse(Files.exists(root.resolve("home/dev/.git-credentials.sail-new")));
    assertFalse(Files.exists(root.resolve("home/dev/.sail/gh-token")));
    try (var files = Files.walk(root.resolve("home"))) {
      assertEquals(
          List.of(),
          files
              .filter(Files::isRegularFile)
              .filter(file -> read(file).contains(TOKEN))
              .map(Path::toString)
              .toList());
    }
  }

  /**
   * The proof's High 1: the strip is the same idempotent act whether none, some or all of what a
   * give writes is there, and it checks by what is left: no file of {@link
   * WorkspaceCredentials#FILES}, no identity and no key, every time, with exit 0.
   */
  @Test
  void theStripLeavesNothingWhetherNoneSomeOrAllOfWhatAGiveWritesIsThere() throws Exception {
    assertEquals(0, script(WorkspaceCredentials.STRIP, "").exit(), "none there");
    Files.createDirectories(root.resolve("home/dev/.config/gh"));
    Files.writeString(root.resolve("home/dev/.config/gh/hosts.yml"), "github.com: {}\n");
    git("config", "--global", "user.email", "old@sail.invalid");
    var some = script(WorkspaceCredentials.STRIP, "");
    builderLeftEverything();
    Files.writeString(root.resolve("home/dev/.git-credentials.sail-new"), TOKEN + "\n");
    var all = script(WorkspaceCredentials.STRIP, "");

    assertEquals(0, some.exit(), some.said());
    assertEquals(0, all.exit(), all.said());
    for (var file : WorkspaceCredentials.FILES) {
      assertFalse(Files.exists(root.resolve(file.substring(1))), file);
    }
    assertEquals("", Files.readString(root.resolve("home/dev/.ssh/authorized_keys")));
    assertFalse(Files.readString(root.resolve("home/dev/.gitconfig")).contains("user"));
  }

  /** What a person signed in to each harness with, and an agent session's login file. */
  private void harnessesSignedIn() throws Exception {
    for (var file : WorkspaceCredentials.HARNESS_LOGINS) {
      var at = root.resolve(file.substring(1));
      Files.createDirectories(at.getParent());
      Files.writeString(at, "{\"approved\": [\"" + TOKEN + "\"]}\n");
    }
    var login =
        root.resolve(SessionLogin.file("0f0e0d0c-0000-7000-8000-000000000000").substring(1));
    Files.createDirectories(login.getParent());
    Files.writeString(login, "CODEX_API_KEY=" + TOKEN + "\n");
  }

  /**
   * M1: a strip, what every re-own, disown and template build runs as a workspace leaves its
   * person, takes every file a harness keeps a person's login in, or a part of one, and every agent
   * session's login file, and the check after it finds none of them.
   */
  @Test
  void theStripTakesWhatAPersonSignedInToEachHarnessWithAndEverySessionsLogin() throws Exception {
    harnessesSignedIn();

    var stripped = script(WorkspaceCredentials.STRIP, "");

    assertEquals(0, stripped.exit(), stripped.said());
    for (var file : WorkspaceCredentials.HARNESS_LOGINS) {
      assertFalse(Files.exists(root.resolve(file.substring(1))), file);
    }
    assertFalse(Files.exists(root.resolve(WorkspaceCredentials.SESSIONS.substring(1))));
    assertEquals(
        List.of(
            "/home/dev/.claude/.credentials.json",
            "/home/dev/.claude.json",
            "/home/dev/.codex/auth.json"),
        WorkspaceCredentials.HARNESS_LOGINS);
  }

  /**
   * A give to the workspace's own person at its start keeps what that person signed in to their
   * harnesses with, and takes every agent session's login file, since no session outlives a stop.
   */
  @Test
  void aGiveToItsOwnPersonKeepsTheirHarnessSignInsAndTakesEverySessionsLogin() throws Exception {
    harnessesSignedIn();

    var given = script(WorkspaceCredentials.STRIP, "", "Ana", "ana@sail.invalid");

    assertEquals(0, given.exit(), given.said());
    for (var file : WorkspaceCredentials.HARNESS_LOGINS) {
      assertTrue(Files.exists(root.resolve(file.substring(1))), file);
    }
    assertFalse(Files.exists(root.resolve(WorkspaceCredentials.SESSIONS.substring(1))));
  }

  /** A strip whose harness file could not be removed fails, by what is left, never silently. */
  @Test
  void aHarnessLoginTheStripCannotRemoveFailsIt() throws Exception {
    var held = root.resolve("home/dev/.codex/auth.json");
    Files.createDirectories(held);
    Files.writeString(held.resolve("inside"), TOKEN);

    var stripped = script(WorkspaceCredentials.STRIP, "");

    assertEquals(1, stripped.exit(), stripped.said());
    assertTrue(stripped.said().contains(".codex/auth.json"), stripped.said());
  }

  /** The strip given a name and email leaves them as the identity, and nothing else of anyone. */
  @Test
  void theStripGivenAnOwnersNameAndEmailSetsThemAsTheIdentity() throws Exception {
    builderLeftEverything();

    assertEquals(0, script(WorkspaceCredentials.STRIP, "", "Ana", "ana@sail.invalid").exit());

    var gitconfig = Files.readString(root.resolve("home/dev/.gitconfig"));
    assertTrue(gitconfig.contains("ana@sail.invalid"), gitconfig);
    assertFalse(gitconfig.contains("builder@sail.invalid"), gitconfig);
  }

  /** (a) The token's bytes anywhere on the root filesystem fail the check, wherever they hide. */
  @Test
  void aTokenAnywhereFailsTheCheckWhereverItHides() throws Exception {
    for (var hidden :
        List.of(
            "home/dev/.netrc",
            "home/dev/.sail/gh-token-01a1",
            "home/dev/workspace/app/.git/config",
            "etc/environment")) {
      var file = root.resolve(hidden);
      Files.createDirectories(file.getParent());
      Files.writeString(file, "url = https://oauth2:" + TOKEN + "@github.com/acme/app.git\n");

      var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");

      assertEquals(1, checked.exit(), hidden + ": " + checked.said());
      assertTrue(checked.said().contains(hidden), checked.said());
      assertFalse(checked.said().contains(TOKEN), "the check never says the token");
      Files.delete(file);
    }
  }

  /** (b) A git identity at the system or the global level fails the check. */
  @Test
  void aGitIdentityAtTheSystemOrGlobalLevelFailsTheCheck() throws Exception {
    git("config", "--system", "user.email", "root@box");
    var system = script(WorkspaceCredentials.HELD, "");
    Files.delete(root.resolve("etc/gitconfig"));
    git("config", "--global", "user.name", "Builder");
    var global = script(WorkspaceCredentials.HELD, "");

    assertEquals(1, system.exit(), system.said());
    assertTrue(system.said().contains("a system git user.email"), system.said());
    assertEquals(1, global.exit(), global.said());
    assertTrue(global.said().contains("a global git user.name"), global.said());
  }

  /** (c) A line in any home's authorized_keys fails the check. */
  @Test
  void aKeyInAnyHomeFailsTheCheck() throws Exception {
    Files.createDirectories(root.resolve("home/ubuntu/.ssh"));
    Files.writeString(root.resolve("home/ubuntu/.ssh/authorized_keys"), "ssh-ed25519 AAAAx x\n");

    var checked = script(WorkspaceCredentials.HELD, "");

    assertEquals(1, checked.exit(), checked.said());
    assertTrue(checked.said().contains("home/ubuntu/.ssh/authorized_keys"), checked.said());
  }

  /** (d) A gh login fails the check. */
  @Test
  void aGhLoginFailsTheCheck() throws Exception {
    Files.createDirectories(root.resolve("home/dev/.config/gh"));
    Files.writeString(root.resolve("home/dev/.config/gh/hosts.yml"), "github.com: {}\n");

    var checked = script(WorkspaceCredentials.HELD, "");

    assertEquals(1, checked.exit(), checked.said());
    assertTrue(checked.said().contains("a gh login"), checked.said());
  }

  /**
   * A git config git cannot read is never "no identity": the check fails as one that cannot tell.
   */
  @Test
  void aGitConfigThatCannotBeReadFailsTheCheckAsOneThatCannotTell() throws Exception {
    Files.writeString(root.resolve("home/dev/.gitconfig"), "[user\n\temail = uday@x\n");

    var checked = script(WorkspaceCredentials.HELD, "");

    assertEquals(2, checked.exit(), checked.said());
    assertTrue(checked.said().contains("git could not read"), checked.said());
  }

  /**
   * The search's own failures are never "no match": an empty token, which would match every file,
   * and a grep that errors both fail the check as one that cannot tell.
   */
  @Test
  void anEmptyTokenAndAGrepThatErrorsFailTheCheckAsOneThatCannotTell() throws Exception {
    var empty = script(WorkspaceCredentials.HELD, "\n" + TOKEN + "\n");
    var realGrep =
        List.of(Path.of("/usr/bin/grep"), Path.of("/bin/grep")).stream()
            .filter(Files::isExecutable)
            .findFirst()
            .orElseThrow();
    stub(
        "grep",
        "case \" $* \" in *\" -r \"*) echo 'grep: a file: Input/output error' >&2; exit 2 ;; esac\n"
            + "exec "
            + realGrep
            + " \"$@\"\n");
    var broken = script(WorkspaceCredentials.HELD, TOKEN + "\n");

    assertEquals(2, empty.exit(), empty.said());
    assertTrue(empty.said().contains("an empty token matches every file"), empty.said());
    assertEquals(2, broken.exit(), broken.said());
    assertTrue(broken.said().contains("the search for the build's tokens failed"), broken.said());
  }

  /** A token in many files names three of them and how many more, never a list without end. */
  @Test
  void aTokenInManyFilesNamesThreeAndCountsTheRest() throws Exception {
    var dir = Files.createDirectories(root.resolve("srv/copies"));
    for (var i = 0; i < 5; i++) {
      Files.writeString(dir.resolve("copy " + i), TOKEN + "\n");
    }

    var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");

    assertEquals(1, checked.exit(), checked.said());
    assertTrue(checked.said().contains("copy "), checked.said());
    assertTrue(checked.said().strip().endsWith("and 2 more"), checked.said());
    assertFalse(checked.said().contains(TOKEN), checked.said());
  }

  /** A clean container passes, with no token given or with the build's. */
  @Test
  void aCleanContainerPasses() throws Exception {
    assertEquals(0, script(WorkspaceCredentials.HELD, "").exit());
    assertEquals(0, script(WorkspaceCredentials.HELD, TOKEN + "\n").exit());
  }

  private static final String VANISHED = "grep: /tmp/.XIM-unix: No such file or directory\n";
  private static final String DENIED = "grep: /root/.cache/x: Permission denied\n";

  private static Path realGrep() {
    return List.of(Path.of("/usr/bin/grep"), Path.of("/bin/grep")).stream()
        .filter(Files::isExecutable)
        .findFirst()
        .orElseThrow();
  }

  /**
   * A grep whose search runs for real and then also says {@code errors} and ends {@code 2}, as one
   * does when a file it listed is gone, or cannot be read, by the time it opens it; it writes down
   * the locale it ran in. Every other grep is the real one.
   */
  private void grepThatAlsoSays(String errors) throws IOException {
    stub(
        "grep",
        "case \" $* \" in *\" -r \"*) ;; *) exec "
            + realGrep()
            + " \"$@\" ;; esac\n"
            + "echo \"${LC_ALL:-unset}\" > "
            + bin.resolve("grep-locale")
            + "\n"
            + realGrep()
            + " \"$@\"\n"
            + "[ $? -le 1 ] || exit 2\n"
            + "printf '%s' '"
            + errors
            + "' >&2\n"
            + "exit 2\n");
  }

  /** An mktemp that writes down where it made its file and with what mode, then answers it. */
  private void mktempWrittenDown() throws IOException {
    stub(
        "mktemp",
        "made=$(/usr/bin/mktemp \"$@\") || exit $?\n"
            + "echo \"$made $(stat -c %a \"$made\") $*\" > "
            + bin.resolve("made")
            + "\necho \"$made\"\n");
  }

  /**
   * A file the search listed but found gone when it opened it holds nothing: a running template's
   * boot removes some of its own (as {@code /tmp/.XIM-unix}), so the check passes. Its errors are
   * kept in a file of the search's own, {@code 0600} on {@code /dev/shm}, gone after, and the
   * search runs in the C locale, so the words it is told apart by are never translated.
   */
  @Test
  void aFileGoneBetweenTheListingAndTheOpenHoldsNothing() throws Exception {
    grepThatAlsoSays(VANISHED);
    mktempWrittenDown();

    var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");

    assertEquals(0, checked.exit(), checked.said());
    assertEquals("C\n", read(bin.resolve("grep-locale")));
    var made = read(bin.resolve("made")).strip().split(" ", 3);
    assertTrue(made[0].startsWith("/dev/shm/"), made[0]);
    assertEquals("600", made[1]);
    assertFalse(Files.exists(Path.of(made[0])), "the search's error file is gone");
  }

  /**
   * Any error but a file gone fails the check as one that cannot tell, saying what grep said: a
   * file it cannot read, the two together, or an error it does not say.
   */
  @Test
  void aFileTheSearchCannotReadFailsTheCheckAloneOrBesideAFileGone() throws Exception {
    mktempWrittenDown();
    for (var errors : List.of(DENIED, VANISHED + DENIED, "")) {
      grepThatAlsoSays(errors);

      var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");

      assertEquals(2, checked.exit(), errors + ": " + checked.said());
      assertTrue(
          checked.said().contains("the search for the build's tokens failed"), checked.said());
      assertTrue(checked.said().contains(errors.isEmpty() ? "said nothing" : "Permission denied"));
      assertFalse(
          Files.exists(Path.of(read(bin.resolve("made")).split(" ")[0])),
          "the search's error file is gone");
    }
  }

  /** A token is found where it is, whatever else vanished meanwhile. */
  @Test
  void aTokenIsFoundBesideAFileGone() throws Exception {
    grepThatAlsoSays(VANISHED);
    var file = Files.createDirectories(root.resolve("home/dev/.sail")).resolve("gh-token-01");
    Files.writeString(file, TOKEN + "\n");

    var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");

    assertEquals(1, checked.exit(), checked.said());
    assertTrue(checked.said().contains("home/dev/.sail/gh-token-01"), checked.said());
    assertFalse(checked.said().contains(TOKEN), "the check never says the token");
  }

  /** With nowhere to keep its errors, the search does not run, and the check says why. */
  @Test
  void aSearchWithNowhereToKeepItsErrorsFailsTheCheckSayingSo() throws Exception {
    stub(
        "mktemp",
        "echo \"mktemp: failed to create file via template '/dev/shm/sail-held.XXXXXX': Read-only"
            + " file system\" >&2\nexit 1\n");

    var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");

    assertEquals(2, checked.exit(), checked.said());
    assertTrue(
        checked
            .said()
            .contains(
                "the search for the build's tokens did not run: it could not make a file on"
                    + " /dev/shm for its errors"),
        checked.said());
  }

  /**
   * A file a search cannot read is refused whatever it is named: one named for the error a file
   * gone gives, and holding the token, is still an error that is not that one.
   */
  @Test
  void aFileNamedForAFileGoneThatCannotBeReadIsStillRefused() throws Exception {
    Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root reads it");
    var named =
        Files.createDirectories(root.resolve("srv")).resolve("a: No such file or directory");
    Files.writeString(named, TOKEN + "\n");
    Files.setPosixFilePermissions(named, java.util.Set.of());

    var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");

    assertEquals(2, checked.exit(), checked.said());
    assertTrue(checked.said().contains("Permission denied"), checked.said());
    assertFalse(checked.said().contains(TOKEN), "the check never says the token");
  }

  /**
   * A grep, or any tool the check runs, planted earlier on the path inside the template answers
   * nothing: the check and the strip run with their own path.
   */
  @Test
  void aToolPlantedEarlierOnThePathAnswersForNeitherScript() throws Exception {
    planted = Files.createDirectories(root.resolve("usr/local/bin"));
    var used = root.resolve("planted-used");
    var grep = planted.resolve("grep");
    Files.writeString(grep, "#!/bin/sh\ntouch " + used + "\nexit 1\n");
    grep.toFile().setExecutable(true);
    Files.copy(grep, planted.resolve("rm"));
    planted.resolve("rm").toFile().setExecutable(true);
    Files.writeString(root.resolve("etc/environment"), TOKEN + "\n");

    var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");
    builderLeftEverything();
    var stripped = script(WorkspaceCredentials.STRIP, "");

    assertEquals(1, checked.exit(), checked.said());
    assertTrue(checked.said().contains("etc/environment"), checked.said());
    assertEquals(0, stripped.exit(), stripped.said());
    assertFalse(Files.exists(root.resolve("home/dev/.git-credentials")));
    assertFalse(Files.exists(used), "the planted tools never ran");
  }

  /**
   * A template is searched once systemd says its boot is done, running or degraded, waited for
   * within a bound; any other state, or none, is said as it is.
   */
  @Test
  void aTemplateIsSettledOnceItsBootIsDoneRunningOrDegraded() throws Exception {
    stub("timeout", "echo \"$1\" > " + bin.resolve("bound") + "\nshift\nexec \"$@\"\n");
    var said = new ArrayList<String>();
    for (var state : List.of("running:0", "degraded:1", "starting:1", ":1")) {
      var parts = state.split(":", -1);
      stub(
          "systemctl",
          "[ \"$*\" = \"is-system-running --wait\" ] || exit 9\n"
              + (parts[0].isEmpty() ? "" : "echo " + parts[0] + "\n")
              + "exit "
              + parts[1]
              + "\n");
      var ran = script(WorkspaceCredentials.SETTLED, "");
      said.add(ran.exit() + " " + ran.said().strip());
    }

    assertEquals(List.of("0 ", "0 ", "1 starting", "1 no state"), said);
    assertEquals("120\n", read(bin.resolve("bound")));
  }
}
