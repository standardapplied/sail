/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SailYamlUpdater;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectApplier;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.gen.ServicePresets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "service",
    description = "Add an infrastructure service to a running project.",
    mixinStandardHelpOptions = true)
public final class ProjectAddServiceCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--service-name", description = "Service name (non-interactive mode).")
  private String serviceName;

  @Option(names = "--image", description = "Container image (non-interactive mode).")
  private String image;

  @Option(names = "--ports", description = "Comma-separated ports.", split = ",")
  private List<Integer> ports;

  @Option(names = "--env", description = "Environment variables (KEY=VALUE).", split = ",")
  private List<String> envPairs;

  @Option(names = "--volumes", description = "Volumes.", split = ",")
  private List<String> volumes;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    var ansi = Ansi.AUTO;
    var out = System.out;

    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    if (!Files.exists(singYamlPath)) {
      throw new IllegalStateException(
          "Project descriptor not found: "
              + singYamlPath.toAbsolutePath()
              + "\n  Create a sail.yaml in the current directory, or specify one with --file.");
    }

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    ContainerStateGuard.requireRunning(state, name);

    String svcName;
    SailYaml.Service service;

    if (serviceName != null && image != null) {
      svcName = serviceName;
      service = buildServiceFromFlags();
    } else {
      if (!json) {
        Banner.printBranding(out, ansi);
        out.println();
        out.println(ansi.string("  @|bold Add service to|@ " + name));
        out.println();
      }
      var selection = collectServiceInteractively(out, ansi);
      svcName = selection.name();
      service = selection.service();
    }

    var applier = new ProjectApplier(shell, out);
    var result = applier.applyServices(name, java.util.Map.of(svcName, service));

    SailYamlUpdater.addService(singYamlPath, svcName, service);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("action", "add-service");
      map.put("service", svcName);
      map.put("image", service.image());
      map.put("added", result.added());
      out.println(YamlUtil.dumpJson(map));
      return;
    }

    out.println();
    out.println(
        ansi.string(
            "  @|bold,green \u2713|@ Service '"
                + svcName
                + "' added to "
                + name
                + " and sail.yaml updated"));
  }

  private SailYaml.Service buildServiceFromFlags() {
    java.util.Map<String, String> env = null;
    if (envPairs != null && !envPairs.isEmpty()) {
      env = new LinkedHashMap<>();
      for (var pair : envPairs) {
        var eq = pair.indexOf('=');
        if (eq > 0) {
          env.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
      }
    }
    return new SailYaml.Service(image, ports, env, null, volumes);
  }

  private record ServiceSelection(String name, SailYaml.Service service) {}

  private ServiceSelection collectServiceInteractively(java.io.PrintStream out, Ansi ansi) {
    var presets = ServicePresets.all();

    out.println(ansi.string("  @|bold Choose a service (runs as a Podman container):|@"));
    for (var i = 0; i < presets.size(); i++) {
      var p = presets.get(i);
      var portsStr =
          p.service().ports().stream()
              .map(String::valueOf)
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      out.println(
          ansi.string(
              "    @|bold "
                  + (i + 1)
                  + ".|@ "
                  + p.displayName()
                  + " @|faint (port "
                  + portsStr
                  + ")|@"));
    }
    out.println(ansi.string("    @|bold c.|@ Custom service @|faint (any Podman image)|@"));

    out.print(ansi.string("  @|bold Selection|@: "));
    out.flush();
    var input = Objects.requireNonNullElse(ConsoleHelper.readLine(), "").strip();

    if (input.equalsIgnoreCase("c")) {
      return collectCustomService(out, ansi);
    }

    try {
      var idx = Integer.parseInt(input) - 1;
      if (idx >= 0 && idx < presets.size()) {
        var preset = presets.get(idx);
        out.print(ansi.string("  @|bold Version|@ @|faint [" + preset.defaultVersion() + "]|@: "));
        out.flush();
        var versionInput = Objects.requireNonNullElse(ConsoleHelper.readLine(), "").strip();
        var version = versionInput.isEmpty() ? preset.defaultVersion() : versionInput;
        var service = customizePresetEnv(preset.withVersion(version), out, ansi);
        return new ServiceSelection(preset.key(), service);
      }
    } catch (NumberFormatException ignored) {
    }

    throw new IllegalArgumentException("Invalid selection: " + input);
  }

  private SailYaml.Service customizePresetEnv(
      SailYaml.Service service, java.io.PrintStream out, Ansi ansi) {
    var defaultEnv = service.environment();
    var env = new LinkedHashMap<String, String>();
    if (defaultEnv != null && !defaultEnv.isEmpty()) {
      for (var entry : defaultEnv.entrySet()) {
        var value = promptWithDefault(out, ansi, "  " + entry.getKey(), entry.getValue());
        if (!value.isEmpty()) {
          env.put(entry.getKey(), value);
        }
      }
    }
    if (ConsoleHelper.confirmNo("Add environment variables?")) {
      do {
        var kv = promptRequired(out, ansi, "Environment variable as KEY=VALUE");
        var eqIdx = kv.indexOf('=');
        if (eqIdx > 0) {
          env.put(kv.substring(0, eqIdx), kv.substring(eqIdx + 1));
        }
      } while (ConsoleHelper.confirmNo("Add another env var?"));
    }
    return new SailYaml.Service(
        service.image(),
        service.ports(),
        env.isEmpty() ? null : env,
        service.command(),
        service.volumes());
  }

  private ServiceSelection collectCustomService(java.io.PrintStream out, Ansi ansi) {
    out.println();
    out.println(ansi.string("  @|bold Custom Podman service|@"));
    var svcName =
        promptRequired(
            out, ansi, "Service name \u2014 a label for this service (e.g. mysql, redis)");
    NameValidator.requireValidServiceName(svcName);
    var img = promptRequired(out, ansi, "Podman image (e.g. mysql:8.0, mongo:7, redis:latest)");
    var portsStr =
        promptWithDefault(out, ansi, "Host ports to expose (comma-separated, blank for none)", "");
    List<Integer> customPorts = null;
    if (!portsStr.isEmpty()) {
      customPorts = new ArrayList<>();
      for (var p : portsStr.split("[,\\s]+")) {
        try {
          customPorts.add(Integer.parseInt(p.strip()));
        } catch (NumberFormatException ignored) {
        }
      }
      if (customPorts.isEmpty()) customPorts = null;
    }
    java.util.Map<String, String> env = null;
    if (ConsoleHelper.confirmNo("Add environment variables?")) {
      env = new LinkedHashMap<>();
      do {
        var kv = promptRequired(out, ansi, "Environment variable as KEY=VALUE");
        var eqIdx = kv.indexOf('=');
        if (eqIdx > 0) {
          env.put(kv.substring(0, eqIdx), kv.substring(eqIdx + 1));
        }
      } while (ConsoleHelper.confirmNo("Add another env var?"));
    }
    List<String> customVolumes = null;
    if (ConsoleHelper.confirmNo("Add volumes?")) {
      customVolumes = new ArrayList<>();
      do {
        var vol = promptRequired(out, ansi, "Volume as name:path (e.g. mydata:/var/lib/data)");
        customVolumes.add(vol);
      } while (ConsoleHelper.confirmNo("Add another volume?"));
    }
    return new ServiceSelection(
        svcName, new SailYaml.Service(img, customPorts, env, null, customVolumes));
  }

  private static String promptWithDefault(
      java.io.PrintStream out, Ansi ansi, String label, String def) {
    if (def != null && !def.isEmpty()) {
      out.print(ansi.string("  @|bold " + label + "|@ @|faint [" + def + "]|@: "));
    } else {
      out.print(ansi.string("  @|bold " + label + "|@: "));
    }
    out.flush();
    var line = ConsoleHelper.readLine();
    if (line == null || line.isBlank()) {
      return Objects.requireNonNullElse(def, "");
    }
    return line.strip();
  }

  private static String promptRequired(java.io.PrintStream out, Ansi ansi, String label) {
    while (true) {
      out.print(ansi.string("  @|bold " + label + "|@: "));
      out.flush();
      var line = ConsoleHelper.readLine();
      if (line != null && !line.isBlank()) {
        return line.strip();
      }
      out.println(ansi.string("    @|yellow Required field. Please enter a value.|@"));
    }
  }
}
