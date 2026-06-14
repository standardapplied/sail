package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecIndexMigratorTest {

  private static final String SPECS_DIR = "/home/dev/workspace/specs";

  @Test
  void convertsLegacyIndexToPerSpecMetadataAndRemovesIndex() throws Exception {
    var shell =
        new FakeShell()
            .on("test -f " + SPECS_DIR + "/index.yaml", ok())
            .on("cat " + SPECS_DIR + "/index.yaml", ok(indexYaml()))
            .on("test -f " + SPECS_DIR + "/auth/spec.yaml", missing())
            .on("mkdir -p " + SPECS_DIR + "/auth", ok())
            .on("test -f " + SPECS_DIR + "/payments/spec.yaml", missing())
            .on("mkdir -p " + SPECS_DIR + "/payments", ok())
            .on("printf '%s'", ok())
            .on("rm -f " + SPECS_DIR + "/index.yaml", ok());

    var result = new SpecIndexMigrator(shell).migrate("acme", SPECS_DIR, false);

    assertTrue(result.indexFound());
    assertEquals(2, result.converted());
    assertEquals(0, result.skipped());
    assertTrue(result.indexRemoved());
    assertTrue(shell.invoked("rm -f " + SPECS_DIR + "/index.yaml"));
  }

  @Test
  void keepsIndexWhenExistingMetadataDiffers() throws Exception {
    var shell =
        new FakeShell()
            .on("test -f " + SPECS_DIR + "/index.yaml", ok())
            .on("cat " + SPECS_DIR + "/index.yaml", ok(indexYaml()))
            .on("test -f " + SPECS_DIR + "/auth/spec.yaml", ok())
            .on(
                "cat " + SPECS_DIR + "/auth/spec.yaml",
                ok(
                    """
                    id: auth
                    title: Different
                    status: pending
                    """))
            .on("test -f " + SPECS_DIR + "/payments/spec.yaml", missing())
            .on("mkdir -p " + SPECS_DIR + "/payments", ok())
            .on("printf '%s'", ok());

    var result = new SpecIndexMigrator(shell).migrate("acme", SPECS_DIR, false);

    assertEquals(1, result.converted());
    assertEquals(1, result.skipped());
    assertFalse(result.indexRemoved());
    assertFalse(shell.invoked("rm -f " + SPECS_DIR + "/index.yaml"));
    assertEquals(1, result.warnings().size());
  }

  @Test
  void noOpsWhenLegacyIndexIsAbsent() throws Exception {
    var shell = new FakeShell().on("test -f " + SPECS_DIR + "/index.yaml", missing());

    var result = new SpecIndexMigrator(shell).migrate("acme", SPECS_DIR, false);

    assertFalse(result.indexFound());
    assertEquals(0, result.converted());
    assertFalse(result.indexRemoved());
  }

  @Test
  void dryRunDoesNotTouchContainer() throws Exception {
    var shell = new FakeShell().withDryRun(true);

    var result = new SpecIndexMigrator(shell).migrate("acme", SPECS_DIR, false);

    assertFalse(result.indexFound());
    assertEquals(1, result.warnings().size());
    assertTrue(shell.invocations().isEmpty());
  }

  private static ShellExec.Result ok() {
    return ok("");
  }

  private static ShellExec.Result ok(String stdout) {
    return new ShellExec.Result(0, stdout, "");
  }

  private static ShellExec.Result missing() {
    return new ShellExec.Result(1, "", "No such file");
  }

  private static String indexYaml() {
    return """
        specs:
          - id: auth
            title: Auth flow
            status: pending
            assignee: codex
            depends_on:
              - base
            branch: feat/auth
          - id: payments
            title: Payments
            status: review
        """;
  }

  private static final class FakeShell implements ShellExec {
    private final Map<String, Result> scripts = new LinkedHashMap<>();
    private final List<String> invocations = new ArrayList<>();
    private boolean dryRun;

    private FakeShell on(String pattern, Result result) {
      scripts.put(pattern, result);
      return this;
    }

    private FakeShell withDryRun(boolean dryRun) {
      this.dryRun = dryRun;
      return this;
    }

    private boolean invoked(String pattern) {
      return invocations.stream().anyMatch(command -> command.contains(pattern));
    }

    private List<String> invocations() {
      return List.copyOf(invocations);
    }

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
      invocations.add(joined);
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return new Result(1, "", "no script for " + joined);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return dryRun;
    }
  }
}
