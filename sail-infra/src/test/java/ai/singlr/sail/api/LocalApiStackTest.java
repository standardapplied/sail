/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The socket's stack routes, which the container's {@code portal} calls: a project's own agents
 * read its ports, an agent that may write brings it up, and a read-only room member is refused.
 */
class LocalApiStackTest {

  private final ScriptedShellExecutor shell =
      new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
          .onOk("incus list", StackOperationsTest.RUNNING)
          .onOk("ss -ltnH", StackOperationsTest.LISTENING);
  private final LocalApiRouter router =
      new LocalApiRouter(
          new TestOperations() {
            private final StackOperations stack =
                new StackOperations(
                    shell,
                    new ProjectLoader(
                        shell,
                        project ->
                            Optional.of(
                                SailYaml.fromMap(
                                    YamlUtil.parseMap(
                                        StackOperationsTest.ACME.replace("acme", project))))),
                    duration -> {},
                    System::nanoTime);

            @Override
            public StackOperations stack() {
              return stack;
            }
          });

  private ApiResponse ask(String method, String path, String credential, String form) {
    return router.handle(
        new LocalApiRequest(
            method,
            path,
            Map.of(),
            Map.of("authorization", "Bearer " + credential),
            form.getBytes(StandardCharsets.UTF_8)));
  }

  private static String message(ApiResponse response) {
    return (String) assertInstanceOf(Map.class, response.body().get("error")).get("message");
  }

  @Test
  void anAgentRunBringsItsProjectUpAndEveryCallerReadsItsPorts() {
    var served = ask("POST", "/v1/projects/acme/serve", TestOperations.RUN_CREDENTIAL, "timeout=5");

    assertEquals(200, served.status(), served.body().toString());
    assertEquals(true, served.body().get("all_listening"));
    for (var credential :
        List.of(
            TestOperations.RUN_CREDENTIAL,
            TestOperations.ROOM_RUN_CREDENTIAL,
            TestOperations.BOX_CREDENTIAL)) {
      assertEquals(200, ask("GET", "/v1/projects/acme/ports", credential, "").status(), credential);
    }
  }

  @Test
  void aPersonsSessionStopsTheProjectsProcesses() {
    shell.onOk("basename", "sail-process-web\n");

    var stopped = ask("DELETE", "/v1/projects/acme/serve", TestOperations.BOX_CREDENTIAL, "");

    assertEquals(200, stopped.status(), stopped.body().toString());
    assertEquals(
        1,
        shell.invocations().stream()
            .filter(c -> c.contains("systemctl --user disable --now sail-process-web.service"))
            .count());
  }

  @Test
  void aReadOnlyRoomMemberMayNotServeOrStopNamingWhy() {
    var served = ask("POST", "/v1/projects/acme/serve", TestOperations.ROOM_RUN_CREDENTIAL, "");
    var stopped = ask("DELETE", "/v1/projects/acme/serve", TestOperations.ROOM_RUN_CREDENTIAL, "");

    assertEquals(403, served.status());
    assertEquals(403, stopped.status());
    assertEquals(
        List.of(),
        shell.invocations().stream().filter(c -> c.contains("systemctl --user restart")).toList());
  }

  @Test
  void aRequestWithoutACredentialIsRefused() {
    var unknown = ask("GET", "/v1/projects/acme/ports", "sailrun_unknown", "");

    assertEquals(401, unknown.status());
  }

  @Test
  void aRunReachesOnlyItsOwnProjectsStack() {
    var other = ask("GET", "/v1/projects/zeta/ports", TestOperations.RUN_CREDENTIAL, "");

    assertEquals(404, other.status());
    assertEquals("Project 'zeta' is not in the catalog.", message(other));
  }

  @Test
  void otherMethodsAndShapesAreSaidSoAndATimeoutMustBeSeconds() {
    var credential = TestOperations.BOX_CREDENTIAL;

    assertEquals(405, ask("GET", "/v1/projects/acme/serve", credential, "").status());
    assertEquals(405, ask("POST", "/v1/projects/acme/ports", credential, "").status());
    assertEquals(404, ask("GET", "/v1/projects/acme/ports/web", credential, "").status());
    assertEquals(404, ask("POST", "/v1/projects/acme/serve/web", credential, "").status());
    assertEquals(404, ask("GET", "/v1/projects/acme/other", credential, "").status());
    assertEquals(404, ask("GET", "/v1/projects/acme", credential, "").status());
    var badTimeout = ask("POST", "/v1/projects/acme/serve", credential, "timeout=soon");
    assertEquals(400, badTimeout.status());
    assertEquals("timeout must be whole seconds, got: soon", message(badTimeout));
  }
}
