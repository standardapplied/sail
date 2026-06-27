/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Measures, against a real incus daemon, whether a <em>privileged</em> Testcontainers Ryuk reaper
 * actually reaps containers under the <em>rootless</em> Podman that sail provisions — the open
 * question behind whether sail should re-enable Ryuk ({@code TESTCONTAINERS_RYUK_PRIVILEGED=true})
 * instead of disabling it (which leaks test containers until the hourly cron sweep).
 *
 * <p>Self-contained and faithful: it reproduces sail's rootless-Podman setup (install Podman, a
 * uid-1000 {@code dev} user, {@code enable-linger}, the user {@code podman.socket}), then drives
 * Ryuk's documented protocol — start Ryuk privileged with the Podman socket mounted, register a
 * label filter, hold the control connection, then drop it — and checks whether the labelled victim
 * container is gone once Ryuk's reconnection timeout elapses.
 *
 * <p><strong>This test is the empirical gate.</strong> Green here means privileged Ryuk reaps under
 * rootless Podman and sail can safely re-enable it; red (or {@code NOT_REAPED}) means it does not,
 * and the {@code RYUK_DISABLED} + hourly-cron fallback stays. Either way the answer is measured on
 * CI, not guessed. Like every {@code *IT}, it runs only under the {@code integration} profile and
 * fails loudly (never {@code assumeTrue}) once a daemon is reachable.
 */
class RyukReapsContainersIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-ryuk";
  private static final Duration SLOW = Duration.ofMinutes(8);

  @Test
  void privilegedRyukReapsContainersUnderRootlessPodman() throws Exception {
    ensureIncusOrSkip();
    try {
      launch(CONTAINER);
      waitForNetwork();
      setUpRootlessPodman();

      var run =
          shell.exec(
              ContainerExec.asDevUser(CONTAINER, List.of("bash", "-lc", experimentScript())),
              null,
              SLOW);

      assertTrue(
          run.stdout().contains("RYUK_RESULT=REAPED"),
          "privileged Ryuk must reap the labelled container under rootless Podman.\n"
              + "----- stdout -----\n"
              + run.stdout()
              + "\n----- stderr -----\n"
              + run.stderr());
    } finally {
      deleteContainerQuietly(CONTAINER);
    }
  }

  /** Reproduces sail's rootless-Podman provisioning, asserting each real step. */
  private void setUpRootlessPodman() throws Exception {
    assertOk(
        exec(
            CONTAINER,
            List.of(
                "bash",
                "-c",
                "userdel -r ubuntu 2>/dev/null || true;"
                    + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev")),
        "create the dev user");

    assertOk(
        slow(
            List.of(
                "incus",
                "exec",
                CONTAINER,
                "--",
                "bash",
                "-c",
                "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq"
                    + " podman uidmap")),
        "install Podman");

    assertOk(
        exec(
            CONTAINER,
            List.of(
                "bash",
                "-c",
                "mkdir -p /etc/containers/registries.conf.d && printf"
                    + " 'unqualified-search-registries = [\"docker.io\"]\\n' >"
                    + " /etc/containers/registries.conf.d/01-unqualified.conf")),
        "configure the container registry");

    assertOk(exec(CONTAINER, List.of("loginctl", "enable-linger", "dev")), "enable linger for dev");

    waitForUserBus();

    assertOk(
        shell.exec(
            ContainerExec.asDevUser(
                CONTAINER, List.of("systemctl", "--user", "enable", "--now", "podman.socket")),
            null,
            SLOW),
        "enable the rootless podman.socket");
  }

  /**
   * The Ryuk reaping probe, run as the dev user. Starts Ryuk privileged with the rootless Podman
   * socket mounted, registers a label filter over Ryuk's TCP port, holds the connection, then drops
   * it; after the reconnection timeout Ryuk should remove the labelled victim. Emits {@code
   * RYUK_RESULT=REAPED} or {@code RYUK_RESULT=NOT_REAPED}, with diagnostics for CI logs.
   */
  private static String experimentScript() {
    return """
        set -u
        SOCK="$XDG_RUNTIME_DIR/podman/podman.sock"
        RYUK_IMG="docker.io/testcontainers/ryuk:0.11.0"
        VICTIM_IMG="docker.io/library/busybox:latest"
        LABEL="sail.ryuk.it=1"

        echo "--- podman version ---"; podman version || true
        for i in $(seq 1 30); do [ -S "$SOCK" ] && break; sleep 1; done
        [ -S "$SOCK" ] || { echo "NO_SOCKET at $SOCK"; exit 1; }

        echo "--- pulling images ---"
        podman pull -q "$RYUK_IMG"    || { echo "RYUK_PULL_FAILED"; exit 1; }
        podman pull -q "$VICTIM_IMG"  || { echo "VICTIM_PULL_FAILED"; exit 1; }

        echo "--- starting ryuk (privileged) ---"
        podman run -d --rm --name sail-ryuk --privileged \\
          -v "$SOCK:/var/run/docker.sock" \\
          -e RYUK_RECONNECTION_TIMEOUT=3s \\
          -p 127.0.0.1:8080:8080 \\
          "$RYUK_IMG" || { echo "RYUK_START_FAILED"; podman logs sail-ryuk 2>&1 || true; exit 1; }

        for i in $(seq 1 30); do (echo >/dev/tcp/127.0.0.1/8080) >/dev/null 2>&1 && break; sleep 1; done

        echo "--- starting labelled victim ---"
        podman run -d --name sail-victim --label "$LABEL" "$VICTIM_IMG" sleep 600 \\
          || { echo "VICTIM_START_FAILED"; exit 1; }

        echo "--- registering filter with ryuk, then dropping the connection ---"
        exec 3<>/dev/tcp/127.0.0.1/8080
        printf 'label=%s\\n' "$LABEL" >&3
        sleep 2
        exec 3>&- 3<&-

        echo "--- waiting past the reconnection timeout for the reap ---"
        sleep 12

        if podman ps -a --format '{{.Names}}' | grep -qx sail-victim; then
          echo "RYUK_RESULT=NOT_REAPED"
        else
          echo "RYUK_RESULT=REAPED"
        fi
        echo "--- ryuk logs ---"; podman logs sail-ryuk 2>&1 | tail -20 || true
        podman rm -f sail-ryuk sail-victim >/dev/null 2>&1 || true
        """;
  }

  /**
   * Waits until the freshly launched container has working outbound DNS — a bare {@code incus
   * launch} returns before systemd-networkd finishes DHCP off the incus NAT bridge, so an immediate
   * {@code apt-get} fails to resolve the archive. Fails loudly if the network never comes up (e.g.
   * the CI host blocks bridge forwarding).
   */
  private void waitForNetwork() throws Exception {
    for (var attempt = 0; attempt < 60; attempt++) {
      if (exec(CONTAINER, List.of("getent", "hosts", "archive.ubuntu.com")).ok()) {
        return;
      }
      Thread.sleep(2000);
    }
    assertOk(
        exec(CONTAINER, List.of("getent", "hosts", "archive.ubuntu.com")),
        "container never obtained outbound DNS within 120s (incus NAT/forwarding?)");
  }

  private void waitForUserBus() throws Exception {
    var busPath = ContainerExec.DEV_XDG_RUNTIME_DIR + "/bus";
    for (var attempt = 0; attempt < 30; attempt++) {
      if (exec(CONTAINER, List.of("test", "-S", busPath)).ok()) {
        return;
      }
      Thread.sleep(500);
    }
  }

  private ShellExec.Result slow(List<String> command) throws Exception {
    return shell.exec(command, null, SLOW);
  }

  private static void assertOk(ShellExec.Result result, String step) {
    assertTrue(result.ok(), "rootless-Podman setup step failed (" + step + "): " + result.stderr());
  }
}
