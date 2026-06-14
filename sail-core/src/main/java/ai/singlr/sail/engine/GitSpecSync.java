package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class GitSpecSync {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
  private static final Pattern AHEAD = Pattern.compile("ahead (\\d+)");
  private static final Pattern BEHIND = Pattern.compile("behind (\\d+)");

  private final ShellExec shell;
  private final List<String> gitCommand;
  private final Duration timeout;

  public GitSpecSync(ShellExec shell, List<String> gitCommand) {
    this(shell, gitCommand, DEFAULT_TIMEOUT);
  }

  public GitSpecSync(ShellExec shell, List<String> gitCommand, Duration timeout) {
    this.shell = Objects.requireNonNull(shell);
    this.gitCommand = requireCommand(gitCommand);
    this.timeout = Objects.requireNonNull(timeout);
  }

  public static GitSpecSync local(ShellExec shell, Path specsDir) {
    var path = Objects.requireNonNull(specsDir).toAbsolutePath().normalize();
    return new GitSpecSync(shell, List.of("git", "-C", path.toString()));
  }

  public Status status() throws IOException, InterruptedException, TimeoutException {
    var repository = exec("rev-parse", "--is-inside-work-tree");
    if (!repository.ok() || !repository.stdout().strip().equals("true")) {
      return new Status(
          State.NOT_A_REPOSITORY,
          null,
          null,
          0,
          0,
          false,
          false,
          false,
          message(State.NOT_A_REPOSITORY));
    }

    var branch = optionalOutput("branch", "--show-current");
    var upstreamResult = exec("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}");
    var upstream = upstreamResult.ok() ? blankToNull(upstreamResult.stdout().strip()) : null;
    var porcelain = exec("status", "--porcelain=v1", "-b");
    if (!porcelain.ok()) {
      var errorMessage = cleanMessage(porcelain);
      return new Status(
          State.ERROR,
          branch,
          upstream,
          0,
          0,
          false,
          false,
          true,
          errorMessage.isBlank() ? message(State.ERROR) : errorMessage);
    }

    var lines = porcelain.stdout().lines().toList();
    var header = lines.isEmpty() ? "" : lines.getFirst();
    var entries = lines.size() <= 1 ? List.<String>of() : lines.subList(1, lines.size());
    var ahead = count(AHEAD, header);
    var behind = count(BEHIND, header);
    var dirty = !entries.isEmpty();
    var conflicted = entries.stream().anyMatch(GitSpecSync::isConflict);
    var state = state(upstream, ahead, behind, dirty, conflicted);

    return new Status(
        state, branch, upstream, ahead, behind, dirty, conflicted, true, message(state));
  }

  public OperationResult pull() throws IOException, InterruptedException, TimeoutException {
    var before = requirePullable(status());
    var result = exec("pull", "--ff-only");
    if (!result.ok()) {
      throw new IOException("Failed to pull specs: " + cleanMessage(result));
    }
    var after = status();
    return new OperationResult("pull", before, after, changed(before, after), cleanMessage(result));
  }

  public OperationResult push() throws IOException, InterruptedException, TimeoutException {
    var before = requirePushable(status());
    var result = exec("push");
    if (!result.ok()) {
      throw new IOException("Failed to push specs: " + cleanMessage(result));
    }
    var after = status();
    return new OperationResult("push", before, after, changed(before, after), cleanMessage(result));
  }

  public OperationResult init(String remote, String branch)
      throws IOException, InterruptedException, TimeoutException {
    var remoteUrl = requireSafeRemote(remote);
    var before = status();
    if (before.repository()) {
      return new OperationResult("init", before, before, false, "Specs are already backed by Git.");
    }

    var init = exec("init", "--initial-branch", defaultBranch(branch));
    if (!init.ok()) {
      throw new IOException("Failed to initialize specs repository: " + cleanMessage(init));
    }
    if (remoteUrl != null) {
      var addRemote = exec("remote", "add", "origin", remoteUrl);
      if (!addRemote.ok()) {
        throw new IOException("Failed to add specs remote: " + cleanMessage(addRemote));
      }
    }
    var after = status();
    return new OperationResult("init", before, after, true, cleanMessage(init));
  }

  private Status requirePullable(Status status) {
    return switch (status.state()) {
      case CLEAN, BEHIND -> status;
      case AHEAD -> status;
      case DIRTY ->
          throw new IllegalStateException("Commit or stash local spec changes before pulling.");
      case DIVERGED ->
          throw new IllegalStateException("Specs have diverged. Resolve with Git before pulling.");
      case CONFLICTED ->
          throw new IllegalStateException("Resolve spec merge conflicts before pulling.");
      case NO_UPSTREAM ->
          throw new IllegalStateException("Specs repository has no upstream branch.");
      case NOT_A_REPOSITORY ->
          throw new IllegalStateException("Specs are not backed by a Git repository.");
      case ERROR -> throw new IllegalStateException(status.message());
    };
  }

  private Status requirePushable(Status status) {
    return switch (status.state()) {
      case CLEAN, AHEAD -> status;
      case DIRTY -> throw new IllegalStateException("Commit local spec changes before pushing.");
      case BEHIND -> throw new IllegalStateException("Pull remote spec changes before pushing.");
      case DIVERGED ->
          throw new IllegalStateException("Specs have diverged. Resolve with Git before pushing.");
      case CONFLICTED ->
          throw new IllegalStateException("Resolve spec merge conflicts before pushing.");
      case NO_UPSTREAM ->
          throw new IllegalStateException("Specs repository has no upstream branch.");
      case NOT_A_REPOSITORY ->
          throw new IllegalStateException("Specs are not backed by a Git repository.");
      case ERROR -> throw new IllegalStateException(status.message());
    };
  }

  private ShellExec.Result exec(String... args)
      throws IOException, InterruptedException, TimeoutException {
    var command = Stream.concat(gitCommand.stream(), Stream.of(args)).toList();
    return shell.exec(command, null, timeout);
  }

  private String optionalOutput(String... args)
      throws IOException, InterruptedException, TimeoutException {
    var result = exec(args);
    return result.ok() ? blankToNull(result.stdout().strip()) : null;
  }

  private static State state(
      String upstream, int ahead, int behind, boolean dirty, boolean conflicted) {
    if (conflicted) {
      return State.CONFLICTED;
    }
    if (dirty) {
      return State.DIRTY;
    }
    if (upstream == null) {
      return State.NO_UPSTREAM;
    }
    if (ahead > 0 && behind > 0) {
      return State.DIVERGED;
    }
    if (behind > 0) {
      return State.BEHIND;
    }
    if (ahead > 0) {
      return State.AHEAD;
    }
    return State.CLEAN;
  }

  private static int count(Pattern pattern, String input) {
    var matcher = pattern.matcher(input);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
  }

  private static boolean isConflict(String entry) {
    if (entry.length() < 2) {
      return false;
    }
    var status = entry.substring(0, 2);
    return switch (status) {
      case "DD", "AU", "UD", "UA", "DU", "AA", "UU" -> true;
      default -> false;
    };
  }

  private static boolean changed(Status before, Status after) {
    return before.ahead() != after.ahead()
        || before.behind() != after.behind()
        || before.dirty() != after.dirty()
        || before.conflicted() != after.conflicted()
        || before.state() != after.state();
  }

  private static List<String> requireCommand(List<String> command) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("git command is required.");
    }
    var copy = new ArrayList<String>();
    for (var token : command) {
      if (Strings.isBlank(token)) {
        throw new IllegalArgumentException("git command tokens must not be blank.");
      }
      copy.add(token);
    }
    return List.copyOf(copy);
  }

  private static String message(State state) {
    return switch (state) {
      case CLEAN -> "Specs are synchronized.";
      case DIRTY -> "Specs have uncommitted local changes.";
      case AHEAD -> "Specs have local commits to push.";
      case BEHIND -> "Specs have remote commits to pull.";
      case DIVERGED -> "Specs have diverged from upstream.";
      case CONFLICTED -> "Specs have merge conflicts.";
      case NO_UPSTREAM -> "Specs repository has no upstream branch.";
      case NOT_A_REPOSITORY -> "Specs are not backed by a Git repository.";
      case ERROR -> "Specs Git status could not be read.";
    };
  }

  private static String cleanMessage(ShellExec.Result result) {
    var stderr = blankToNull(result.stderr());
    if (stderr != null) {
      return stderr.strip();
    }
    var stdout = blankToNull(result.stdout());
    return stdout != null ? stdout.strip() : "";
  }

  private static String blankToNull(String value) {
    return Strings.isBlank(value) ? null : value;
  }

  private static String defaultBranch(String branch) {
    return Strings.isBlank(branch) ? "main" : branch;
  }

  private static String requireSafeRemote(String remote) {
    var value = blankToNull(remote);
    if (value == null) {
      return null;
    }
    if (value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
      throw new IllegalArgumentException("remote must not contain control characters.");
    }
    return value;
  }

  public enum State {
    NOT_A_REPOSITORY,
    NO_UPSTREAM,
    CLEAN,
    DIRTY,
    AHEAD,
    BEHIND,
    DIVERGED,
    CONFLICTED,
    ERROR
  }

  public record Status(
      State state,
      String branch,
      String upstream,
      int ahead,
      int behind,
      boolean dirty,
      boolean conflicted,
      boolean repository,
      String message) {}

  public record OperationResult(
      String operation, Status before, Status after, boolean changed, String message) {}
}
