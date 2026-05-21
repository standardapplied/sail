/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ShellExecutor;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "status",
    description = "Show the sail API service status (installed / active / pid).",
    mixinStandardHelpOptions = true)
public final class HostServiceStatusCommand implements Runnable {

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--show-unit", description = "Print the rendered systemd unit file and exit.")
  private boolean showUnit;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var username = HostServiceInstallers.currentUsername();
    var installer =
        HostServiceInstallers.create(new ShellExecutor(false), "127.0.0.1", 7070, username);

    if (showUnit) {
      System.out.print(installer.renderUnit());
      return;
    }

    var installed = installer.isInstalled();
    var status = installed ? installer.status() : null;
    var linger = installer.lingerStatus();

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("installed", installed);
      map.put("service_file", installer.serviceFilePath().toString());
      map.put("systemd_link", installer.systemdLinkPath().toString());
      map.put("user", username);
      map.put("linger", linger);
      if (status != null) {
        map.put("load_state", status.loadState());
        map.put("active_state", status.activeState());
        map.put("sub_state", status.subState());
        if (status.pid() != null) {
          map.put("pid", status.pid());
        }
        map.put("running", status.running());
      }
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println(Ansi.AUTO.string("  @|bold sail-api service|@"));
    System.out.println(Ansi.AUTO.string("    @|bold Installed:|@ " + (installed ? "yes" : "no")));
    if (installed) {
      System.out.println(
          Ansi.AUTO.string("    @|bold Unit:|@      " + installer.serviceFilePath()));
      System.out.println(
          Ansi.AUTO.string("    @|bold Link:|@      " + installer.systemdLinkPath()));
    }
    System.out.println(Ansi.AUTO.string("    @|bold User:|@      " + username));
    System.out.println(Ansi.AUTO.string("    @|bold Linger:|@    " + linger));
    if (status != null) {
      System.out.println(
          Ansi.AUTO.string(
              "    @|bold State:|@     " + status.activeState() + " (" + status.subState() + ")"));
      if (status.pid() != null) {
        System.out.println(Ansi.AUTO.string("    @|bold PID:|@       " + status.pid()));
      }
    }
  }
}
