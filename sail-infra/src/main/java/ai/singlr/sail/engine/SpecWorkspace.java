package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecDirectory;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

public final class SpecWorkspace {

  private final ShellExec shell;
  private final String containerName;
  private final String specsDir;

  public SpecWorkspace(ShellExec shell, String containerName, String specsDir) {
    this.shell = Objects.requireNonNull(shell);
    NameValidator.requireValidProjectName(containerName);
    if (Strings.isBlank(specsDir)) {
      throw new IllegalArgumentException("specsDir is required.");
    }
    this.containerName = containerName;
    this.specsDir = specsDir;
  }

  public List<Spec> readSpecs() throws IOException, InterruptedException, TimeoutException {
    var metadataPaths = specMetadataPaths();
    if (metadataPaths.isEmpty()) {
      return List.of();
    }
    var specs = new ArrayList<Spec>();
    for (var metadataPath : metadataPaths) {
      specs.add(readMetadataPath(metadataPath));
    }
    requireUniqueSpecIds(specs);
    return List.copyOf(specs);
  }

  public Spec readSpec(String specId) throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidSpecId(specId);
    var result =
        shell.exec(
            ContainerExec.asDevUser(containerName, List.of("cat", specMetadataPath(specId))));
    if (result.ok()) {
      var spec = SpecDirectory.parseMetadata(YamlUtil.parseMap(result.stdout()));
      requireMatchingSpecId(specId, spec);
      return spec;
    }
    if (isMissingFile(result)) {
      return null;
    }
    throw new IOException("Failed to read spec metadata: " + result.stderr());
  }

  public String readSpecBody(String specId)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidSpecId(specId);
    var result =
        shell.exec(
            ContainerExec.asDevUser(containerName, List.of("cat", specMarkdownPath(specId))));
    if (result.ok()) {
      return result.stdout().strip();
    }
    if (isMissingFile(result)) {
      return null;
    }
    throw new IOException("Failed to read spec markdown: " + result.stderr());
  }

  public Spec updateStatus(String specId, String newStatus)
      throws IOException, InterruptedException, TimeoutException {
    var status = SpecStatus.fromWire(newStatus);
    SpecDirectory.requireSettableStatus(status);
    var spec = readSpec(specId);
    if (spec == null) {
      throw new IllegalArgumentException("Spec '" + specId + "' not found");
    }
    var updated =
        new Spec(
            spec.id(),
            spec.project(),
            spec.title(),
            status,
            spec.assignee(),
            spec.dependsOn(),
            spec.repos(),
            spec.agent(),
            spec.model(),
            spec.reasoningEffort(),
            spec.branch());
    writeMetadata(updated);
    return updated;
  }

  public void createSpec(Spec spec, String markdown)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidSpecId(spec.id());
    if (specDirectoryExists(spec.id())) {
      throw new IllegalArgumentException("Spec '" + spec.id() + "' already exists.");
    }
    ensureSpecDirectory(spec.id());
    writeMetadata(spec);
    writeSpecBody(spec.id(), markdown);
  }

  public String readPlanBody(String specId)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidSpecId(specId);
    var result =
        shell.exec(ContainerExec.asDevUser(containerName, List.of("cat", specPlanPath(specId))));
    if (result.ok()) {
      return result.stdout().strip();
    }
    if (isMissingFile(result)) {
      return null;
    }
    throw new IOException("Failed to read spec plan: " + result.stderr());
  }

  public String specMarkdownPath(String specId) {
    NameValidator.requireValidSpecId(specId);
    return specsDir + "/" + specId + "/spec.md";
  }

  public String specPlanPath(String specId) {
    NameValidator.requireValidSpecId(specId);
    return specsDir + "/" + specId + "/plan.md";
  }

  public String specMetadataPath(String specId) {
    NameValidator.requireValidSpecId(specId);
    return specsDir + "/" + specId + "/spec.yaml";
  }

  private List<String> specMetadataPaths()
      throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(
            ContainerExec.asDevUser(
                containerName,
                List.of(
                    "find",
                    specsDir,
                    "-mindepth",
                    "2",
                    "-maxdepth",
                    "2",
                    "-name",
                    "spec.yaml",
                    "-print")));
    if (!result.ok()) {
      if (isMissingFile(result)) {
        return List.of();
      }
      throw new IOException("Failed to list spec metadata: " + result.stderr());
    }
    if (result.stdout().isBlank()) {
      return List.of();
    }
    return result.stdout().lines().filter(line -> !line.isBlank()).sorted().toList();
  }

  private static void requireMatchingSpecId(String expectedId, Spec spec) throws IOException {
    if (!expectedId.equals(spec.id())) {
      throw new IOException(
          "Spec metadata id mismatch for '" + expectedId + "': found '" + spec.id() + "'");
    }
  }

  private Spec readMetadataPath(String path)
      throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(ContainerExec.asDevUser(containerName, List.of("cat", path)));
    if (!result.ok()) {
      throw new IOException("Failed to read spec metadata: " + result.stderr());
    }
    return SpecDirectory.parseMetadata(YamlUtil.parseMap(result.stdout()));
  }

  private static void requireUniqueSpecIds(List<Spec> specs) {
    var ids = new HashSet<String>();
    for (var spec : specs) {
      if (!ids.add(spec.id())) {
        throw new IllegalArgumentException("Duplicate spec id: " + spec.id());
      }
    }
  }

  private void ensureSpecDirectory(String specId)
      throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(
            ContainerExec.asDevUser(
                containerName, List.of("mkdir", "-p", specsDir + "/" + specId)));
    if (!result.ok()) {
      throw new IOException("Failed to create spec directory: " + result.stderr());
    }
  }

  private void writeMetadata(Spec spec) throws IOException, InterruptedException, TimeoutException {
    var yaml = YamlUtil.dumpToString(SpecDirectory.generateMetadata(spec));
    var result =
        shell.exec(
            ContainerExec.asDevUser(
                containerName,
                List.of(
                    "bash",
                    "-c",
                    "printf '%s' \"$1\" > \"$2\"",
                    "bash",
                    yaml,
                    specMetadataPath(spec.id()))));
    if (!result.ok()) {
      throw new IOException("Failed to write spec metadata: " + result.stderr());
    }
  }

  private void writeSpecBody(String specId, String markdown)
      throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(
            ContainerExec.asDevUser(
                containerName,
                List.of(
                    "bash",
                    "-c",
                    "printf '%s' \"$1\" > \"$2\"",
                    "bash",
                    Objects.requireNonNullElse(markdown, ""),
                    specMarkdownPath(specId))));
    if (!result.ok()) {
      throw new IOException("Failed to write spec markdown: " + result.stderr());
    }
  }

  private boolean specDirectoryExists(String specId)
      throws IOException, InterruptedException, TimeoutException {
    var result =
        shell.exec(
            ContainerExec.asDevUser(containerName, List.of("test", "-e", specsDir + "/" + specId)));
    return result.ok();
  }

  private static boolean isMissingFile(ShellExec.Result result) {
    return result.stderr() != null && result.stderr().contains("No such file");
  }
}
