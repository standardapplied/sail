package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecWorkspaceTest {

  private static final String OAUTH_METADATA =
      """
      id: oauth-flow
      title: OAuth Flow
      status: pending
      """;

  private static final String SEARCH_METADATA =
      """
      id: search-api
      title: Search API
      status: review
      depends_on:
        - oauth-flow
      """;

  @Test
  void readSpecsScansMetadataFilesInSortedOrder() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                "/home/dev/workspace/specs/search-api/spec.yaml\n/home/dev/workspace/specs/oauth-flow/spec.yaml\n")
            .onOk("cat /home/dev/workspace/specs/oauth-flow/spec.yaml", OAUTH_METADATA)
            .onOk("cat /home/dev/workspace/specs/search-api/spec.yaml", SEARCH_METADATA);
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");

    var specs = workspace.readSpecs();

    assertEquals(2, specs.size());
    assertEquals("oauth-flow", specs.getFirst().id());
    assertEquals("search-api", specs.get(1).id());
    assertEquals(List.of("oauth-flow"), specs.get(1).dependsOn());
  }

  @Test
  void readSpecsRejectsDuplicateIds() {
    var duplicate =
        """
        id: oauth-flow
        title: Duplicate OAuth Flow
        status: pending
        """;
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                "/home/dev/workspace/specs/oauth-flow/spec.yaml\n/home/dev/workspace/specs/duplicate/spec.yaml\n")
            .onOk("cat /home/dev/workspace/specs/oauth-flow/spec.yaml", OAUTH_METADATA)
            .onOk("cat /home/dev/workspace/specs/duplicate/spec.yaml", duplicate);
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");

    var error = assertThrows(IllegalArgumentException.class, workspace::readSpecs);

    assertTrue(error.getMessage().contains("Duplicate spec id"));
  }

  @Test
  void readSpecsReturnsEmptyWhenDirectoryIsMissing() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail(
                "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                "No such file");
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");

    assertTrue(workspace.readSpecs().isEmpty());
  }

  @Test
  void readSpecsThrowsOnUnexpectedListFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onFail(
                "find /home/dev/workspace/specs -mindepth 2 -maxdepth 2 -name spec.yaml -print",
                "permission denied");
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");

    var error = assertThrows(java.io.IOException.class, workspace::readSpecs);

    assertTrue(error.getMessage().contains("Failed to list spec metadata"));
  }

  @Test
  void readSpecReadsSingleMetadataFile() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat /home/dev/workspace/specs/oauth-flow/spec.yaml", OAUTH_METADATA);
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");

    var spec = workspace.readSpec("oauth-flow");

    assertEquals("OAuth Flow", spec.title());
  }

  @Test
  void readSpecRejectsMismatchedMetadataId() {
    var mismatch =
        """
        id: wrong-id
        title: Wrong ID
        status: pending
        """;
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat /home/dev/workspace/specs/oauth-flow/spec.yaml", mismatch);
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");

    var error = assertThrows(java.io.IOException.class, () -> workspace.readSpec("oauth-flow"));

    assertTrue(error.getMessage().contains("Spec metadata id mismatch"));
  }

  @Test
  void readSpecReturnsNullWhenMissing() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat /home/dev/workspace/specs/oauth-flow/spec.yaml", "No such file");
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");

    assertNull(workspace.readSpec("oauth-flow"));
  }

  @Test
  void readSpecBodyReturnsNullWhenMissing() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat /home/dev/workspace/specs/oauth-flow/spec.md", "No such file");
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");

    var content = workspace.readSpecBody("oauth-flow");

    assertNull(content);
  }

  @Test
  void readSpecBodyThrowsOnUnexpectedFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat /home/dev/workspace/specs/oauth-flow/spec.md", "permission denied");
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");

    var error = assertThrows(java.io.IOException.class, () -> workspace.readSpecBody("oauth-flow"));

    assertTrue(error.getMessage().contains("Failed to read spec markdown"));
  }

  @Test
  void createSpecWritesMetadataAndMarkdown() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onceOnFail("test -e /home/dev/workspace/specs/oauth-flow", "No such file");
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");
    var spec = new Spec("oauth-flow", "OAuth Flow", SpecStatus.PENDING, null, List.of(), null);

    workspace.createSpec(spec, "# OAuth Flow");

    var commands = shell.invocations();
    assertTrue(
        commands.stream()
            .anyMatch(cmd -> cmd.contains("mkdir -p /home/dev/workspace/specs/oauth-flow")));
    assertTrue(
        commands.stream()
            .anyMatch(cmd -> cmd.contains("/home/dev/workspace/specs/oauth-flow/spec.yaml")));
    assertTrue(
        commands.stream()
            .anyMatch(cmd -> cmd.contains("/home/dev/workspace/specs/oauth-flow/spec.md")));
  }

  @Test
  void createSpecRejectsDuplicateDirectory() {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");
    var spec = new Spec("oauth-flow", "OAuth Flow", SpecStatus.PENDING, null, List.of(), null);

    var error =
        assertThrows(
            IllegalArgumentException.class, () -> workspace.createSpec(spec, "# OAuth Flow"));

    assertTrue(error.getMessage().contains("already exists"));
  }

  @Test
  void updateStatusWritesOnlyMetadata() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat /home/dev/workspace/specs/oauth-flow/spec.yaml", OAUTH_METADATA);
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/workspace/specs");

    var updated = workspace.updateStatus("oauth-flow", "review");

    assertEquals(SpecStatus.REVIEW, updated.status());
    assertTrue(shell.invocations().stream().anyMatch(cmd -> cmd.contains("status: review")));
    assertTrue(
        shell.invocations().stream()
            .anyMatch(cmd -> cmd.contains("/home/dev/workspace/specs/oauth-flow/spec.yaml")));
  }

  @Test
  void writeMetadataKeepsPathOutOfShellScript() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onceOnFail("test -e /home/dev/specs; touch /tmp/pwned/oauth-flow", "No such file");
    var workspace = new SpecWorkspace(shell, "acme-health", "/home/dev/specs; touch /tmp/pwned");
    var spec = new Spec("oauth-flow", "OAuth Flow", SpecStatus.PENDING, null, List.of(), null);

    workspace.createSpec(spec, "# OAuth Flow");

    assertTrue(
        shell.invocations().stream().anyMatch(cmd -> cmd.contains("printf '%s' \"$1\" > \"$2\"")));
  }
}
