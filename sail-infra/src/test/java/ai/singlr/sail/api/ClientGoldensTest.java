/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.access.baseline.GoldenFiles;
import ai.singlr.sail.api.Event.WellKnownTypes;
import ai.singlr.sail.auth.EnrollmentService;
import ai.singlr.sail.auth.PasskeyService;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.EngagementMode;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.RunStatus;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerConfig;
import ai.singlr.sail.engine.HeldLocks;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.Recorded;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.TemplateBuild;
import ai.singlr.sail.engine.TemplateRecord;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.LoginOutcome;
import ai.singlr.sail.harness.RunReport;
import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyWire;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.EnrollmentTicketStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FeedPositions;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.PendingChallengeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.sync.SyncTransition;
import ai.singlr.sail.webauthn.RelyingParty;
import ai.singlr.sail.webauthn.TestAuthenticator;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server's answers a native client decodes, as the goldens {@code clients/SailKit} tests read:
 * each route the first native surface calls and each refusal it handles, as one request and the
 * response the real router gave; the event stream the real stream handler wrote while those routes
 * ran, with a sample of every well-known event type; every pty frame type as {@link PtyWire} writes
 * it, with the magic it opens with and the largest payload it sends; and the vocabularies the
 * client's enums mirror. Nothing of the wire is typed here: what a golden holds, the server wrote.
 * Each file must equal what the server writes now, so a change to a shape a client decodes fails
 * here and arrives in its pull request as a changed golden. They are a snapshot as the permission
 * baseline is ({@link GoldenFiles}), so a change that alters one on purpose re-records them as it
 * does:
 *
 * <pre>
 * mvn -o -pl sail-infra -am test -Dtest=ClientGoldensTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Dsail.access.baseline=record
 * </pre>
 *
 * <p>Ids are random and times run on the clock, so the clock is pinned and every UUID is renamed by
 * the order it first appears in; the name of the machine that answered is written as {@code box};
 * the passkey ceremonies' random bytes, ids and secrets are written as fixed values of the same
 * shape, each by the order it first appears in; JSON keys are sorted, since a map's iteration order
 * is not part of the wire. Nothing else is rewritten.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class ClientGoldensTest {

  private static final Path GOLDENS = Path.of("..", "clients", "SailKit", "Tests", "Goldens");
  private static final Instant AT = Instant.parse("2026-11-11T09:00:00.656Z");

  /**
   * A position the box hands out: its database's catalog, the seq and the fingerprint of the entry
   * there, scrubbed to {@code golden}, the seq, and the seq again in the fingerprint's place.
   */
  private static final Pattern POSITION =
      Pattern.compile("\\b([0-9a-f]{32})\\.([0-9]+)\\.([0-9a-f]{12}|0)\\b");

  private static final Pattern UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final String PROJECT = "harbor";
  private static final String ROOM = "harbor-design";
  private static final String QUIET_ROOM = "harbor-quay";
  private static final String SPEC = "harbor-docks";
  private static final String BARE_SPEC = "harbor-buoys";
  private static final String RUN = "run-1";
  private static final String DATA = "data: ";
  private static final String ID = "id: ";
  private static final int STREAMS = 2;
  private static final SyncConfig OWNER = new SyncConfig(null, null, "uday");
  private static final ProjectDefinition HARBOR =
      new ProjectDefinition(
          PROJECT,
          "",
          null,
          null,
          null,
          null,
          List.of(new ProjectDefinition.Repo("https://github.com/harbor/app.git", "app", null)),
          null,
          null,
          null,
          null);

  /**
   * A project with resources set and a repo whose host asks for credentials: what an admin builds a
   * template of in the app, frames 2d to 2h of the design.
   */
  private static final ProjectDefinition LIGHTHOUSE =
      new ProjectDefinition(
          "lighthouse",
          "",
          new ProjectDefinition.Resources(2, "4GB", "20GB"),
          null,
          null,
          null,
          List.of(new ProjectDefinition.Repo("https://github.com/harbor/lamp.git", "lamp", null)),
          null,
          null,
          null,
          null);

  /** Harbor's template, current at v1, as Incus lists it. */
  private static final String TEMPLATE =
      TestWorkspaces.template(PROJECT, ProjectProvisioner.inputs(HARBOR, Optional.empty()));

  /** Workspace {@code dock} of harbor, running, uday's by {@code udayId}. */
  private static String dock(String udayId) {
    return workspace("dock", "Running", "uday", udayId);
  }

  /** Workspace {@code name} of harbor as Incus lists it, {@code status}, {@code handle}'s by id. */
  private static String workspace(String name, String status, String handle, String id) {
    return """
        {"name": "harbor-%s", "status": "%s", "type": "container",
         "config": {"user.sail.project": "harbor", "user.sail.workspace": "%s",
                    "user.sail.ready": "1762941601000", "user.sail.owner": "%s",
                    "user.sail.owner_id": "%s"},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}"""
        .formatted(name, status, name, handle, id);
  }

  private static final String RP_ID = "sail.standardapplied.com";
  private static final String ORIGIN = "https://" + RP_ID;
  private static final String PASSKEYS = "/v1/auth";
  private static final String OWN_PASSKEYS = "/v1/passkeys";
  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64URL_DECODE = Base64.getUrlDecoder();

  /** The fields of a passkey ceremony that hold random bytes, ids or secrets. */
  private static final Set<String> CEREMONY_RANDOM =
      Set.of(
          "challenge",
          "challenge_id",
          "id",
          "credential_id",
          "session_token",
          "client_data_json",
          "attestation_object",
          "authenticator_data",
          "signature",
          "user_handle");

  @TempDir Path tempDir;
  private Clock clock;

  @BeforeEach
  void pinTheClock() {
    clock = DateTimeUtils.clock;
    DateTimeUtils.clock = Clock.fixed(AT, ZoneOffset.UTC);
  }

  @AfterEach
  void restoreTheClock() {
    DateTimeUtils.clock = clock;
  }

  @Test
  void theRoutesAndTheStreamAClientDecodesAnswerAsTheGoldensSay() throws Exception {
    try (var db = Sqlite.open(tempDir.resolve("box.db"));
        var bus = new EventBus();
        var box = new Box(db, bus, tempDir)) {
      var uday = box.session("uday", "Uday", "uday@example.com", Role.ADMIN);
      var http = new TreeMap<String, String>();
      box.record(http, "projects-none", "GET", "/v1/projects", uday, null);
      HeldProjects.hold(db, PROJECT);
      box.holdsDockOf("uday");
      var vik = box.token("vik", Role.VIEWER);
      var sam = box.scopedToken("sam");
      box.seed("POST", "/v1/rooms", uday, room());
      box.seed("POST", "/v1/rooms", uday, bare(QUIET_ROOM, "Quay"));
      var stream = box.stream("/v1/events/stream", uday);
      var roomStream = box.stream("/v1/events/stream?room=" + ROOM, uday);
      var asked = box.record(http, "message-posted", "POST", messages(), uday, question());
      var asking = ((Map<?, ?>) asked.get("message")).get("id").toString();
      box.record(http, "message-reply", "POST", messages(), uday, reply(asking));
      box.seed("POST", "/v1/specs", uday, spec());
      box.seed("POST", "/v1/specs", uday, bare(BARE_SPEC, "Buoys mark the channel"));
      box.seat(Engagement.of("claude-code", "read_only", "opus", DateTimeUtils.format(AT)));
      var run = box.run(RUN, SPEC, OWNER.handle());
      box.post(run, "The harness exited before it answered.", MessageStore.Mark.ERROR);
      box.post(run, "Shall I take the south berth?", MessageStore.Mark.QUESTION);
      box.seed("POST", "/v1/events", uday, published());
      box.seed("PUT", "/v1/specs/" + BARE_SPEC, uday, Map.of("status", "pending"));
      box.reviewPassedWithAFindingOpen();

      box.record(http, "whoami", "GET", "/v1/whoami", uday, null);
      box.record(http, "health", "GET", "/v1/health", null, null);
      box.record(http, "agents", "GET", "/v1/agents", uday, null);
      box.record(http, "projects", "GET", "/v1/projects", uday, null);
      box.record(http, "rooms", "GET", "/v1/rooms?project=" + PROJECT, uday, null);
      box.record(http, "room", "GET", "/v1/rooms/" + ROOM, uday, null);
      box.record(http, "room-bare", "GET", "/v1/rooms/" + QUIET_ROOM, uday, null);
      box.record(http, "messages", "GET", messages() + "?limit=50", uday, null);
      box.record(http, "specs", "GET", "/v1/specs?project=" + PROJECT, uday, null);
      box.record(http, "spec", "GET", "/v1/specs/" + SPEC, uday, null);
      box.record(http, "spec-bare", "GET", "/v1/specs/" + BARE_SPEC, uday, null);
      box.record(http, "spec-measures", "GET", "/v1/specs/" + SPEC + "/measures", uday, null);
      box.record(
          http, "specs-measures", "GET", "/v1/specs:measures?project=" + PROJECT, uday, null);
      box.record(http, "workspaces", "GET", "/v1/projects/" + PROJECT + "/workspaces", uday, null);
      box.record(http, "attention", "POST", "/v1/attention", uday, Map.of("conversation", SPEC));
      box.record(
          http,
          "terminal-ticket",
          "POST",
          "/v1/terminal/tickets",
          uday,
          Map.of("action", "open", "project", PROJECT));
      box.record(
          http,
          "terminal-ticket-workspace",
          "POST",
          "/v1/terminal/tickets",
          uday,
          Map.of("action", "open", "project", PROJECT, "workspace", "dock"));
      box.record(
          http,
          "terminal-ticket-files",
          "POST",
          "/v1/terminal/tickets",
          uday,
          Map.of("action", "files", "project", PROJECT, "workspace", "dock"));
      box.record(
          http,
          "refusal-workspace-stopped",
          "POST",
          "/v1/terminal/tickets",
          uday,
          Map.of("action", "files", "project", PROJECT, "workspace", "idle"));
      box.record(
          http,
          "refusal-not-found-session",
          "POST",
          "/v1/terminal/tickets",
          uday,
          Map.of("action", "attach", "session", "app-gone"));
      box.record(
          http,
          "workspace-sessions",
          "GET",
          "/v1/projects/" + PROJECT + "/workspaces/dock/sessions",
          uday,
          null);

      box.record(http, "refusal-read-only-credential", "POST", messages(), vik, plain("hello"));
      box.record(http, "refusal-room-not-found", "GET", "/v1/rooms/nowhere", uday, null);
      box.record(http, "refusal-spec-not-found", "GET", "/v1/specs/nothing", uday, null);
      box.record(http, "refusal-missing-bearer-token", "GET", "/v1/whoami", null, null);
      box.record(http, "refusal-invalid-bearer-token", "GET", "/v1/whoami", "not-a-token", null);
      box.record(http, "refusal-bad-request", "POST", messages(), uday, plain(""));
      box.record(http, "refusal-out-of-scope", "POST", "/v1/specs", sam, unroomed());
      box.record(
          http,
          "refusal-project-not-in-catalog-sessions",
          "GET",
          "/v1/projects/" + PROJECT + "/workspaces/dock/sessions",
          sam,
          null);
      box.record(http, "refusal-project-not-created", "POST", members(), uday, member());
      box.record(http, "refusal-not-found", "GET", "/v1/nowhere", uday, null);
      box.run("run-2", SPEC, "ada");
      box.record(http, "refusal-run-on-other-node", "GET", "/v1/runs/run-2/log", uday, null);
      box.seed("DELETE", "/v1/rooms/" + QUIET_ROOM, uday, null);
      box.seed("PUT", "/v1/specs/" + BARE_SPEC, uday, Map.of("status", "cancelled"));
      box.seed(
          "POST", "/v1/specs:prune", uday, Map.of("ids", List.of(BARE_SPEC), "dry_run", false));
      var beginning = box.beginning();
      var changes =
          box.record(http, "changes", "GET", "/v1/changes?since=" + beginning, uday, null);
      box.record(http, "board-specs", "GET", "/v1/specs?project=" + PROJECT, uday, null);
      box.record(http, "board-rooms", "GET", "/v1/rooms?project=" + PROJECT, uday, null);
      box.record(http, "refusal-resync-required", "GET", "/v1/changes?since=999999999", uday, null);
      box.recordPasskeys(http, uday);
      var resumed = box.resumed(uday, beginning, "id: " + changes.get("next"));
      var resync = box.resumed(uday, "999999999", "event: resync");

      var full = box.fullHouse(uday);
      box.end(uday, box.publishEveryOtherWellKnownEvent(run));
      var events = new TreeMap<String, String>();
      events.put("answers.json", json(Map.of("open", stream.head(), "full", full)));
      events.put("stream.sse", Scrub.stream(Scrub.busEvents(stream.join()), box::renamed));
      events.put("room-stream.sse", Scrub.stream(Scrub.busEvents(roomStream.join()), box::renamed));
      events.put("resumed-stream.sse", Scrub.stream(resumed, box::renamed));
      events.put("resync-stream.sse", Scrub.stream(resync, box::renamed));
      Acting.system(() -> new ProjectStore(db).upsert(HARBOR));
      var dana = box.session("dana", "Dana", "dana@example.com", Role.ADMIN);
      var dock = "/v1/projects/" + PROJECT + "/workspaces/dock";
      var workspaces = "/v1/projects/" + PROJECT + "/workspaces";
      var ana = box.token("ana", Role.MEMBER);
      box.record(
          http,
          "refusal-read-only-credential-workspace",
          "POST",
          workspaces,
          vik,
          Map.of("name", "new"));
      box.record(
          http,
          "refusal-conflict-workspace-exists",
          "POST",
          workspaces,
          dana,
          Map.of("name", "dock"));
      box.record(http, "refusal-forbidden-not-assignee", "POST", dock + "/start", ana, null);
      box.record(http, "workspace-sessions-not-theirs", "GET", dock + "/sessions", ana, null);
      box.record(
          http,
          "refusal-not-found-workspace",
          "GET",
          "/v1/projects/" + PROJECT + "/workspaces/gone/sessions",
          ana,
          null);
      box.record(
          http,
          "workspace-create",
          "POST",
          "/v1/projects/" + PROJECT + "/workspaces",
          dana,
          Map.of("name", "new"));
      box.record(http, "workspace-start", "POST", dock + "/start", dana, null);
      box.record(http, "workspace-stop", "POST", dock + "/stop", dana, null);
      box.record(http, "refusal-workspace-holds-work", "DELETE", dock, dana, null);
      box.record(http, "workspace-delete", "DELETE", dock + "?force=true", dana, null);
      box.lists(
          workspace("dock", "Running", "uday", box.idOf("uday")),
          workspace("pier", "Stopped", "uday", box.idOf("uday")),
          workspace("quay", "Running", "ana", box.idOf("ana")));
      box.record(http, "workspaces-three", "GET", workspaces, dana, null);
      box.lists();
      box.record(http, "workspaces-none", "GET", workspaces, dana, null);
      box.lists(
          workspace("dock", "Running", "uday", box.idOf("uday")),
          workspace("new", "Running", "uday", box.idOf("uday")));
      box.record(http, "workspaces-made", "GET", workspaces, dana, null);
      box.recordAgents(http, ana, dana);
      box.recordChats(http, ana, dana);
      box.recordChatAnswers(http, ana, dana);
      box.recordDriveAgents(http);
      box.recordLogins(http);
      box.recordTemplates(
          http, dana, box.session("ike", "Ike", "ike@example.com", Role.ADMIN), ana, vik);
      box.record(http, "project-created", "POST", "/v1/projects", dana, marina());
      box.record(http, "project-definition", "GET", "/v1/projects/marina/definition", dana, null);
      box.record(
          http,
          "refusal-project-name-taken",
          "POST",
          "/v1/projects",
          dana,
          Map.of("name", PROJECT));
      box.record(
          http,
          "refusal-project-name-invalid",
          "POST",
          "/v1/projects",
          dana,
          Map.of("name", "Harbor Docks"));
      box.record(
          http,
          "refusal-project-repos-same-path",
          "POST",
          "/v1/projects",
          dana,
          Map.of("name", "jetty", "repos", reposAtOnePath()));
      box.record(
          http,
          "refusal-project-section-repos-same-path",
          "PUT",
          "/v1/projects/marina/definition/repos",
          dana,
          Map.of("value", reposAtOnePath()));

      assertAll(
          () -> GoldenFiles.check(GOLDENS.resolve("http"), http),
          () -> GoldenFiles.check(GOLDENS.resolve("events"), events),
          () ->
              assertEquals(
                  wellKnownTypes(),
                  typesIn(events.get("stream.sse"), events.get("room-stream.sse")),
                  "the streams carry every well-known event type"));
    }
  }

  @Test
  void everyPtyFrameIsWhatThePtyWireWrites() throws IOException {
    var frames = new ArrayList<Map<String, Object>>();
    for (var message : everyPtyMessage()) {
      var name = message.getClass().getSimpleName();
      frames.add(Map.of("name", name, "hex", HexFormat.of().formatHex(wire(message))));
    }
    var sampled =
        frames.stream().map(frame -> frame.get("name").toString()).collect(Collectors.toSet());
    var permitted =
        Arrays.stream(PtyMessage.class.getPermittedSubclasses())
            .flatMap(
                type ->
                    type.isSealed()
                        ? Arrays.stream(type.getPermittedSubclasses())
                        : Stream.of(type))
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    assertEquals(permitted, sampled, "a sample of every pty frame type");
    GoldenFiles.check(
        GOLDENS.resolve("frames"),
        new TreeMap<>(
            Map.of(
                "frames.json",
                json(
                    Map.of(
                        "magic",
                        HexFormat.of().formatHex(magic()),
                        "max_payload",
                        largestPayload(),
                        "frames",
                        frames)))));
  }

  @Test
  void theVocabulariesAClientMirrorsAreTheServers() {
    var vocabulary = new LinkedHashMap<String, Object>();
    vocabulary.put(
        "error_codes",
        Arrays.stream(ErrorCode.values())
            .map(code -> Map.of("code", code.code(), "status", code.httpCode()))
            .toList());
    vocabulary.put("change_types", ChangeFeed.TYPES);
    vocabulary.put("event_types", List.copyOf(wellKnownTypes()));
    vocabulary.put(
        "member_modes", Arrays.stream(EngagementMode.values()).map(EngagementMode::wire).toList());
    vocabulary.put(
        "publisher_lanes",
        Arrays.stream(Actor.Lane.values())
            .map(lane -> Event.Publisher.of(new Actor("uday", Role.MEMBER, lane)).lane())
            .toList());
    vocabulary.put(
        "remedy_runners",
        Arrays.stream(Remedy.Runs.values())
            .map(runs -> runs.name().toLowerCase(Locale.ROOT))
            .toList());
    vocabulary.put("roles", Arrays.stream(Role.values()).map(Role::attribute).toList());
    vocabulary.put(
        "upload_refusals",
        Arrays.stream(PtyMessage.PutRefusal.values()).map(PtyMessage.PutRefusal::wire).toList());
    vocabulary.put(
        "file_kinds",
        Arrays.stream(PtyMessage.FileKind.values()).map(PtyMessage.FileKind::wire).toList());
    vocabulary.put(
        "change_states",
        Arrays.stream(PtyMessage.ChangeState.values()).map(PtyMessage.ChangeState::wire).toList());
    vocabulary.put(
        "file_refusals",
        Arrays.stream(PtyMessage.FilesRefusal.values())
            .map(PtyMessage.FilesRefusal::wire)
            .toList());
    vocabulary.put(
        "ticket_actions",
        Arrays.stream(PtyMessage.TicketAction.values())
            .map(PtyMessage.TicketAction::wire)
            .toList());
    vocabulary.put(
        "ticket_refusals",
        Arrays.stream(PtyMessage.TicketRefusal.values())
            .map(PtyMessage.TicketRefusal::wire)
            .toList());
    vocabulary.put("run_presences", List.of(RunPresence.WORKING, RunPresence.QUIET));
    vocabulary.put("run_statuses", Arrays.stream(RunStatus.values()).map(RunStatus::wire).toList());
    vocabulary.put(
        "spec_statuses", Arrays.stream(SpecStatus.values()).map(SpecStatus::wire).toList());
    vocabulary.put(
        "wake_modes", List.of(RoomWakePolicy.ON, RoomWakePolicy.MENTION_MODE, RoomWakePolicy.OFF));
    GoldenFiles.check(
        GOLDENS.resolve("vocabulary"), new TreeMap<>(Map.of("vocabulary.json", json(vocabulary))));
  }

  /** {@code value} as a golden holds it: its keys sorted, one to a line. */
  private static String json(Object value) {
    return YamlUtil.dumpJsonPretty(Scrub.canonical(value, UnaryOperator.identity())) + "\n";
  }

  /**
   * A whole definition as the app's Create Project sends it: a name, two repos, both harnesses
   * installed and the Medium size.
   */
  private static Map<String, Object> marina() {
    return Map.of(
        "name",
        "marina",
        "repos",
        List.of(
            Map.of("url", "https://github.com/harbor/app.git", "path", "app"),
            Map.of("url", "https://github.com/harbor/docs.git", "path", "docs")),
        "agent",
        Map.of("install", List.of("claude-code", "codex")),
        "resources",
        Map.of("cpu", 4, "memory", "8GB", "disk", "50GB"));
  }

  /** Two repositories the app sends with one path: the second would clone over the first. */
  private static List<Map<String, Object>> reposAtOnePath() {
    return List.of(
        Map.of("url", "https://github.com/harbor/app.git", "path", "app"),
        Map.of("url", "https://github.com/harbor/site.git", "path", "app"));
  }

  private static String messages() {
    return "/v1/rooms/" + ROOM + "/messages";
  }

  private static String members() {
    return "/v1/rooms/" + ROOM + "/members";
  }

  private static Map<String, Object> plain(String body) {
    return Map.of("body", body);
  }

  private static Map<String, Object> unroomed() {
    return Map.of("id", "harbor-ferry", "project", PROJECT, "title", "A ferry timetable");
  }

  /**
   * An event a client publishes, whose data is the client's own: every kind of JSON value, a null
   * inside a list and an integer past what a double holds exactly among them.
   */
  private static Map<String, Object> published() {
    var berths = new ArrayList<Object>(List.of(4, 12.5, true, "south"));
    berths.add(null);
    return Map.of(
        "v",
        1,
        "ts",
        DateTimeUtils.format(AT),
        "project",
        PROJECT,
        "type",
        "board_updated",
        "agent",
        "mast",
        "host",
        "laptop",
        "data",
        Map.of("berths", berths, "displacement", 9_007_199_254_740_993L));
  }

  private static Map<String, Object> room() {
    return Map.of("id", ROOM, "project", PROJECT, "title", "Docks", "wake", "mention");
  }

  /** A room or a spec as it is created with nothing but what it must have. */
  private static Map<String, Object> bare(String id, String title) {
    return Map.of("id", id, "project", PROJECT, "title", title);
  }

  private static Map<String, Object> member() {
    return Map.of("agent", "claude-code", "mode", "read_only", "model", "opus");
  }

  private static Map<String, Object> question() {
    return Map.of("body", "Which berth takes the ferry — north or south? ⚓", "question", true);
  }

  private static Map<String, Object> reply(String messageId) {
    return Map.of("body", "South: it has the deeper draft. 🚢", "reply_to", messageId);
  }

  private static Map<String, Object> spec() {
    return Map.ofEntries(
        Map.entry("id", SPEC),
        Map.entry("project", PROJECT),
        Map.entry("title", "Docks take the ferry"),
        Map.entry("status", "pending"),
        Map.entry("assignee", "uday"),
        Map.entry("agent", "claude-code"),
        Map.entry("model", "opus"),
        Map.entry("reasoning_effort", "high"),
        Map.entry("branch", "agent/harbor-docks"),
        Map.entry("priority", 2),
        Map.entry("depends_on", List.of("harbor-berths")),
        Map.entry("repos", List.of("harbor-api")),
        Map.entry("body", "The south berth takes the ferry."),
        Map.entry("room_id", ROOM));
  }

  /** An event with no data: the envelope every event has, as the box itself publishes one. */
  private static Event envelope(String spec, String type) {
    return Event.of(PROJECT, spec, type, Event.SAIL_AGENT, "box");
  }

  private static Set<String> wellKnownTypes() {
    return Arrays.stream(WellKnownTypes.class.getFields())
        .filter(field -> Modifier.isStatic(field.getModifiers()))
        .filter(field -> field.getType() == String.class)
        .map(ClientGoldensTest::constant)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static String constant(Field field) {
    try {
      return (String) field.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Set<String> typesIn(String... streams) {
    return Stream.of(streams)
        .flatMap(String::lines)
        .filter(line -> line.startsWith(DATA))
        .map(line -> YamlUtil.parseMap(line.substring(DATA.length())).get("type"))
        .filter(Objects::nonNull)
        .map(String::valueOf)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static List<PtyMessage> everyPtyMessage() {
    var lounge =
        new PtyMessage.SessionInfo(
                "lounge",
                "inst-1",
                true,
                2,
                "uday",
                ROOM,
                List.of("claude", "--permission-mode", "bypassPermissions"))
            .placed(PROJECT, "dock", "uday")
            .lived(1_763_460_000_000L, "")
            .running(new PtyMessage.Agent("claude-code", "bypassPermissions", false));
    var shell = new PtyMessage.SessionInfo("shell", "inst-2", false, 0, "", "", List.of("bash"));
    return List.of(
        new PtyMessage.Hello("tok-uday"),
        new PtyMessage.Welcome("boot-7"),
        new PtyMessage.Create(
            "lounge",
            List.of(),
            "/home/dev",
            PROJECT,
            ROOM,
            132,
            43,
            "dock",
            new PtyMessage.Agent("codex", "full-access", true)),
        new PtyMessage.Attach("lounge", true, "pane-7f", false),
        new PtyMessage.Input(42, "ls -la\r".getBytes(StandardCharsets.UTF_8)),
        new PtyMessage.Resize(100, 30),
        new PtyMessage.TakeWrite(),
        new PtyMessage.Detach(),
        new PtyMessage.ListSessions("", PtyMessage.PAGE_LIMIT),
        new PtyMessage.Kill("lounge"),
        new PtyMessage.Yield("lounge", "a dispatch took the repos", "A dispatch took the repos."),
        new PtyMessage.IssueTicket(PtyMessage.TicketAction.OPEN, PROJECT, "sail_uday", "dock"),
        new PtyMessage.Ticket("tkt_harbor"),
        new PtyMessage.PutBegin(7, "", 64L << 20),
        new PtyMessage.PutChunk(7, Long.MAX_VALUE, chunk()),
        new PtyMessage.PutEnd(7, sha256(chunk())),
        new PtyMessage.PutCancel(Integer.MAX_VALUE),
        new PtyMessage.PutAck(7, 65536),
        new PtyMessage.PutStored(7, "/var/lib/sail-paste/inst-1/1-shot.png"),
        new PtyMessage.PutRefused(-1, PtyMessage.PutRefusal.TOO_LARGE, 64L << 20),
        new PtyMessage.TicketRefused(
            PtyMessage.TicketRefusal.NOT_FOUND,
            "Project 'harbor' is not in the catalog.",
            Remedy.of("Run '", Remedy.sail("sync"), "'.")),
        new PtyMessage.Output(42, "héllo ✅\u001b[0m\r\n".getBytes(StandardCharsets.UTF_8)),
        new PtyMessage.ReplayBegin(true),
        new PtyMessage.ReplayEnd(),
        new PtyMessage.WriterChanged("uday", true),
        new PtyMessage.Resized(100, 30),
        new PtyMessage.Paused(),
        new PtyMessage.Continued(),
        new PtyMessage.SessionEnded("exited(0)"),
        lounge,
        new PtyMessage.Sessions(List.of(lounge, shell), "shell"),
        new PtyMessage.Ok(),
        new PtyMessage.Err(
            "You do not hold the write token.",
            new Remedy(
                List.of(
                    new Remedy.Words("Run "),
                    new Remedy.Command(Remedy.Runs.SAIL, "session", List.of("take", "lounge")),
                    new Remedy.Words(" to take it."))),
            ErrorCode.LOGIN_NOT_STORED.code()),
        new PtyMessage.YieldWorkspace(
            PROJECT,
            "dock",
            "Workspace dock of harbor is being stopped by ana, which ends this terminal.",
            "Workspace dock of harbor was stopped by ana."),
        new PtyMessage.FilesList(1, "/home/dev/workspace"),
        new PtyMessage.FilesStat(2, "/home/dev/workspace/App.java"),
        new PtyMessage.FilesRead(3, "/home/dev/workspace/App.java", 65536, 262144),
        new PtyMessage.FilesCancel(3),
        new PtyMessage.FilesWatch(
            List.of(
                new PtyMessage.Watched("/home/dev/workspace", "51:9:4096:1.5:1.5"),
                new PtyMessage.Watched("/home/dev/workspace/gone.md", ""))),
        new PtyMessage.FilesListing(
            1,
            "51:9:4096:1795210162.152082747:1795210162.152082747",
            List.of(
                new PtyMessage.FileEntry(
                    "App.java",
                    PtyMessage.FileKind.FILE,
                    PtyMessage.FileKind.NONE,
                    12,
                    1_795_210_162_152_082_747L,
                    0644),
                new PtyMessage.FileEntry(
                    "src", PtyMessage.FileKind.LINK, PtyMessage.FileKind.FOLDER, 3, -1, 0777),
                new PtyMessage.FileEntry(
                    ai.singlr.sail.pty.FileNames.decode(
                        new byte[] {'b', 'a', 'd', (byte) 0xff, 'n'}),
                    PtyMessage.FileKind.FILE,
                    PtyMessage.FileKind.NONE,
                    4,
                    7,
                    0600)),
            false,
            true),
        new PtyMessage.FilesEntry(
            2,
            new PtyMessage.FileEntry(
                "App.java", PtyMessage.FileKind.FILE, PtyMessage.FileKind.NONE, 12, 7, 0600),
            "51:10:12:7.0:7.0"),
        new PtyMessage.FilesData(
            3,
            "51:10:12:7.0:7.0",
            "class App {}".getBytes(StandardCharsets.UTF_8),
            true,
            true,
            "42f26956ba1a845d48ac0db6270beddc5d864212ef56efe8a7964871297b9da3"),
        new PtyMessage.FilesChanged(List.of("/home/dev/workspace", "/home/dev/workspace/App.java")),
        new PtyMessage.FilesTick(),
        new PtyMessage.FilesRefused(
            4, PtyMessage.FilesRefusal.LOOP, "/home/dev/workspace/self leads back to itself."),
        new PtyMessage.FilesWrite(
            5,
            "/home/dev/workspace/App.java",
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            "51:10:12:7.0:7.0",
            13),
        new PtyMessage.FilesCreate(6, "/home/dev/workspace/notes", true),
        new PtyMessage.FilesMove(7, "/home/dev/workspace/a.md", "/home/dev/workspace/b.md"),
        new PtyMessage.FilesRemove(8, "/home/dev/workspace/old"),
        new PtyMessage.FilesWritten(
            5,
            "a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447",
            "51:11:13:8.0:8.0"),
        new PtyMessage.FilesConflict(5, PtyMessage.ABSENT),
        new PtyMessage.FilesDone(6),
        new PtyMessage.FilesChanges(9),
        new PtyMessage.FilesChangeList(
            9,
            41,
            "/home/dev/workspace",
            "main",
            "",
            List.of(
                new PtyMessage.Change(
                    "/home/dev/workspace/App.java", PtyMessage.ChangeState.MODIFIED, ""),
                new PtyMessage.Change(
                    "/home/dev/workspace/New.java",
                    PtyMessage.ChangeState.RENAMED,
                    "/home/dev/workspace/Old.java"),
                new PtyMessage.Change(
                    ai.singlr.sail.pty.FileNames.decode(new byte[] {'/', 'b', (byte) 0xff}),
                    PtyMessage.ChangeState.UNTRACKED,
                    "")),
            List.of("/home/dev/workspace/node_modules"),
            false,
            false),
        new PtyMessage.FilesDiff(10, "/home/dev/workspace/App.java"),
        new PtyMessage.FilesDiffData(
            10, "@@ -1 +1 @@\n-a\n+b\n".getBytes(StandardCharsets.UTF_8), true));
  }

  /** The largest chunk an upload carries, every byte value in turn. */
  private static byte[] chunk() {
    var chunk = new byte[64 << 10];
    for (var i = 0; i < chunk.length; i++) {
      chunk[i] = (byte) i;
    }
    return chunk;
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] wire(PtyMessage message) throws IOException {
    var out = new ByteArrayOutputStream();
    PtyWire.write(Channels.newChannel(out), message);
    return out.toByteArray();
  }

  /** What {@link PtyWire#handshake} writes before it reads its peer's answer. */
  private static byte[] magic() {
    var sent = new ByteArrayOutputStream();
    assertThrows(
        EOFException.class,
        () ->
            PtyWire.handshake(
                Channels.newChannel(InputStream.nullInputStream()), Channels.newChannel(sent)));
    return sent.toByteArray();
  }

  /**
   * The largest payload {@link PtyWire#write} sends: an output's bytes are doubled until it refuses
   * the frame, then narrowed to the last size it sent.
   */
  private static int largestPayload() throws IOException {
    var refused = 1;
    while (sends(refused)) {
      refused *= 2;
    }
    var sent = refused / 2;
    while (refused - sent > 1) {
      var between = (sent + refused) / 2;
      if (sends(between)) {
        sent = between;
      } else {
        refused = between;
      }
    }
    return wire(new PtyMessage.Output(0, new byte[sent])).length - Integer.BYTES;
  }

  private static boolean sends(int bytes) {
    try {
      wire(new PtyMessage.Output(0, new byte[bytes]));
      return true;
    } catch (IOException refused) {
      return false;
    }
  }

  /** A box serving the web API and the event stream over its own database and bus. */
  private static final class Box implements AutoCloseable {
    private final Sqlite db;
    private final EventBus bus;
    private final SailOperations operations;
    private final ScriptedShellExecutor incus;
    private final FakeAgentProcess chats = new FakeAgentProcess(new FakeContainer(PROJECT));
    private final HttpServer server;

    /**
     * Whom each credential made here stands for, as the terminal host resolves it: what the fake
     * host lists a caller's sessions by.
     */
    private final Map<String, String> holders = new java.util.concurrent.ConcurrentHashMap<>();

    private final ManualClock streamClock;
    private final SessionAwareAuth auth;
    private final HttpServer unconfigured;
    private final Map<String, String> pinned = new LinkedHashMap<>();
    private final List<OpenStream> streams = new ArrayList<>();
    private final Map<String, String> ids = new LinkedHashMap<>();
    private final Path projects;
    private final java.util.concurrent.atomic.AtomicReference<SyncConfig> config =
        new java.util.concurrent.atomic.AtomicReference<>(OWNER);

    Box(Sqlite db, EventBus bus, Path dir) throws IOException {
      this.projects = dir;
      new SchemaManager(db).migrate();
      this.db = db;
      this.bus = bus;
      this.streamClock = new ManualClock(AT, Duration.ofMinutes(1));
      this.incus =
          new ScriptedShellExecutor(new ShellExec.Result(0, "[]", ""))
              .onOk("incus storage list", "[{\"name\": \"tank\", \"driver\": \"zfs\"}]")
              .onOk("incus snapshot list harbor--tmpl --format json", TestWorkspaces.V1)
              .onOk("incus list --format json", "[" + TEMPLATE + "]")
              .onOk("sail-left", "changes app\n")
              .onOk("ticks() {", "gone");
      chats.onStart(fake -> fake.onClose(fake::exitsIfRunning));
      this.operations =
          OperationsFactory.create(
                  db,
                  incus,
                  bus,
                  SyncScheduler.disabled(),
                  SessionYield.NONE,
                  new OperationHooks(
                      bus::publish,
                      new WatcherSpawner(incus, WatcherSpawner::spawnProcess),
                      Workspaces.onThisBox(incus),
                      DispatchOperations.shellLauncher(incus),
                      DispatchOperations.Listener.NONE,
                      StopOperations.Listener.NONE,
                      chats),
                  SyncOperations.PLAIN_NOTICES)
              .useControlPlane(
                  db,
                  dir,
                  new SyncOperations(
                      db,
                      "node",
                      dir,
                      config::get,
                      target -> {
                        throw new IOException("main unavailable");
                      }));
      this.auth = TestAuth.sessions(db, config::get);
      this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      PrivateAttributes.context(
          server,
          "/",
          new ApiRouter(
              operations,
              auth,
              new RateLimitGate(),
              new AgentLogStreamer(auth),
              Optional.of(
                  new TerminalTickets(
                      new TerminalTickets.Host() {
                        @Override
                        public PtyMessage ticket(
                            String credential,
                            PtyMessage.TicketAction action,
                            String target,
                            String workspace) {
                          if (action == PtyMessage.TicketAction.FILES && workspace.equals("idle")) {
                            return new PtyMessage.TicketRefused(
                                PtyMessage.TicketRefusal.STOPPED,
                                "Workspace idle of harbor is stopped, and reading its files does"
                                    + " not start it.",
                                Remedy.of(
                                    "Start it with: ",
                                    Remedy.sail("project workspace start", PROJECT, "idle"),
                                    "."));
                          }
                          if (target.equals("app-gone")) {
                            return new PtyMessage.TicketRefused(
                                PtyMessage.TicketRefusal.NOT_FOUND,
                                "There is no session 'app-gone' on this box that you may attach.");
                          }
                          return new PtyMessage.Ticket("tkt_" + target);
                        }

                        @Override
                        public PtyMessage sessions(String credential) {
                          var who = holders.get(credential);
                          if (who == null) {
                            return new PtyMessage.TicketRefused(
                                PtyMessage.TicketRefusal.FORBIDDEN,
                                "This credential is bound to no FDE, so it may attach to nothing.");
                          }
                          return new PtyMessage.Sessions(
                              Stream.of(
                                      new PtyMessage.SessionInfo(
                                              "app-5a6b7c8d", "i-2", false, 0, "", "", List.of())
                                          .placed(PROJECT, "dock", "uday")
                                          .lived(
                                              1_763_456_400_000L,
                                              "Workspace dock of harbor was stopped by ana."),
                                      new PtyMessage.SessionInfo(
                                              "app-1f2e3d4c",
                                              "i-1",
                                              true,
                                              1,
                                              "uday",
                                              "",
                                              List.of("claude"))
                                          .placed(PROJECT, "dock", "uday")
                                          .lived(1_763_460_000_000L, "")
                                          .running(
                                              new PtyMessage.Agent(
                                                  "claude-code", "default", false)),
                                      new PtyMessage.SessionInfo(
                                              "app-9e0f1a2b", "i-3", true, 0, "", "", List.of())
                                          .placed(PROJECT, "pier", "uday")
                                          .lived(1_763_460_000_000L, ""))
                                  .filter(info -> info.opener().equals(who))
                                  .toList(),
                              "");
                        }
                      },
                      new InetSocketAddress(InetAddress.ofLiteral("100.64.0.7"), 7072)))));
      server.createContext(
          "/v1/events/stream",
          new SseHandler(
              bus,
              auth,
              operations.liveMembers()::turnState,
              operations.changes(),
              STREAMS,
              streamClock));
      PrivateAttributes.context(
          server,
          PASSKEYS,
          new WebauthnAuthHandler(
              new PasskeyService(
                  new RelyingParty(RP_ID, "Sail", Set.of(ORIGIN)),
                  new FdeStore(db),
                  new WebauthnCredentialStore(db),
                  new AuthSessionStore(db),
                  new PendingChallengeStore(db),
                  Duration.ofDays(30)),
              enrollment(),
              auth,
              List.of(ORIGIN)));
      server.start();
      this.unconfigured = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      PrivateAttributes.context(
          unconfigured, PASSKEYS, new WebauthnAuthHandler(null, null, auth, null));
      unconfigured.start();
    }

    private EnrollmentService enrollment() {
      return new EnrollmentService(new EnrollmentTicketStore(db), new FdeStore(db));
    }

    /**
     * A passkey enrolled by ticket and signed in with, as a native client does it, each answer
     * recorded: the ticket refused once spent, a second registration offering the enrolled passkey
     * as excluded, a passkey the box never enrolled, an origin it does not accept, and the routes
     * on a box with no relying party; then the person's own passkeys, listed and removed, and
     * {@code plainSession}, a session no passkey signed in, refused a removal.
     */
    void recordPasskeys(Map<String, String> goldens, String plainSession) throws Exception {
      var passkey = new TestAuthenticator(RP_ID);
      var ticket = enrollment().issue("uday").ticket();
      var started =
          ceremony(goldens, "passkey-register-start", base(), "register/start", ticket, Map.of());
      var made = passkey.register(challenge(started.get("public_key")), ORIGIN);
      var finish = new LinkedHashMap<String, Object>();
      finish.put("challenge_id", started.get("challenge_id"));
      finish.put("client_data_json", B64URL.encodeToString(made.clientDataJson()));
      finish.put("attestation_object", B64URL.encodeToString(made.attestationObject()));
      finish.put("label", "Sail for Mac");
      ceremony(goldens, "passkey-register-finish", base(), "register/finish", ticket, finish);
      ceremony(goldens, "refusal-forbidden", base(), "register/start", ticket, Map.of());
      ceremony(
          goldens,
          "passkey-register-start-enrolled",
          base(),
          "register/start",
          enrollment().issue("uday").ticket(),
          Map.of());
      var signing = ceremony(goldens, "passkey-login-start", base(), "login/start", null, Map.of());
      var assertion = passkey.assertResponse(challenge(signing.get("public_key")), ORIGIN);
      var login = new LinkedHashMap<String, Object>();
      login.put("challenge_id", signing.get("challenge_id"));
      login.put("credential_id", B64URL.encodeToString(assertion.credentialId()));
      login.put("client_data_json", B64URL.encodeToString(assertion.clientDataJson()));
      login.put("authenticator_data", B64URL.encodeToString(assertion.authenticatorData()));
      login.put("signature", B64URL.encodeToString(assertion.signature()));
      var signedIn = ceremony(goldens, "passkey-login-finish", base(), "login/finish", null, login);
      var stranger = new TestAuthenticator(RP_ID).assertResponse(new byte[32], ORIGIN);
      var unknown = new LinkedHashMap<>(login);
      unknown.put("credential_id", B64URL.encodeToString(stranger.credentialId()));
      ceremony(goldens, "refusal-authentication-failed", base(), "login/finish", null, unknown);
      var elsewhere = enrollment().issue("uday").ticket();
      var restarted = ceremony(goldens, null, base(), "register/start", elsewhere, Map.of());
      var foreign =
          new TestAuthenticator(RP_ID)
              .register(challenge(restarted.get("public_key")), "https://elsewhere.example");
      var refused = new LinkedHashMap<String, Object>();
      refused.put("challenge_id", restarted.get("challenge_id"));
      refused.put("client_data_json", B64URL.encodeToString(foreign.clientDataJson()));
      refused.put("attestation_object", B64URL.encodeToString(foreign.attestationObject()));
      refused.put("label", "Sail for Mac");
      ceremony(goldens, "refusal-invalid-request", base(), "register/finish", elsewhere, refused);
      ceremony(
          goldens,
          "refusal-passkey-not-configured",
          "http://127.0.0.1:" + unconfigured.getAddress().getPort(),
          "login/start",
          null,
          Map.of());
      var session = signedIn.get("session_token").toString();
      var other = enroll(goldens, "Sail for iPhone");
      var first = B64URL.encodeToString(passkey.credentialId());
      record(goldens, "passkeys", "GET", OWN_PASSKEYS, session, null);
      record(
          goldens,
          "refusal-forbidden-passkey-no-passkey-session",
          "DELETE",
          ownPasskey(first),
          plainSession,
          null);
      record(
          goldens, "refusal-conflict-passkey-current", "DELETE", ownPasskey(first), session, null);
      record(goldens, "passkey-removed", "DELETE", ownPasskey(other), session, null);
      record(goldens, "refusal-conflict-passkey-last", "DELETE", ownPasskey(first), session, null);
      record(
          goldens,
          "refusal-not-found-passkey-not-yours",
          "DELETE",
          ownPasskey(B64URL.encodeToString(stranger.credentialId())),
          session,
          null);
    }

    /** Enrolls another passkey of uday's, labelled {@code label}, recording nothing; its id. */
    private String enroll(Map<String, String> goldens, String label) throws Exception {
      var another = new TestAuthenticator(RP_ID);
      var ticket = enrollment().issue("uday").ticket();
      var started = ceremony(goldens, null, base(), "register/start", ticket, Map.of());
      var made = another.register(challenge(started.get("public_key")), ORIGIN);
      var finish = new LinkedHashMap<String, Object>();
      finish.put("challenge_id", started.get("challenge_id"));
      finish.put("client_data_json", B64URL.encodeToString(made.clientDataJson()));
      finish.put("attestation_object", B64URL.encodeToString(made.attestationObject()));
      finish.put("label", label);
      ceremony(goldens, null, base(), "register/finish", ticket, finish);
      return B64URL.encodeToString(another.credentialId());
    }

    private static String ownPasskey(String id) {
      return OWN_PASSKEYS + "/" + id;
    }

    private static byte[] challenge(Object options) {
      return B64URL_DECODE.decode(((Map<?, ?>) options).get("challenge").toString());
    }

    /**
     * Records {@code POST /v1/auth/<route>} at {@code base} as {@code name}, or sends it only when
     * no name is given, with the enrollment ticket when given (never recorded), its random values
     * pinned; answers the response body as the server sent it.
     */
    private Map<String, Object> ceremony(
        Map<String, String> goldens,
        String name,
        String base,
        String route,
        String ticket,
        Map<String, Object> body)
        throws Exception {
      var path = PASSKEYS + "/" + route;
      var builder =
          HttpRequest.newBuilder(URI.create(base + path))
              .POST(HttpRequest.BodyPublishers.ofString(YamlUtil.dumpJson(body)));
      if (ticket != null) {
        builder.header("X-Enrollment-Ticket", ticket);
      }
      HttpResponse<String> response;
      try (var client = HttpClient.newHttpClient()) {
        response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      }
      if (response.statusCode() >= 500 && !name.startsWith("refusal-")) {
        fail(path + " answered " + response.body());
      }
      var parsed = YamlUtil.parseMap(response.body());
      pin(body);
      pin(parsed);
      var request = new LinkedHashMap<String, Object>();
      request.put("method", "POST");
      request.put("path", path);
      request.put("body", body);
      var answer = new LinkedHashMap<String, Object>();
      answer.put("status", response.statusCode());
      answer.put("body", parsed);
      if (name != null) {
        goldens.put(
            name + ".json",
            json(Scrub.canonical(Map.of("request", request, "response", answer), this::renamed)));
      }
      return parsed;
    }

    /**
     * Pins every random value {@code value} holds under a ceremony's random fields; the relying
     * party's id is its name, not a random one.
     */
    private void pin(Object value) {
      switch (value) {
        case Map<?, ?> map ->
            map.forEach(
                (key, item) -> {
                  if ("rp".equals(key)) {
                    return;
                  }
                  if (item instanceof String text && CEREMONY_RANDOM.contains(key.toString())) {
                    pinned.computeIfAbsent(text, found -> golden(key.toString(), found));
                  } else {
                    pin(item);
                  }
                });
        case List<?> list -> list.forEach(this::pin);
        case null, default -> {}
      }
    }

    /** A fixed value in {@code found}'s shape: a session or challenge id keeps its prefix. */
    private String golden(String field, String found) {
      var order = pinned.size() + 1;
      if (found.startsWith(SessionAwareAuth.SESSION_PREFIX)) {
        return SessionAwareAuth.SESSION_PREFIX + "golden" + order;
      }
      if (found.startsWith("wac_")) {
        return "wac_golden" + order;
      }
      return B64URL.encodeToString(
          ("golden " + field + " " + order).getBytes(StandardCharsets.UTF_8));
    }

    String session(String handle, String displayName, String email, Role role) {
      var fde = new FdeStore(db).add(handle, displayName, email, role.attribute());
      var token = new AuthSessionStore(db).create(fde.id(), Duration.ofMinutes(30)).token();
      holders.put(token, handle);
      return token;
    }

    /** A member's token whose scope grants no project. */
    String scopedToken(String handle) {
      var token = token(handle, Role.MEMBER);
      new FdeStore(db).restrict(handle);
      return token;
    }

    /**
     * Seats {@code member} in the room as the box's own machinery does: engaging one through the
     * route needs the project's container, which this box has none of.
     */
    void seat(Engagement member) {
      Acting.system(() -> new RoomStore(db).updateRoster(ROOM, Roster.solo(member).toJson()));
    }

    /**
     * Posts {@code body}, marked {@code mark}, in the room as {@code run}'s agent: the room is the
     * one its spec lives in, so it is the run's own work.
     */
    void post(RunStore.RunRow run, String body, MessageStore.Mark mark) {
      var agent = agent(run);
      Actor.call(
              agent,
              () ->
                  operations.postRoomMessage(
                      ROOM, new SpecMessageRequest(body, null, mark), agent.handle()))
          .orThrow();
    }

    /** The principal {@code run}'s credential resolves to at a door, its work included. */
    private Actor agent(RunStore.RunRow run) {
      return new RoleRule(() -> OWNER, new FdeStore(db), new SpecStore(db))
          .principal(new RoleRule.Credential.Run(run))
          .orElseThrow();
    }

    /** A build run {@code id} of {@code spec} on {@code node}'s box, as a dispatch records one. */
    RunStore.RunRow run(String id, String spec, String node) {
      var runs = new RunStore(db);
      Acting.system(
          () ->
              runs.create(
                  id,
                  PROJECT,
                  spec,
                  node,
                  "build",
                  "claude-code",
                  "agent/" + spec,
                  null,
                  null,
                  null,
                  "/var/log/sail/" + id + ".log",
                  "sail-agent-" + id));
      runs.stampActivity(id, Duration.ZERO);
      return runs.findById(id).orElseThrow();
    }

    /** The spec's review as the loop leaves one that passed below its gate: one finding open. */
    void reviewPassedWithAFindingOpen() {
      var reviews = new ReviewStore(db);
      Acting.system(
          () -> {
            var review = reviews.createReview(SPEC, 1);
            reviews.addFinding(
                reviews.createStage(review, "logic", "agent"),
                Finding.create(
                    Finding.Severity.LOW,
                    Finding.Category.LOGIC,
                    "docks/Berth.java",
                    12,
                    12,
                    "The berth's depth is unchecked",
                    "A ferry deeper than the berth is admitted.",
                    "Berth.admit never reads draft.",
                    null,
                    0.6));
            reviews.pass(review, () -> {});
          });
    }

    /**
     * Publishes one event of each well-known type the routes above did not, and answers the last,
     * which is the room's, so both streams are sent it. An event whose emitter a test can call is
     * what that emitter writes. Every other is its envelope alone: data typed here would pin
     * nothing the server writes, so a client is shown none.
     */
    Event publishEveryOtherWellKnownEvent(RunStore.RunRow run) {
      bus.publish(SyncTransitionEvents.health("main@host", false, "unreachable", "refused", "box"));
      bus.publish(SyncTransitionEvents.health("main@host", true, null, null, "box"));
      synced("spec", SPEC, "review", "in_progress", Map.of("branch", run.branch()));
      synced("run", run.id(), "running", "failed", Map.of("spec_id", SPEC, "exit_code", 2));
      bus.publish(StopOperations.cancelEvent(run, Event.WellKnownData.SOURCE_OPERATOR, "uday"));
      new RunPresenceEmitter(
              new RunStore(db),
              bus,
              OWNER::handle,
              () -> AT.plus(RunPresence.THRESHOLD).plusMillis(1))
          .sweep();
      new LoopNarrator(null, bus, null)
          .publishGuardrail(PROJECT, SPEC, "stall (20m)", "stopped the run");
      Actor.call(
              agent(run),
              () -> operations.recordRoomConversation(ROOM, "claude", "s-1", "startup", null))
          .orThrow();
      Stream.of(
              WellKnownTypes.AGENT_SESSION_STARTED,
              WellKnownTypes.AGENT_SESSION_COMPLETED,
              WellKnownTypes.AGENT_STOP_NUDGED,
              WellKnownTypes.AGENT_TOOL_STARTED,
              WellKnownTypes.AGENT_TOOL_FINISHED,
              WellKnownTypes.AGENT_LOG_CHUNK,
              WellKnownTypes.SNAPSHOT_RESTORED,
              WellKnownTypes.SNAPSHOT_DELETED,
              WellKnownTypes.SPEC_STRANDED)
          .forEach(type -> bus.publish(envelope(SPEC, type)));
      var runs = new RunStore(db);
      Acting.system(
          () ->
              runs.recordLaunch(
                  run.id(),
                  new RunStore.Launched(
                      null, null, null, new RunStore.RunLogin("default", "api-key"), null)));
      var launched = runs.findById(run.id()).orElseThrow();
      Stream.of(new LoginOutcome.Refused(), new LoginOutcome.Limited("15:00 UTC"))
          .map(outcome -> new RunReport(RunUsage.NONE, outcome))
          .forEach(
              report ->
                  new RunReports(read -> report, RunLauncher.Room.NONE, bus::publish)
                      .finish(runs, launched, recorded -> true));
      return Stream.of(
              WellKnownTypes.SPEC_ENGAGED,
              WellKnownTypes.SPEC_DISENGAGED,
              WellKnownTypes.SPEC_ENGAGE_FAILED,
              WellKnownTypes.AGENT_TURN_BEGAN,
              WellKnownTypes.AGENT_TEXT_DELTA,
              WellKnownTypes.AGENT_TOOL_CALL,
              WellKnownTypes.AGENT_TURN_ENDED,
              WellKnownTypes.PTY_SESSION_STARTED,
              WellKnownTypes.PTY_SESSION_ATTACHED,
              WellKnownTypes.PTY_SESSION_ENDED)
          .map(type -> bus.publish(envelope(ROOM, type)))
          .toList()
          .getLast();
    }

    /** Publishes what main narrates when a node's {@code type} row moves between two statuses. */
    private void synced(String type, String id, String from, String to, Map<String, Object> row) {
      var snapshot = new LinkedHashMap<>(row);
      snapshot.put("project", PROJECT);
      SyncTransitionEvents.eventsFor(
              new SyncTransition(type, id, from, to, snapshot),
              spec -> PROJECT,
              spec -> "passed",
              "box")
          .forEach(bus::publish);
    }

    String token(String handle, Role role) {
      var fde = new FdeStore(db).add(handle, null, null, role.attribute());
      var token = new TokenStore(db).create(handle, role.attribute(), fde.id(), null).token();
      holders.put(token, handle);
      return token;
    }

    /**
     * Records {@code method path}, sent as {@code token} with {@code body}, and the answer the
     * router gave, under {@code name}; answers the response body.
     */
    Map<String, Object> record(
        Map<String, String> goldens,
        String name,
        String method,
        String path,
        String token,
        Map<String, Object> body)
        throws Exception {
      return golden(goldens, name, method, path, body, send(method, path, token, body));
    }

    /**
     * Records the server's own failure, {@code code}, as golden {@code name}: the one 5xx a golden
     * may hold is the one it was written to show.
     */
    void recordFailure(
        Map<String, String> goldens,
        String name,
        String code,
        String method,
        String path,
        String token,
        Map<String, Object> body)
        throws Exception {
      var parsed = golden(goldens, name, method, path, body, exchange(method, path, token, body));
      assertEquals(code, ((Map<?, ?>) parsed.get("error")).get("code"), name);
    }

    private Map<String, Object> golden(
        Map<String, String> goldens,
        String name,
        String method,
        String path,
        Map<String, Object> body,
        HttpResponse<String> response) {
      var parsed =
          response.body().isEmpty() ? Map.<String, Object>of() : YamlUtil.parseMap(response.body());
      var request = new LinkedHashMap<String, Object>();
      request.put("method", method);
      request.put("path", path);
      if (body != null) {
        request.put("body", body);
      }
      var answer = new LinkedHashMap<String, Object>();
      answer.put("status", response.statusCode());
      response
          .headers()
          .firstValue(ChangeFeed.SEQ_HEADER)
          .ifPresent(seq -> answer.put("headers", Map.of("seq", seq)));
      if (!response.body().isEmpty()) {
        answer.put("body", untimed(parsed));
      }
      var exchange = Map.of("request", request, "response", answer);
      goldens.put(name + ".json", json(Scrub.canonical(exchange, this::renamed)));
      return parsed;
    }

    /** {@code body} with every time a workspace door measured, its {@code timings}, as 0. */
    private static Map<String, Object> untimed(Map<String, Object> body) {
      if (!(body.get("timings") instanceof Map<?, ?> timings)) {
        return body;
      }
      var steady = new LinkedHashMap<>(body);
      steady.put("timings", zeroed(timings));
      return steady;
    }

    private static Object zeroed(Object measured) {
      return switch (measured) {
        case Number taken -> 0;
        case Map<?, ?> steps -> {
          var zeroed = new LinkedHashMap<Object, Object>();
          steps.forEach((step, taken) -> zeroed.put(step, zeroed(taken)));
          yield zeroed;
        }
        case null, default -> measured;
      };
    }

    /** Makes Incus list workspace {@code dock} of harbor, {@code handle}'s by their id. */
    void holdsDockOf(String handle) {
      var dock = dock(idOf(handle));
      incus
          .onOk("incus list ^harbor-dock$", "[" + dock + "]")
          .onOk("incus list --format json", "[" + TEMPLATE + "," + dock + "]");
    }

    /** Makes Incus list harbor's template and {@code workspaces}, and nothing else. */
    void lists(String... workspaces) {
      var listed = new ArrayList<String>(List.of(TEMPLATE));
      listed.addAll(List.of(workspaces));
      incus.onOk("incus list --format json", "[" + String.join(",", listed) + "]");
    }

    /**
     * Records every answer of a project's template the app draws, each named for the design's frame
     * it serves ({@code design/workspaces-mockup.html}): lighthouse with no template, as a member
     * sees it (2c) and as an admin (2d) with the refusals Build Template can meet (2d), its build
     * under way as its builder sees it (2e) and as everyone else does, a second admin's request
     * joining it (2f), harbor's template built and lighthouse's found current (2g), and a build
     * that failed, was cut short and ended, or was cut short with no one to end it yet (2h). The
     * refusals the app never meets, since it offers no button, carry no frame: a member's, a
     * viewer's and on a node anyone's but its own person's.
     */
    void recordTemplates(
        Map<String, String> goldens, String dana, String ike, String ana, String vik)
        throws Exception {
      Acting.system(() -> new ProjectStore(db).upsert(LIGHTHOUSE));
      var lighthouse = "/v1/projects/lighthouse/template";
      var build = lighthouse + "%3Abuild";
      record(goldens, "template-2c-missing", "GET", lighthouse, ana, null);
      record(goldens, "template-2d-missing", "GET", lighthouse, dana, null);
      record(goldens, "refusal-template-admin-only", "POST", build, ana, null);
      record(goldens, "refusal-template-read-only", "POST", build, vik, null);
      config.set(new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "uday", "uday-box"));
      try {
        record(goldens, "refusal-template-node", "POST", build, dana, null);
      } finally {
        config.set(OWNER);
      }
      record(
          goldens,
          "refusal-template-2d-no-resources",
          "POST",
          "/v1/projects/harbor/template%3Abuild",
          dana,
          null);
      record(goldens, "refusal-template-2d-runner-not-ready", "POST", build, dana, null);
      var hostYaml = SailPaths.hostConfigPath();
      YamlUtil.dumpToFile(
          new HostYaml(
                  "dir",
                  "default",
                  null,
                  "incusbr0",
                  "singlr-base",
                  "ubuntu/24.04",
                  "6.0",
                  null,
                  null)
              .toMap(),
          hostYaml);
      try {
        operations.useCredentialProbe(url -> true);
        new FdeStore(db).logins().storeGit(idOf("uday"), "github.com", "ghp_the_box_persons_own");
        record(goldens, "refusal-template-2d-login-not-stored", "POST", build, dana, null);
        var started = AT.minusSeconds(250);
        var step =
            TemplateRecord.started("dana", started, TemplateBuild.STEPS).at(9, AT.minusSeconds(40));
        step.write(recordOf("lighthouse"));
        HeldLocks.whileHeld(
            ContainerConfig.lockOf(SailPaths.locksDir(), "lighthouse--tmpl"),
            () -> {
              record(goldens, "template-2e-building", "GET", lighthouse, dana, null);
              record(goldens, "template-2f-building", "GET", lighthouse, ana, null);
              record(goldens, "template-2f-joined", "POST", build, ike, null);
              return null;
            });
        TemplateRecord.started("dana", started, TemplateBuild.STEPS)
            .at(13, AT.minusSeconds(90))
            .ended(
                TemplateRecord.Outcome.FAILED,
                AT.minusSeconds(60),
                "github.com did not give lighthouse's repo lamp to the token stored for it: the"
                    + " token may not read the repo, or the repo may not exist.",
                Remedy.of(
                    "Store a token for github.com that reads it, then try again: ",
                    Remedy.sail("git login", "github.com"),
                    "."))
            .write(recordOf("lighthouse"));
        record(goldens, "template-2h-failed", "GET", lighthouse, dana, null);
        step.ended(
                TemplateRecord.Outcome.INTERRUPTED,
                AT.minusSeconds(30),
                TemplateBuild.CUT_SHORT,
                TemplateBuild.RESUMES)
            .write(recordOf("lighthouse"));
        record(goldens, "template-2h-interrupted", "GET", lighthouse, dana, null);
        step.write(recordOf("lighthouse"));
        record(goldens, "template-2h-cut-pending", "GET", lighthouse, dana, null);
        var built =
            TemplateRecord.started("dana", started, TemplateBuild.STEPS)
                .at(TemplateBuild.STEPS, AT.minusSeconds(20))
                .ended(TemplateRecord.Outcome.BUILT, AT.minusSeconds(10), null, null);
        built.write(recordOf("harbor"));
        built.write(recordOf("lighthouse"));
        record(goldens, "template-2g-built", "GET", "/v1/projects/harbor/template", dana, null);
        operations.useCredentialProbe(url -> false);
        incus
            .onOk(
                "incus list --format json",
                "["
                    + TEMPLATE
                    + ","
                    + TestWorkspaces.template(
                        "lighthouse", ProjectProvisioner.inputs(LIGHTHOUSE, Optional.empty()))
                    + "]")
            .onOk("incus snapshot list lighthouse--tmpl --format json", TestWorkspaces.V1);
        record(goldens, "template-2g-current", "POST", build, dana, null);
      } finally {
        Files.deleteIfExists(hostYaml);
      }
    }

    /**
     * Records what can start in a workspace, each case the app draws: its owner signed in, with a
     * harness the project does not install; a stopped one whose person stored no login; an admin in
     * another person's; one whose person is gone; and a member refused another person's. Harbor is
     * defined with its agent installation only meanwhile, so no other answer changes.
     */
    void recordAgents(Map<String, String> goldens, String ana, String dana) throws Exception {
      Acting.system(
          () ->
              new ProjectStore(db)
                  .upsert(
                      TestDefinitions.yaml(
                          "name: harbor\nagent:\n  type: claude-code\n  install: [claude-code]\n")));
      var claude = Harnesses.of("claude-code");
      var logins = new FdeStore(db).logins();
      logins.store(idOf("dana"), claude, "work", "api-key", "sk-ant-api03-golden-dana-work");
      incus
          .onOk(
              "incus list ^harbor-berth$",
              "[" + workspace("berth", "Running", "dana", idOf("dana")) + "]")
          .onOk(
              "incus list ^harbor-quay$",
              "[" + workspace("quay", "Stopped", "ana", idOf("ana")) + "]")
          .onOk(
              "incus list ^harbor-wreck$",
              "[" + workspace("wreck", "Stopped", "zed", "fde_zed") + "]")
          .onOk("incus exec harbor-berth ", "");
      var agents = "/v1/projects/" + PROJECT + "/workspaces/%s/agents";
      record(goldens, "workspace-agents", "GET", agents.formatted("berth"), dana, null);
      record(
          goldens, "workspace-agents-stopped-no-login", "GET", agents.formatted("quay"), ana, null);
      record(goldens, "workspace-agents-not-yours", "GET", agents.formatted("dock"), dana, null);
      record(goldens, "workspace-agents-owner-gone", "GET", agents.formatted("wreck"), dana, null);
      record(
          goldens,
          "refusal-forbidden-not-assignee-agents",
          "GET",
          agents.formatted("dock"),
          ana,
          null);
      Acting.system(() -> new ProjectStore(db).upsert(HARBOR));
    }

    /**
     * Records a chat's every answer and refusal as the app meets them: dana opens one in berth, her
     * running workspace, sends a message, reads the turn Claude Code took (a call and its result),
     * sends again, and while that turn is open sends once more, held; then ends it, and it takes no
     * message. A chat in another person's workspace, of a harness with no chat form, by a person
     * with no login stored, read, sent to or ended by another person, or named by no id, is
     * refused, and a message whose process will not start is refused as such.
     */
    void recordChats(Map<String, String> goldens, String ana, String dana) throws Exception {
      Acting.system(
          () ->
              new ProjectStore(db)
                  .upsert(
                      TestDefinitions.yaml(
                          "name: harbor\nagent:\n  type: claude-code\n  install: [claude-code]\n")));
      lists(
          workspace("dock", "Running", "uday", idOf("uday")),
          workspace("berth", "Running", "dana", idOf("dana")));
      var chats = "/v1/projects/" + PROJECT + "/workspaces/%s/chats";
      var claude =
          Map.<String, Object>of("harness", "claude-code", "mode", "default", "inside", false);
      var opened = record(goldens, "chat-opened", "POST", chats.formatted("berth"), dana, claude);
      record(
          goldens,
          "refusal-chat-not-your-workspace",
          "POST",
          chats.formatted("dock"),
          dana,
          claude);
      record(
          goldens,
          "refusal-chat-no-chat-form",
          "POST",
          chats.formatted("berth"),
          dana,
          Map.of("harness", "codex", "mode", "full-access"));
      record(
          goldens,
          "refusal-chat-mode-not-offered",
          "POST",
          chats.formatted("berth"),
          dana,
          Map.of("harness", "claude-code", "mode", "bypassPermissions"));
      record(
          goldens,
          "refusal-chat-inside-invalid",
          "POST",
          chats.formatted("berth"),
          dana,
          Map.of("harness", "claude-code", "mode", "default", "inside", "yes"));
      record(
          goldens, "refusal-chat-login-not-stored", "POST", chats.formatted("quay"), ana, claude);
      var session = "/v1/sessions/" + opened.get("session");
      var messages = session + "/chat/messages";
      this.chats.refusing(new IOException("incus is not running"));
      recordFailure(
          goldens,
          "refusal-chat-launch-failed",
          "agent_launch_failed",
          "POST",
          messages,
          dana,
          Map.of("text", "hi"));
      this.chats.refusing(null);
      record(goldens, "chat-message-sent", "POST", messages, dana, Map.of("text", "hi"));
      said(
          Recorded.line("04b-set-permission-mode-and-background-task", 148),
          Recorded.line("01-baseline-live-flags", 13),
          Recorded.line("01-baseline-live-flags", 17),
          Recorded.line("02b-relay-stdio", 138));
      record(goldens, "chat-items", "GET", session + "/chat?after=0", dana, null);
      record(goldens, "chat-message-again", "POST", messages, dana, Map.of("text", "again"));
      said(Recorded.line("04b-set-permission-mode-and-background-task", 148));
      record(goldens, "chat-message-queued", "POST", messages, dana, Map.of("text", "and this"));
      said(Recorded.line("02b-relay-stdio", 138));
      record(goldens, "chat-items-after", "GET", session + "/chat?after=5", dana, null);
      record(goldens, "refusal-chat-not-found", "GET", session + "/chat", ana, null);
      record(
          goldens,
          "refusal-chat-message-not-found",
          "POST",
          messages,
          ana,
          Map.of("text", "mine now"));
      record(goldens, "refusal-chat-end-not-found", "POST", session + ":end", ana, null);
      record(goldens, "refusal-chat-invalid-id", "GET", "/v1/sessions/nobody/chat", dana, null);
      record(
          goldens, "refusal-chat-after-invalid", "GET", session + "/chat?after=last", dana, null);
      record(goldens, "chat-ended", "POST", session + ":end", dana, null);
      record(goldens, "chat-items-ended", "GET", session + "/chat?after=10", dana, null);
      record(goldens, "refusal-chat-ended", "POST", messages, dana, Map.of("text", "more"));
      Acting.system(() -> new ProjectStore(db).upsert(HARBOR));
    }

    /**
     * Records every answer, stop and change of mode a chat's person makes, and each refusal, as the
     * app meets them: dana opens a chat in berth and sets its mode before any process holds it; its
     * turn asks to run a command, which she denies with her words, then to run another always, to
     * run one it offers no always for, a question she answers with two choices and her own words,
     * and a plan she approves; a request she stops; and a mode set between turns. A second answer,
     * one to a request withdrawn or never asked, a reply a request does not take, a change of mode
     * mid-turn or to one no chat takes, another person at each route, and an answer to a chat ended
     * are refused.
     */
    void recordChatAnswers(Map<String, String> goldens, String ana, String dana) throws Exception {
      Acting.system(
          () ->
              new ProjectStore(db)
                  .upsert(
                      TestDefinitions.yaml(
                          "name: harbor\nagent:\n  type: claude-code\n  install: [claude-code]\n")));
      lists(workspace("berth", "Running", "dana", idOf("dana")));
      var opened =
          send(
              "POST",
              "/v1/projects/" + PROJECT + "/workspaces/berth/chats",
              dana,
              Map.of("harness", "claude-code", "mode", "default", "inside", false));
      var session = "/v1/sessions/" + YamlUtil.parseJsonLine(opened.body(), 1 << 16).get("session");
      var requests = session + "/chat/requests/";
      var mode = session + "/chat/mode";
      record(goldens, "chat-mode-kept", "PUT", mode, dana, Map.of("mode", "acceptEdits"));
      send("POST", session + "/chat/messages", dana, Map.of("text", "hi"));
      said(
          Recorded.line("04b-set-permission-mode-and-background-task", 148),
          Recorded.line("05-answer-twice", 16));
      var asked = "bda0b4cf-c4f6-4219-bd84-0f0617292889";
      record(goldens, "refusal-chat-mode-turn-open", "PUT", mode, dana, Map.of("mode", "plan"));
      record(
          goldens,
          "refusal-chat-mode-not-a-chat-mode",
          "PUT",
          mode,
          dana,
          Map.of("mode", "bypassPermissions"));
      record(goldens, "refusal-chat-request-not-yours", "POST", requests + asked, ana, allow());
      record(
          goldens,
          "refusal-chat-interrupt-not-yours",
          "POST",
          session + "/chat:interrupt",
          ana,
          null);
      record(goldens, "refusal-chat-mode-not-yours", "PUT", mode, ana, Map.of("mode", "plan"));
      record(
          goldens,
          "refusal-chat-request-not-found",
          "POST",
          requests + "7f35b673-a8fa-4967-a1d0-a52e2b28cf73",
          dana,
          allow());
      record(
          goldens,
          "refusal-chat-answer-invalid",
          "POST",
          requests + asked,
          dana,
          Map.of("decision", "allow_always", "rules", List.of("Bash(*)")));
      record(
          goldens,
          "refusal-chat-answer-not-taken",
          "POST",
          requests + asked,
          dana,
          Map.of("decision", "answer", "answers", Map.of("Which color?", Map.of("own", "teal"))));
      record(
          goldens,
          "chat-request-denied",
          "POST",
          requests + asked,
          dana,
          Map.of("decision", "deny", "message", "use the existing spinner instead"));
      record(goldens, "refusal-chat-request-answered", "POST", requests + asked, dana, allow());
      said(Recorded.line("02b-relay-stdio", 99));
      record(
          goldens,
          "chat-request-allowed-always",
          "POST",
          requests + "d3b09c29-b79d-4151-b490-d70fdf7adb18",
          dana,
          Map.of("decision", "allow_always"));
      said(Recorded.line("01-always-rule-wrappers", 108));
      var unsaid = (String) Recorded.parsed("01-always-rule-wrappers", 108).get("request_id");
      record(
          goldens,
          "refusal-chat-always-not-offered",
          "POST",
          requests + unsaid,
          dana,
          Map.of("decision", "allow_always"));
      record(goldens, "chat-request-allowed", "POST", requests + unsaid, dana, allow());
      said(Recorded.line("02-question-multi-select-and-own-words", 32));
      var question = requests + "82da6f15-368c-4201-9a85-f925427a7c03";
      record(
          goldens,
          "refusal-chat-answer-not-a-choice",
          "POST",
          question,
          dana,
          answers(Map.of("chosen", List.of("Teal"))));
      record(
          goldens,
          "chat-question-answered",
          "POST",
          question,
          dana,
          answers(Map.of("chosen", List.of("Red", "Blue"))));
      said(Recorded.line("03b-plan-exit-plan-mode", 58));
      record(
          goldens,
          "chat-plan-approved",
          "POST",
          requests + "712631d6-6601-4153-9c1e-397ba123bb15",
          dana,
          allow());
      said(Recorded.line("04a-cancel-request-by-interrupt", 16));
      record(goldens, "chat-stopping", "POST", session + "/chat:interrupt", dana, null);
      said(
          Recorded.line("04a-cancel-request-by-interrupt", 20),
          Recorded.line("04a-cancel-request-by-interrupt", 21),
          Recorded.line("04a-cancel-request-by-interrupt", 23),
          Recorded.line("04a-cancel-request-by-interrupt", 24));
      record(
          goldens,
          "refusal-chat-request-withdrawn",
          "POST",
          requests + "dc0b76a9-43c6-4484-a47b-1b7c1e5a4b10",
          dana,
          allow());
      record(goldens, "chat-not-stopping", "POST", session + "/chat:interrupt", dana, null);
      record(goldens, "chat-mode-set", "PUT", mode, dana, Map.of("mode", "plan"));
      record(goldens, "chat-items-answered", "GET", session + "/chat?after=0", dana, null);
      send("POST", session + ":end", dana, null);
      record(goldens, "refusal-chat-answer-ended", "POST", requests + asked, dana, allow());
      Acting.system(() -> new ProjectStore(db).upsert(HARBOR));
    }

    private static Map<String, Object> allow() {
      return Map.of("decision", "allow");
    }

    /** Dana's answer to probe 5's two questions: colors as {@code colors}, size in her words. */
    private static Map<String, Object> answers(Map<String, Object> colors) {
      var answers = new LinkedHashMap<String, Object>();
      answers.put("Which colors?", colors);
      answers.put("Which size?", Map.of("chosen", List.of(), "own", "teal, but only on weekends"));
      return Map.of("decision", "answer", "answers", answers);
    }

    /** The chat process started last says {@code lines}, and its reader has taken them. */
    private void said(String... lines) {
      var process = chats.latest();
      process.says(lines);
      process.settle();
    }

    /**
     * Records what can start in the drive's own workspaces of harbor, as uday asks, so the app's
     * New Session frames name the rows they open from: dock, uday's and running, with a Claude Code
     * login and a Codex login, holding Codex's own sign-in and not Claude Code's; dock once it
     * holds both, and while it holds neither; pier, uday's and stopped; quay, ana's; dock with no
     * Claude Code login stored, then with harbor installing Claude Code alone; and pier once its
     * person is gone.
     */
    void recordDriveAgents(Map<String, String> goldens) throws Exception {
      var uday = new AuthSessionStore(db).create(idOf("uday"), Duration.ofMinutes(30)).token();
      holders.put(uday, "uday");
      installs("claude-code, codex");
      var claude = Harnesses.of("claude-code");
      var codex = Harnesses.of("codex");
      var logins = new FdeStore(db).logins();
      var udayId = idOf("uday");
      logins.store(udayId, claude, "default", "api-key", "sk-ant-api03-golden-uday-default");
      logins.store(udayId, codex, "work", "api-key", "sk-proj-golden-uday-work");
      incus
          .onOk(
              "incus list ^harbor-dock$", "[" + workspace("dock", "Running", "uday", udayId) + "]")
          .onOk(
              "incus list ^harbor-pier$", "[" + workspace("pier", "Stopped", "uday", udayId) + "]")
          .onOk(
              "incus list ^harbor-quay$",
              "[" + workspace("quay", "Running", "ana", idOf("ana")) + "]")
          .onOk("incus exec harbor-dock ", ".claude/.credentials.json\n.codex/auth.json\n");
      var agents = "/v1/projects/" + PROJECT + "/workspaces/%s/agents";
      record(
          goldens, "workspace-agents-dock-signed-in", "GET", agents.formatted("dock"), uday, null);
      incus.onOk("incus exec harbor-dock ", "");
      record(
          goldens, "workspace-agents-dock-signed-out", "GET", agents.formatted("dock"), uday, null);
      incus.onOk("incus exec harbor-dock ", ".codex/auth.json\n");
      record(goldens, "workspace-agents-dock", "GET", agents.formatted("dock"), uday, null);
      record(goldens, "workspace-agents-pier", "GET", agents.formatted("pier"), uday, null);
      record(goldens, "workspace-agents-quay", "GET", agents.formatted("quay"), uday, null);
      logins.remove(udayId, claude, "default");
      record(
          goldens, "workspace-agents-dock-no-login", "GET", agents.formatted("dock"), uday, null);
      logins.store(udayId, claude, "default", "api-key", "sk-ant-api03-golden-uday-default");
      installs("claude-code");
      record(
          goldens,
          "workspace-agents-dock-not-installed",
          "GET",
          agents.formatted("dock"),
          uday,
          null);
      incus.onOk(
          "incus list ^harbor-pier$", "[" + workspace("pier", "Stopped", "ben", "fde_ben") + "]");
      record(
          goldens, "workspace-agents-pier-owner-gone", "GET", agents.formatted("pier"), uday, null);
      logins.remove(udayId, claude, "default");
      logins.remove(udayId, codex, "work");
      Acting.system(() -> new ProjectStore(db).upsert(HARBOR));
    }

    /**
     * Records a person storing a login from the app, as uday with a Codex login and none of Claude
     * Code's, the run he started earlier ended: his logins listed; an API key stored as "default",
     * his first (`login-stored`, no note); the same name stored again (`login-replaced`, its note
     * on the sessions already running); his logins with it the default, a second key stored beside
     * it (`work`, not the default) and made the one in use, and asked again once it is removed
     * (`refusal-not-found-login`); the first removed (`login-removed`); each refusal of a value,
     * and of a name; then his logins while he has a Claude Code login that is not the default,
     * where the app asks a name. Every value sent is an obvious fake.
     */
    void recordLogins(Map<String, String> goldens) throws Exception {
      var uday = new AuthSessionStore(db).create(idOf("uday"), Duration.ofMinutes(30)).token();
      holders.put(uday, "uday");
      installs("claude-code, codex");
      var claude = Harnesses.of("claude-code");
      var codex = Harnesses.of("codex");
      var logins = new FdeStore(db).logins();
      var udayId = idOf("uday");
      logins.store(udayId, codex, "work", "api-key", "sk-proj-golden-uday-work");
      record(goldens, "logins", "GET", "/v1/logins", uday, null);
      var claudeLogin = "/v1/logins/claude-code/%s";
      var fake = "sk-ant-api03-golden-fake-key";
      Acting.system(() -> new RunStore(db).finish(RUN, "running", "completed", 0, null));
      record(
          goldens,
          "login-stored",
          "PUT",
          claudeLogin.formatted("default"),
          uday,
          Map.of("kind", "api-key", "value", fake));
      record(
          goldens,
          "login-replaced",
          "PUT",
          claudeLogin.formatted("default"),
          uday,
          Map.of("kind", "api-key", "value", fake));
      record(goldens, "logins-with-a-default", "GET", "/v1/logins", uday, null);
      record(
          goldens,
          "login-stored-beside-the-default",
          "PUT",
          claudeLogin.formatted("work"),
          uday,
          Map.of("kind", "api-key", "value", fake));
      record(goldens, "login-used", "POST", claudeLogin.formatted("work%3Ause"), uday, null);
      logins.remove(udayId, claude, "work");
      record(
          goldens,
          "refusal-not-found-login",
          "POST",
          claudeLogin.formatted("work%3Ause"),
          uday,
          null);
      record(goldens, "login-removed", "DELETE", claudeLogin.formatted("default"), uday, null);
      var refused =
          Map.of(
              "empty",
              "   ",
              "lines",
              fake + "\n" + fake,
              "long",
              "k".repeat(16 * 1024 + 1),
              "characters",
              "sk-ant-api03 golden fake key");
      for (var value : refused.entrySet()) {
        record(
            goldens,
            "refusal-invalid-request-login-" + value.getKey(),
            "PUT",
            claudeLogin.formatted("default"),
            uday,
            Map.of("kind", "api-key", "value", value.getValue()));
      }
      record(
          goldens,
          "refusal-invalid-request-login-name",
          "PUT",
          claudeLogin.formatted("Work"),
          uday,
          Map.of("kind", "api-key", "value", fake));
      logins.store(udayId, claude, "work", "api-key", "sk-ant-api03-golden-uday-work");
      logins.store(udayId, claude, "home", "api-key", "sk-ant-api03-golden-uday-home");
      logins.remove(udayId, claude, "work");
      record(goldens, "logins-no-default", "GET", "/v1/logins", uday, null);
      logins.remove(udayId, claude, "home");
      logins.remove(udayId, codex, "work");
      Acting.system(() -> new ProjectStore(db).upsert(HARBOR));
    }

    /** Defines harbor with its agent installation alone, installing {@code harnesses}. */
    private void installs(String harnesses) {
      Acting.system(
          () ->
              new ProjectStore(db)
                  .upsert(
                      TestDefinitions.yaml(
                          "name: harbor\nagent:\n  type: claude-code\n  install: ["
                              + harnesses
                              + "]\n")));
    }

    private Path recordOf(String project) {
      return TemplateRecord.fileOf(projects, project);
    }

    String idOf(String handle) {
      return new FdeStore(db).byHandle(handle).orElseThrow().id();
    }

    /** Sends what a golden needs written first, which must succeed. */
    void seed(String method, String path, String token, Map<String, Object> body) throws Exception {
      var response = send(method, path, token, body);
      if (response.statusCode() >= 300) {
        fail(method + " " + path + " answered " + response.body());
      }
    }

    HttpResponse<String> send(String method, String path, String token, Map<String, Object> body)
        throws Exception {
      var response = exchange(method, path, token, body);
      if (response.statusCode() >= 500) {
        fail(method + " " + path + " answered " + response.body());
      }
      return response;
    }

    private HttpResponse<String> exchange(
        String method, String path, String token, Map<String, Object> body) throws Exception {
      var builder =
          HttpRequest.newBuilder(URI.create(base() + path))
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(YamlUtil.dumpJson(body)));
      if (token != null) {
        builder.header("Authorization", "Bearer " + token);
      }
      try (var client = HttpClient.newHttpClient()) {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      }
    }

    /**
     * What a caller is answered when every stream the box allows is open, as the two this test
     * reads are: the status, and how long the server asks it to wait.
     */
    Map<String, Object> fullHouse(String token) throws Exception {
      assertEquals(STREAMS, streams.size());
      var request =
          HttpRequest.newBuilder(URI.create(base() + "/v1/events/stream"))
              .header("Authorization", "Bearer " + token)
              .build();
      try (var client = HttpClient.newHttpClient()) {
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        response.body().close();
        return head(response);
      }
    }

    /**
     * What a stream resumed from {@code since} as {@code token} is first sent, up to and with the
     * frame that holds the line {@code last}: its replay, every frame as written. It is read
     * through a handler of its own, since the box's allows only the streams the rest of the test
     * holds open.
     */
    /** The position before anything this box's database holds: where a feed of all of it starts. */
    String beginning() {
      var positions = new FeedPositions(db);
      return positions.at(positions.catalog(), 0);
    }

    String resumed(String token, String since, String last) {
      return assertTimeoutPreemptively(
          Duration.ofSeconds(BusTesting.DELIVERY_TIMEOUT_SECONDS),
          () -> replay(token, since, last));
    }

    private String replay(String token, String since, String last) throws Exception {
      var alone = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      alone.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      PrivateAttributes.context(
          alone,
          "/v1/events/stream",
          new SseHandler(
              bus, auth, SseHandler.RoomTurns.NONE, operations.changes(), 1, streamClock));
      alone.start();
      try {
        return replay(alone, token, since, last);
      } finally {
        alone.stop(0);
      }
    }

    private static String replay(HttpServer alone, String token, String since, String last)
        throws Exception {
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:" + alone.getAddress().getPort() + "/v1/events/stream"))
              .header("Authorization", "Bearer " + token)
              .header("Last-Event-ID", since)
              .GET()
              .build();
      try (var client = HttpClient.newHttpClient()) {
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        try (var reader =
            new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
          var lines = new ArrayList<String>();
          var reached = false;
          for (var line = reader.readLine();
              !(reached && line.isEmpty());
              line = reader.readLine()) {
            lines.add(line);
            reached |= line.equals(last);
          }
          lines.add("");
          return String.join("\n", lines) + "\n";
        }
      }
    }

    /** Opens {@code path}'s stream as {@code token}, once it has subscribed. */
    OpenStream stream(String path, String token) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base() + path))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      var client = HttpClient.newHttpClient();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, response.statusCode());
      var stream = new OpenStream(client, head(response), response.body());
      streams.add(stream);
      stream.awaitLine(": subscribed");
      return stream;
    }

    /**
     * Ends every open stream as a revoked caller's ends, once each has written {@code last} and,
     * its clock moved past a heartbeat with nothing left to send, the comment that keeps an idle
     * stream open: {@code token} is revoked, the streams' clock passes their next check, and an
     * event arrives for them to check on.
     */
    void end(String token, Event last) throws InterruptedException {
      for (var stream : streams) {
        stream.awaitLine(DATA + last.toJsonLine());
      }
      streamClock.advance(Duration.ofMillis(SseHandler.HEARTBEAT_MILLIS + 1));
      for (var stream : streams) {
        stream.awaitLine(": keepalive");
      }
      new AuthSessionStore(db).revoke(token);
      streamClock.advance(StreamRecheck.INTERVAL);
      bus.publish(envelope(ROOM, WellKnownTypes.BOARD_UPDATED));
    }

    /** {@code text} with every UUID renamed by the order this box first saw it. */
    String renamed(String text) {
      if (text.equals(HostInfo.hostname())) {
        return "box";
      }
      if (text.startsWith(OWN_PASSKEYS + "/")) {
        return OWN_PASSKEYS + "/" + renamed(text.substring(OWN_PASSKEYS.length() + 1));
      }
      if (pinned.containsKey(text)) {
        return pinned.get(text);
      }
      var positioned =
          POSITION
              .matcher(text)
              .replaceAll(
                  found ->
                      "golden."
                          + found.group(2)
                          + "."
                          + (found.group(3).equals("0")
                              ? "0"
                              : String.format("%012d", Long.parseLong(found.group(2)))));
      return UUID.matcher(positioned)
          .replaceAll(
              found ->
                  ids.computeIfAbsent(
                      found.group(),
                      id -> String.format("00000000-0000-7000-8000-%012d", ids.size() + 1)));
    }

    /** The status of {@code response} and the headers a stream's reader acts on, where sent. */
    private static Map<String, Object> head(HttpResponse<?> response) {
      var head = new LinkedHashMap<String, Object>();
      head.put("status", response.statusCode());
      for (var name : List.of("content-type", "retry-after")) {
        response.headers().firstValue(name).ifPresent(value -> head.put(name, value));
      }
      return head;
    }

    private String base() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
      unconfigured.stop(0);
      operations.close();
    }
  }

  /** An event stream being read to its end, line by line as it arrives. */
  private static final class OpenStream {
    private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
    private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final CompletableFuture<String> ended;
    private final Map<String, Object> head;

    OpenStream(HttpClient client, Map<String, Object> head, InputStream body) {
      this.head = head;
      this.ended =
          CompletableFuture.supplyAsync(
              () -> {
                try (client;
                    body) {
                  return readToEnd(body);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              },
              Executors.newVirtualThreadPerTaskExecutor());
    }

    private String readToEnd(InputStream body) throws IOException {
      var line = new ByteArrayOutputStream();
      for (var next = body.read(); next >= 0; next = body.read()) {
        raw.write(next);
        if (next == '\n') {
          lines.add(line.toString(StandardCharsets.UTF_8));
          line.reset();
        } else {
          line.write(next);
        }
      }
      return raw.toString(StandardCharsets.UTF_8);
    }

    void awaitLine(String wanted) throws InterruptedException {
      for (var line = lines.poll(BusTesting.DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
          !wanted.equals(line);
          line = lines.poll(BusTesting.DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        if (line == null) {
          fail("the stream never wrote '" + wanted + "'");
        }
      }
    }

    Map<String, Object> head() {
      return head;
    }

    String join() {
      return ended.join();
    }
  }

  /** What makes a recorded answer the same on every run, and nothing more. */
  private static final class Scrub {

    private Scrub() {}

    /**
     * The frames of {@code stream} the bus published, its row changes left out: where a change
     * falls among them is the stream thread's timing, not the wire's, and the changes are recorded
     * in their own order by a resumed stream.
     */
    static String busEvents(String stream) {
      return Arrays.stream(stream.split("\n\n", -1))
          .filter(frame -> !frame.startsWith("event: change\n"))
          .collect(Collectors.joining("\n\n"));
    }

    /** {@code value} with its keys sorted and every text {@code rename}d. */
    @SuppressWarnings("unchecked")
    static Object canonical(Object value, UnaryOperator<String> rename) {
      return switch (value) {
        case String text -> rename.apply(text);
        case Map<?, ?> map -> {
          var sorted = new TreeMap<String, Object>();
          ((Map<String, Object>) map)
              .forEach((key, item) -> sorted.put(key, canonical(item, rename)));
          yield sorted;
        }
        case List<?> list -> list.stream().map(item -> canonical(item, rename)).toList();
        case null, default -> value;
      };
    }

    /**
     * {@code stream} with each data line's JSON made canonical and the name of the machine that
     * wrote an event written as {@code box}, its framing as sent: lines are cut at their line feed
     * alone and joined by it again, so whatever else the server ends a line with stays.
     */
    static String stream(String stream, UnaryOperator<String> rename) {
      return Arrays.stream(stream.split("\n", -1))
          .map(
              line ->
                  line.startsWith(DATA)
                      ? DATA + data(line, rename)
                      : line.startsWith(ID) ? ID + rename.apply(line.substring(ID.length())) : line)
          .collect(Collectors.joining("\n"));
    }

    private static String data(String line, UnaryOperator<String> rename) {
      var data = new LinkedHashMap<>(YamlUtil.parseMap(line.substring(DATA.length())));
      data.computeIfPresent("host", (key, host) -> HostInfo.hostname().equals(host) ? "box" : host);
      return YamlUtil.dumpJsonValue(canonical(data, rename));
    }
  }
}
