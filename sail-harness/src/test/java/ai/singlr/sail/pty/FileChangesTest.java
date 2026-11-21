/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.PtyMessage.Change;
import ai.singlr.sail.pty.PtyMessage.ChangeState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * What changed since {@code HEAD}, said by the workspace's own git against repositories made here:
 * every state, names as bytes, ignored paths as git matched them, repositories found, the cap, the
 * index's lock never taken, one run per workspace however many ask, and a file's diff with its
 * refusals. Git, bash 5 and GNU coreutils, as a workspace has, so Linux alone.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FileChangesTest {

  static final String CONTAINER = "acme-dock";

  @TempDir Path dir;
  Path home;
  Path workspace;
  Map<String, String> env;
  final List<List<String>> commands = new CopyOnWriteArrayList<>();
  final LinkedBlockingQueue<PtyMessage> said = new LinkedBlockingQueue<>();

  @BeforeEach
  void setUp() throws IOException {
    home = Files.createDirectory(dir.toRealPath().resolve("home"));
    workspace = Files.createDirectory(home.resolve("workspace"));
    var excludes = Files.writeString(dir.resolve("excludes"), "*.swp\n");
    var config =
        Files.writeString(
            dir.resolve("gitconfig"),
            "[core]\n\texcludesFile = "
                + excludes
                + "\n[init]\n\tdefaultBranch = main\n[user]\n\tname = t\n\temail = t@t\n");
    env = Map.of("GIT_CONFIG_GLOBAL", config.toString(), "GIT_CONFIG_NOSYSTEM", "1");
  }

  private FileScripts scripts(UnaryOperator<String> edit) {
    return FileScriptsTest.over(home, commands, env, edit);
  }

  private WorkspaceChanges changes(UnaryOperator<String> edit, Duration patience) {
    return new WorkspaceChanges(scripts(edit), CONTAINER, patience, () -> {});
  }

  /** One run's answer, asked of a workspace's changes alone. */
  private WorkspaceChanges.Answer asked(WorkspaceChanges changes) throws Exception {
    var answer = new CompletableFuture<WorkspaceChanges.Answer>();
    changes.ask(
        new WorkspaceChanges.Waiter() {
          @Override
          public boolean gone() {
            return false;
          }

          @Override
          public void answered(WorkspaceChanges.Answer made) {
            answer.complete(made);
          }
        });
    return answer.get(60, TimeUnit.SECONDS);
  }

  private WorkspaceChanges.Answer asked() throws Exception {
    return asked(changes(UnaryOperator.identity(), WorkspaceChanges.PATIENCE));
  }

  /** Every entry of {@code answer} by its path. */
  private static Map<String, Change> entries(WorkspaceChanges.Answer answer) {
    var all = new HashMap<String, Change>();
    answer
        .parts()
        .forEach(part -> part.entries().forEach(change -> all.put(change.path(), change)));
    return all;
  }

  private static Set<String> ignored(WorkspaceChanges.Answer answer) {
    var all = new HashSet<String>();
    answer.parts().forEach(part -> all.addAll(part.ignored()));
    return all;
  }

  /** Runs git in {@code at} as the workspace's user would, answering its exit status. */
  private int gitStatus(Path at, String... args) throws Exception {
    var command = new ArrayList<>(List.of("git", "-C", at.toString()));
    command.addAll(List.of(args));
    var builder =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("git.out").toFile());
    builder.environment().putAll(env);
    return builder.start().waitFor();
  }

  private void git(Path at, String... args) throws Exception {
    assertEquals(0, gitStatus(at, args), () -> "git " + String.join(" ", args) + ": " + out());
  }

  private String out() {
    try {
      return Files.readString(dir.resolve("git.out"));
    } catch (IOException unread) {
      return "";
    }
  }

  /** Makes {@code name} in {@code folder} through the shell, so its bytes need not be UTF-8. */
  private static void touch(Path folder, byte[] name) throws Exception {
    var hex = new StringBuilder();
    for (var b : name) {
      hex.append(String.format("\\x%02x", b & 0xff));
    }
    var made =
        new ProcessBuilder(
                "bash",
                "-c",
                "cd \"$1\" && : > \"$(printf \"$2\")\"",
                "touch",
                folder.toString(),
                hex.toString())
            .start()
            .waitFor();
    assertEquals(0, made);
  }

  @Test
  void everyStateIsGitsOwnAndANameIsItsBytes() throws Exception {
    git(workspace, "init", "-q");
    for (var name : List.of("a.md", "b.md", "c.md", "h.md")) {
      Files.writeString(workspace.resolve(name), name + "\nline two\n");
    }
    git(workspace, "add", ".");
    git(workspace, "commit", "-qm", "base");
    git(workspace, "checkout", "-qb", "other");
    Files.writeString(workspace.resolve("h.md"), "theirs\n");
    git(workspace, "commit", "-qam", "theirs");
    git(workspace, "checkout", "-q", "main");
    Files.writeString(workspace.resolve("h.md"), "mine\n");
    git(workspace, "commit", "-qam", "mine");
    assertEquals(1, gitStatus(workspace, "merge", "-q", "other"), "the merge conflicts");
    Files.writeString(workspace.resolve("a.md"), "changed\n");
    Files.delete(workspace.resolve("b.md"));
    git(workspace, "mv", "c.md", "c2.md");
    Files.writeString(workspace.resolve("f.md"), "new\n");
    git(workspace, "add", "f.md");
    Files.writeString(workspace.resolve("g.md"), "untracked\n");
    Files.writeString(workspace.resolve("with space\nand newline"), "odd\n");
    var bad = new byte[] {'b', 'a', 'd', (byte) 0xff, '.', 'm', 'd'};
    touch(workspace, bad);

    var answer = asked();

    assertEquals(0, answer.status());
    var root = workspace.toString();
    var expected = new HashMap<String, Change>();
    for (var change :
        List.of(
            new Change(root + "/a.md", ChangeState.MODIFIED, ""),
            new Change(root + "/b.md", ChangeState.DELETED, ""),
            new Change(root + "/c2.md", ChangeState.RENAMED, root + "/c.md"),
            new Change(root + "/f.md", ChangeState.ADDED, ""),
            new Change(root + "/g.md", ChangeState.UNTRACKED, ""),
            new Change(root + "/h.md", ChangeState.CONFLICTED, ""),
            new Change(root + "/with space\nand newline", ChangeState.UNTRACKED, ""),
            new Change(root + "/" + FileNames.decode(bad), ChangeState.UNTRACKED, ""))) {
      expected.put(change.path(), change);
    }
    assertEquals(expected, entries(answer));
    assertEquals(1, answer.parts().size());
    assertEquals(root, answer.parts().getFirst().root());
    assertEquals("main", answer.parts().getFirst().branch());
    assertFalse(answer.parts().getFirst().truncated());
    assertTrue(answer.tookMillis() >= 0);
    assertTrue(
        commands.stream().flatMap(List::stream).noneMatch(word -> word.contains("a.md")),
        "no path reaches a command line (F9)");
  }

  /** M41: in a repository with no commit yet, a staged file is added and the rest untracked. */
  @Test
  void aRepositoryWithNoCommitListsItsFilesAsAdded() throws Exception {
    git(workspace, "init", "-q");
    Files.writeString(workspace.resolve("staged.md"), "x\n");
    Files.writeString(workspace.resolve("loose.md"), "y\n");
    git(workspace, "add", "staged.md");

    var answer = asked();

    assertEquals(
        Map.of(
            workspace + "/staged.md",
            new Change(workspace + "/staged.md", ChangeState.ADDED, ""),
            workspace + "/loose.md",
            new Change(workspace + "/loose.md", ChangeState.UNTRACKED, "")),
        entries(answer));
    assertEquals("main", answer.parts().getFirst().branch());
  }

  /**
   * M62: ignored paths as git matched them: a file a rule names, a folder a rule names as one path
   * with nothing beneath it, a negated rule, a nested rule and the person's global excludes.
   */
  @Test
  void ignoredPathsAreGitsOwnAndAnIgnoredFolderIsOnePath() throws Exception {
    git(workspace, "init", "-q");
    Files.writeString(workspace.resolve(".gitignore"), "node_modules/\n*.log\n!keep.log\n");
    git(workspace, "add", ".gitignore");
    git(workspace, "commit", "-qm", "rules");
    var modules = Files.createDirectories(workspace.resolve("node_modules/pkg/lib"));
    for (var i = 0; i < 200; i++) {
      Files.writeString(modules.resolve("m" + i + ".js"), "x");
    }
    Files.writeString(workspace.resolve("build.log"), "x");
    Files.writeString(workspace.resolve("keep.log"), "x");
    var sub = Files.createDirectory(workspace.resolve("sub"));
    Files.writeString(sub.resolve(".gitignore"), "secret.txt\n");
    Files.writeString(sub.resolve("secret.txt"), "x");
    Files.writeString(workspace.resolve("notes.swp"), "x");

    var answer = asked();

    var root = workspace.toString();
    assertEquals(
        Set.of(
            root + "/node_modules",
            root + "/build.log",
            root + "/sub/secret.txt",
            root + "/notes.swp"),
        ignored(answer));
    assertEquals(Set.of(root + "/keep.log", root + "/sub/.gitignore"), entries(answer).keySet());
  }

  /** Repositories are found: each folder under the workspace that is its own top, none other. */
  @Test
  void eachRepositoryUnderTheWorkspaceIsAnsweredAndAPlainFolderIsNone() throws Exception {
    for (var name : List.of("api", "web")) {
      var repo = Files.createDirectory(workspace.resolve(name));
      git(repo, "init", "-q");
      Files.writeString(repo.resolve(name + ".md"), "x");
    }
    Files.createDirectories(workspace.resolve("docs/deep"));
    var nested = Files.createDirectories(workspace.resolve("docs/deep/repo"));
    git(nested, "init", "-q");
    var outside = Files.createDirectory(dir.resolve("outside"));
    git(outside, "init", "-q");
    Files.createSymbolicLink(workspace.resolve("away"), outside);

    var answer = asked();

    assertEquals(
        List.of(workspace + "/api", workspace + "/web"),
        answer.parts().stream().map(WorkspaceChanges.Part::root).toList());
    assertEquals(
        Set.of(workspace + "/api/api.md", workspace + "/web/web.md"), entries(answer).keySet());
  }

  /** M40: no repository, and no git, are each their own answer. */
  @Test
  void noRepositoryAndNoGitAreSaidAsWhatTheyAre() throws Exception {
    Files.writeString(workspace.resolve("plain.md"), "x");
    assertEquals(FileScripts.NO_REPOSITORY, asked().status());

    var bin = Files.createDirectory(dir.resolve("bin"));
    for (var tool : List.of("bash", "python3")) {
      Files.createSymbolicLink(bin.resolve(tool), Path.of("/usr/bin", tool).toRealPath());
    }
    var gitless = new HashMap<>(env);
    gitless.put("PATH", bin.toString());
    var withoutGit =
        new WorkspaceChanges(
            FileScriptsTest.over(home, commands, gitless),
            CONTAINER,
            WorkspaceChanges.PATIENCE,
            () -> {});
    git(workspace, "init", "-q");
    assertEquals(FileScripts.NO_GIT, asked(withoutGit).status());
  }

  /** More than 5,000 untracked files: 5,000 of them, and said cut. */
  @Test
  void moreThanFiveThousandUntrackedFilesAreCutAtFiveThousand() throws Exception {
    git(workspace, "init", "-q");
    var many = Files.createDirectory(workspace.resolve("many"));
    for (var i = 0; i < 5_001; i++) {
      Files.writeString(many.resolve("f" + i), "");
    }

    var answer = asked();

    assertEquals(PtyMessage.CHANGES_CAP, entries(answer).size());
    assertTrue(answer.parts().stream().allMatch(WorkspaceChanges.Part::truncated));
  }

  /** An answer holds at most its bytes of paths: past them its lists are cut and say so. */
  @Test
  void anAnswerPastItsBytesIsCutAndSaysSo() throws Exception {
    git(workspace, "init", "-q");
    var deep = Files.createDirectory(workspace.resolve("l".repeat(200)));
    for (var i = 0; i < 4_000; i++) {
      Files.writeString(deep.resolve(String.format("%04d", i) + "n".repeat(240)), "");
    }

    var answer = asked();

    var kept = entries(answer);
    assertTrue(kept.size() < 4_000, kept.size() + " kept");
    assertTrue(answer.parts().stream().allMatch(WorkspaceChanges.Part::truncated));
    var bytes = kept.keySet().stream().mapToLong(path -> path.length() * 3L + 16).sum();
    assertTrue(bytes <= WorkspaceChanges.ANSWER_BYTES, bytes + " bytes");
  }

  /** A repository's changes are cut into frames within the listing's bytes, none lost. */
  @Test
  void aRepositorysChangesAreFramedWithinTheListingsBytes() {
    var entries = new ArrayList<Change>();
    var ignored = new ArrayList<String>();
    for (var i = 0; i < 3_000; i++) {
      entries.add(new Change("/r/" + "e".repeat(200) + i, ChangeState.MODIFIED, ""));
      ignored.add("/r/" + "i".repeat(200) + i);
    }
    var parts = WorkspaceChanges.pieces("/r", "main", entries, ignored, true);
    assertTrue(parts.size() > 2, parts.size() + " parts");
    for (var part : parts) {
      var bytes =
          part.entries().stream().mapToLong(c -> c.path().length() * 3L + 16).sum()
              + part.ignored().stream().mapToLong(p -> p.length() * 3L + 16).sum();
      assertTrue(bytes < FilesConnection.LISTING_BYTES + 1_000, bytes + " bytes");
      assertTrue(part.truncated());
      assertEquals("/r", part.root());
    }
    assertEquals(entries, parts.stream().flatMap(p -> p.entries().stream()).toList());
    assertEquals(ignored, parts.stream().flatMap(p -> p.ignored().stream()).toList());
    assertEquals(1, WorkspaceChanges.pieces("/r", "main", List.of(), List.of(), false).size());
  }

  /**
   * M43: asking never takes the index's lock. A file touched but unchanged leaves the index stale,
   * which a status with optional locks on would refresh and write; and 200 commits made while the
   * changes are asked over and over all succeed.
   */
  @Test
  void theChangesNeverTakeTheIndexsLockAndAnAgentsCommitsNeverWait() throws Exception {
    git(workspace, "init", "-q");
    var file = Files.writeString(workspace.resolve("work.md"), "0\n");
    git(workspace, "add", ".");
    git(workspace, "commit", "-qm", "0");
    Files.setLastModifiedTime(
        file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
    var index = workspace.resolve(".git/index");
    var before = Files.readAllBytes(index);
    var beforeTime = Files.getLastModifiedTime(index);

    assertEquals(0, asked().status());
    assertArrayEquals(before, Files.readAllBytes(index), "the index is never written");
    assertEquals(beforeTime, Files.getLastModifiedTime(index));

    var changes = changes(UnaryOperator.identity(), WorkspaceChanges.PATIENCE);
    var done = new AtomicBoolean();
    var runs = new java.util.concurrent.atomic.AtomicInteger();
    var failures = new CopyOnWriteArrayList<String>();
    var asking =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (!done.get()) {
                    try {
                      var status = asked(changes).status();
                      if (status != 0) {
                        failures.add("status " + status);
                      }
                      runs.incrementAndGet();
                    } catch (Exception failed) {
                      failures.add(failed.toString());
                    }
                  }
                });
    try {
      for (var i = 1; i <= 200; i++) {
        Files.writeString(file, i + "\n");
        git(workspace, "commit", "-qam", "" + i);
      }
    } finally {
      done.set(true);
      asking.join();
    }
    assertEquals(List.of(), failures);
    assertTrue(runs.get() > 1, runs.get() + " runs beside the commits");
  }

  /** One script's output read whole, and its exit status. */
  private record Ran(byte[] bytes, int status) {}

  private Ran diff(Path path, long cap) throws Exception {
    var script =
        scripts(UnaryOperator.identity())
            .start(CONTAINER, FileScripts.DIFF, path.toString(), "" + cap);
    var bytes = new ByteArrayOutputStream();
    for (var chunk = script.bytes(1 << 16); chunk.length > 0; chunk = script.bytes(1 << 16)) {
      bytes.write(chunk);
    }
    return new Ran(bytes.toByteArray(), script.status());
  }

  private static String text(Ran ran) {
    return new String(ran.bytes(), StandardCharsets.UTF_8);
  }

  /**
   * A diff is git's own unified diff with three lines of context, never through a configured
   * external diff or text conversion: of a change, an untracked file, a removed file, and a
   * repository with no commit, whose base git names (SHA-256 here).
   */
  @Test
  void aDiffIsGitsOwnAndNeverAConfiguredTool() throws Exception {
    git(workspace, "init", "-q");
    var lines = new StringBuilder();
    for (var i = 1; i <= 20; i++) {
      lines.append("line ").append(i).append('\n');
    }
    var file = Files.writeString(workspace.resolve("a.txt"), lines);
    Files.writeString(workspace.resolve("gone.txt"), "going\n");
    Files.writeString(workspace.resolve("a.md"), "plain\n");
    Files.writeString(workspace.resolve("[ab].md"), "glob\n");
    Files.writeString(workspace.resolve(".gitattributes"), "*.txt diff=shout\n");
    git(workspace, "add", ".");
    git(workspace, "commit", "-qm", "base");
    git(workspace, "config", "diff.external", "/bin/false");
    git(workspace, "config", "diff.shout.textconv", "tr a-z A-Z <");
    Files.writeString(file, lines.toString().replace("line 10\n", "line ten\n"));
    Files.delete(workspace.resolve("gone.txt"));
    Files.writeString(workspace.resolve("new.txt"), "fresh\n");
    Files.writeString(workspace.resolve("a.md"), "plain changed\n");
    Files.writeString(workspace.resolve("[ab].md"), "glob changed\n");

    var changed = diff(file, 1 << 20);
    assertEquals(0, changed.status());
    var said = text(changed);
    assertTrue(said.contains("@@ -7,7 +7,7 @@"), said);
    assertTrue(said.contains("-line 10\n+line ten\n"), said);
    assertTrue(
        said.contains("\n line 7\n") && !said.contains("\n line 6\n"), "three lines of context");
    assertFalse(said.contains("LINE"), "no text conversion");

    assertTrue(text(diff(workspace.resolve("new.txt"), 1 << 20)).contains("+fresh\n"));
    var glob = text(diff(workspace.resolve("[ab].md"), 1 << 20));
    assertTrue(glob.contains("+glob changed\n") && !glob.contains("plain"), "a path, never a glob");
    assertTrue(text(diff(workspace.resolve("gone.txt"), 1 << 20)).contains("-going\n"));

    var fresh = Files.createDirectory(workspace.resolve("fresh"));
    git(fresh, "init", "-q", "--object-format=sha256");
    Files.writeString(fresh.resolve("first.md"), "one\n");
    git(fresh, "add", ".");
    var first = diff(fresh.resolve("first.md"), 1 << 20);
    assertEquals(0, first.status(), text(first));
    assertTrue(text(first).contains("+one\n"), text(first));
  }

  /** A binary change, a change past the cap, a path in no repository, and one outside the home. */
  @Test
  void aDiffIsRefusedAsWhatItIs() throws Exception {
    git(workspace, "init", "-q");
    var binary = Files.write(workspace.resolve("b.bin"), new byte[] {0, 1, 2});
    var big = Files.writeString(workspace.resolve("big.txt"), "x\n");
    git(workspace, "add", ".");
    git(workspace, "commit", "-qm", "base");
    Files.write(binary, new byte[] {0, 1, 3});
    Files.writeString(big, "y\n".repeat(40));

    assertEquals(FileScripts.BINARY, diff(binary, 1 << 20).status());
    var cut = diff(big, 100);
    assertEquals(0, cut.status());
    assertEquals(101, cut.bytes().length, "one byte past the cap, so the host sees it");
    assertEquals(FileScripts.TOO_LARGE, diff(big, 50).status(), "a side over the cap, at once");

    var plain = Files.createDirectory(home.resolve("plain"));
    assertEquals(
        FileScripts.NO_REPOSITORY,
        diff(Files.writeString(plain.resolve("p.md"), "x"), 100).status());
    var outside = Files.createDirectory(dir.resolve("outside"));
    git(outside, "init", "-q");
    Files.createSymbolicLink(workspace.resolve("away"), outside);
    assertEquals(FileScripts.OUTSIDE_HOME, diff(workspace.resolve("away/x.md"), 100).status());
    assertEquals(
        FileScripts.IS_FOLDER,
        diff(Files.createDirectory(workspace.resolve("folder")), 100).status());
  }

  /** A connection answering on {@link #said}, its changes asked of {@code changes}. */
  private FilesConnection connection(WorkspaceChanges changes, FileScripts with) {
    return new FilesConnection(
        with,
        CONTAINER,
        "dock",
        said::add,
        () -> null,
        unused -> {},
        new FilesConnection.Writes(
            new FilesConnection.WorkspaceLock(),
            ConcurrentHashMap.newKeySet(),
            () -> null,
            FilesConnection.AFTER_GO,
            FilesConnection.HOLD_BOUND),
        changes);
  }

  /** Every frame said until request {@code ids} each had its last, within 30 s. */
  private Map<Integer, List<PtyMessage>> answers(Set<Integer> ids) throws InterruptedException {
    var got = new HashMap<Integer, List<PtyMessage>>();
    var open = new HashSet<>(ids);
    while (!open.isEmpty()) {
      var next = said.poll(30, TimeUnit.SECONDS);
      if (next == null) {
        throw new AssertionError("unanswered: " + open);
      }
      var id =
          switch (next) {
            case PtyMessage.FilesChangeList list -> list.id();
            case PtyMessage.FilesRefused refused -> refused.id();
            case PtyMessage.FilesDiffData data -> data.id();
            default -> throw new AssertionError("unexpected " + next);
          };
      got.computeIfAbsent(id, unused -> new ArrayList<>()).add(next);
      var last =
          switch (next) {
            case PtyMessage.FilesChangeList list -> !list.more();
            case PtyMessage.FilesDiffData data -> data.eof();
            default -> true;
          };
      if (last) {
        open.remove(id);
      }
    }
    return got;
  }

  /**
   * M67: 50 asks from two connections to one workspace, at once, against a slow script: one script
   * is ever running, two run in all, and every ask is answered, each frame saying how long it took.
   */
  @Test
  void fiftyAsksFromTwoConnectionsRunOneScriptAtATimeAndTwoInAll() throws Exception {
    git(workspace, "init", "-q");
    Files.writeString(workspace.resolve("a.md"), "x");
    var log = dir.resolve("runs.log");
    var slow =
        scripts(
            script ->
                script.equals(FileScripts.CHANGES)
                    ? "echo start >> '" + log + "'; sleep 0.5; echo end >> '" + log + "'\n" + script
                    : script);
    var changes = new WorkspaceChanges(slow, CONTAINER, WorkspaceChanges.PATIENCE, () -> {});
    var one = connection(changes, slow);
    var two = connection(changes, slow);
    var ids = new HashSet<Integer>();
    var senders = new ArrayList<Thread>();
    for (var i = 0; i < 50; i++) {
      var id = i;
      ids.add(id);
      senders.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      (id % 2 == 0 ? one : two).changes(new PtyMessage.FilesChanges(id));
                    } catch (InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                    }
                  }));
    }
    for (var sender : senders) {
      sender.join();
    }

    var got = answers(ids);

    assertEquals(ids, got.keySet());
    for (var frames : got.values()) {
      var list = assertInstanceOf(PtyMessage.FilesChangeList.class, frames.getLast());
      assertEquals(workspace + "/a.md", list.entries().getFirst().path());
      assertTrue(list.tookMillis() >= 500, list.tookMillis() + " ms");
    }
    var lines = Files.readAllLines(log);
    assertTrue(lines.size() <= 4, "at most two runs in all: " + lines);
    var running = 0;
    for (var line : lines) {
      running += line.equals("start") ? 1 : -1;
      assertTrue(running <= 1, "one script at a time: " + lines);
    }
    assertTrue(changes.idle(), "idle once every ask is answered");
  }

  /** A cancelled ask is not answered, and a run with no ask left is not started. */
  @Test
  void aCancelledAskIsNeverAnswered() throws Exception {
    git(workspace, "init", "-q");
    var log = dir.resolve("runs.log");
    var slow =
        scripts(
            script ->
                script.equals(FileScripts.CHANGES)
                    ? "echo run >> '" + log + "'; sleep 0.5\n" + script
                    : script);
    var changes = new WorkspaceChanges(slow, CONTAINER, WorkspaceChanges.PATIENCE, () -> {});
    var connection = connection(changes, slow);
    connection.changes(new PtyMessage.FilesChanges(1));
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!Files.exists(log)) {
      assertTrue(System.nanoTime() < deadline, "the first run starts");
      Thread.onSpinWait();
    }
    connection.changes(new PtyMessage.FilesChanges(2));
    connection.cancel(new PtyMessage.FilesCancel(2));
    connection.changes(new PtyMessage.FilesChanges(3));

    var got = answers(Set.of(1, 3));

    assertEquals(Set.of(1, 3), got.keySet());
    assertNull(said.poll(1, TimeUnit.SECONDS), "nothing for the cancelled ask");
    assertEquals(2, Files.readAllLines(log).size());

    connection.changes(new PtyMessage.FilesChanges(4));
    connection.cancel(new PtyMessage.FilesCancel(4));
    connection.changes(new PtyMessage.FilesChanges(4));
    assertEquals(Set.of(4), answers(Set.of(4)).keySet(), "an id is free again once cancelled");
  }

  /** A run past its patience is ended, and each ask told in a true sentence. */
  @Test
  void aRunPastItsPatienceIsEndedAndSaidSo() throws Exception {
    git(workspace, "init", "-q");
    var hung =
        scripts(script -> script.equals(FileScripts.CHANGES) ? "sleep 30\n" + script : script);
    var changes = new WorkspaceChanges(hung, CONTAINER, Duration.ofMillis(300), () -> {});
    connection(changes, hung).changes(new PtyMessage.FilesChanges(1));

    var refused =
        assertInstanceOf(PtyMessage.FilesRefused.class, answers(Set.of(1)).get(1).getFirst());

    assertEquals(PtyMessage.FilesRefusal.FAILED, refused.kind());
    assertEquals(
        "What changed in dock was not said: git did not answer within 0.3 s, so Sail ended it.",
        refused.message());
  }

  /**
   * A connection slow to read its answers holds no other's: each is sent on its own connection's
   * thread, so while one's reply is held, the other's answer of the same run arrives.
   */
  @Test
  void aConnectionSlowToReadHoldsNoOthersAnswer() throws Exception {
    git(workspace, "init", "-q");
    var files = scripts(UnaryOperator.identity());
    var changes = new WorkspaceChanges(files, CONTAINER, WorkspaceChanges.PATIENCE, () -> {});
    var held = new java.util.concurrent.CountDownLatch(1);
    var stalled = new LinkedBlockingQueue<PtyMessage>();
    var slow =
        new FilesConnection(
            files,
            CONTAINER,
            "dock",
            message -> {
              try {
                held.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              stalled.add(message);
            },
            () -> null,
            unused -> {},
            new FilesConnection.Writes(
                new FilesConnection.WorkspaceLock(),
                ConcurrentHashMap.newKeySet(),
                () -> null,
                FilesConnection.AFTER_GO,
                FilesConnection.HOLD_BOUND),
            changes);
    slow.changes(new PtyMessage.FilesChanges(1));
    connection(changes, files).changes(new PtyMessage.FilesChanges(2));

    assertEquals(Set.of(2), answers(Set.of(2)).keySet(), "answered while the other is held");
    held.countDown();
    assertInstanceOf(PtyMessage.FilesChangeList.class, stalled.poll(30, TimeUnit.SECONDS));
  }

  /** No repository and no git are refused in their own words over a connection. */
  @Test
  void aConnectionSaysNoRepositoryInItsOwnWords() throws Exception {
    var files = scripts(UnaryOperator.identity());
    connection(new WorkspaceChanges(files, CONTAINER, WorkspaceChanges.PATIENCE, () -> {}), files)
        .changes(new PtyMessage.FilesChanges(1));
    assertEquals(
        new PtyMessage.FilesRefused(
            1,
            PtyMessage.FilesRefusal.NO_REPOSITORY,
            "No git repository is in " + workspace + ", so dock has no changes to show."),
        answers(Set.of(1)).get(1).getFirst());
  }

  /**
   * A diff over a connection: frames of at most a chunk, never {@link PtyMessage.FilesData}, whose
   * bytes are git's; a binary change and one past 20 MB refused in their own words.
   */
  @Test
  void aDiffIsAnsweredInItsOwnFramesAndRefusedInItsOwnWords() throws Exception {
    git(workspace, "init", "-q");
    var file =
        Files.writeString(workspace.resolve("big.txt"), ("a".repeat(39) + "\n").repeat(15_000));
    var binary = Files.write(workspace.resolve("b.bin"), new byte[] {0, 1});
    git(workspace, "add", ".");
    git(workspace, "commit", "-qm", "base");
    Files.writeString(file, ("b".repeat(39) + "\n").repeat(15_000));
    Files.write(binary, new byte[] {0, 2});
    var files = scripts(UnaryOperator.identity());
    var connection =
        connection(
            new WorkspaceChanges(files, CONTAINER, WorkspaceChanges.PATIENCE, () -> {}), files);

    connection.diff(new PtyMessage.FilesDiff(1, file.toString()));
    var frames = answers(Set.of(1)).get(1);
    assertTrue(frames.size() > 2, frames.size() + " frames");
    var bytes = new ByteArrayOutputStream();
    for (var frame : frames) {
      var data = assertInstanceOf(PtyMessage.FilesDiffData.class, frame);
      assertTrue(data.bytes().length <= PtyMessage.FILE_CHUNK);
      assertEquals(frame == frames.getLast(), data.eof());
      bytes.write(data.bytes());
    }
    assertArrayEquals(diff(file, FilesConnection.READ_CAP).bytes(), bytes.toByteArray());

    connection.diff(new PtyMessage.FilesDiff(2, binary.toString()));
    assertEquals(
        new PtyMessage.FilesRefused(
            2,
            PtyMessage.FilesRefusal.BINARY,
            binary + " is binary, so its change is not shown as text."),
        answers(Set.of(2)).get(2).getFirst());

    var endless =
        scripts(
            script ->
                script.equals(FileScripts.DIFF)
                    ? script.replace(
                        "git diff --no-color --no-ext-diff --no-textconv -U3 \"$@\" | head -c $((cap + 1))",
                        "head -c $((cap + 1)) /dev/zero")
                    : script);
    connection(
            new WorkspaceChanges(endless, CONTAINER, WorkspaceChanges.PATIENCE, () -> {}), endless)
        .diff(new PtyMessage.FilesDiff(3, file.toString()));
    var cut = answers(Set.of(3)).get(3);
    assertEquals(
        new PtyMessage.FilesRefused(
            3,
            PtyMessage.FilesRefusal.TOO_LARGE,
            "The change to " + file + " is over the 20 MB Sail shows, so it is not shown."),
        cut.getLast());
    assertTrue(
        cut.stream()
            .limit(cut.size() - 1)
            .allMatch(f -> f instanceof PtyMessage.FilesDiffData d && !d.eof()));
  }
}
