/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Routes requests arriving over the local Unix-domain socket to a deliberately small surface: event
 * publishing (the in-container event helper), global spec CRUD (the in-container {@code spec} CLI
 * an agent uses), and {@code whoami}. Spec calls go through the very same {@link Operations} the
 * TCP API uses, so a spec an agent creates over the socket is indistinguishable from one the
 * engineer creates with {@code sail spec} — one database, one source of truth.
 *
 * <p>The socket is the transport, not the identity: every request must present a credential ({@code
 * Authorization: Bearer}). A sail-launched run presents the credential minted with its reservation
 * ({@code SAIL_RUN_CREDENTIAL}), resolving to its agent principal; an interactive session — an
 * engineer's shell, an IDE-spawned agent — presents the box's ambient credential (the {@code
 * box.credential} file sharing the socket's bind mount), resolving to the box FDE with its roster
 * role. Run credentials resolve first, so a launched agent is always its principal. A missing,
 * revoked, or unknown credential fails loud with 401 and never falls back to a client-chosen actor.
 * Spec writes are decided by the spec rule ({@code SpecAuthority}) either way; the events route is
 * run-only, and the dispatch/stop routes do not exist here at all.
 */
final class LocalApiRouter implements LocalApiHandler {

  private static final String SPECS = "/v1/specs";
  private static final String EVENTS = "/v1/events";
  private static final String WHOAMI = "/v1/whoami";
  private static final String RUN_MESSAGES = "/v1/run/messages";
  private static final String RUN_SESSION = "/v1/run/session";

  private final LocalLaneOperations operations;

  LocalApiRouter(LocalLaneOperations operations) {
    this.operations = operations;
  }

  @Override
  public ApiResponse handle(LocalApiRequest request) {
    try {
      return route(request);
    } catch (ApiException denied) {
      return ApiResponse.error(denied.failure());
    } catch (WriteRefused refused) {
      return ApiResponse.error(Refusals.exception(refused.refusal()).failure());
    } catch (IllegalArgumentException bad) {
      return problem(400, bad.getMessage());
    } catch (RuntimeException unexpected) {
      ApiLog.unexpected(request.method() + " " + request.path(), unexpected);
      return problem(500, unexpected.getMessage());
    }
  }

  /**
   * Who is on the other end of the socket: a sail-launched run authenticated by its minted
   * credential, or an interactive session covered by the box's ambient FDE credential. Each is the
   * {@link Actor} its request acts as; only the run caller may narrate run lifecycle on the events
   * route.
   */
  private sealed interface Caller {
    Actor actor();

    /**
     * A run's credential, acting as {@code actor} ({@link LocalLaneOperations#runActor}): a
     * read-only lane run — a {@code room} wake — is the read-and-converse room principal, never the
     * write-capable agent principal, decided at this boundary by the run row the server minted, not
     * by anything the session says. A full turn carries the agent principal a dispatched agent
     * holds, nothing more.
     */
    record Run(RunStore.RunRow run, Actor actor) implements Caller {}

    record Box(Actor fde) implements Caller {
      @Override
      public Actor actor() {
        return fde;
      }
    }
  }

  private ApiResponse route(LocalApiRequest request) {
    var run = operations.runForCredential(request.bearer()).orElse(null);
    if (run != null) {
      return operations
          .runActor(run)
          .map(actor -> actingAs(request, new Caller.Run(run, actor)))
          .orElseGet(
              () ->
                  problem(
                      403,
                      "This run acts for "
                          + (Strings.isBlank(run.owner())
                              ? "this box's FDE"
                              : "FDE '" + run.owner() + "'")
                          + ", whom this box cannot place: its roster has disabled that FDE or"
                          + " does not know it yet, or this box names no FDE of its own. Ask an"
                          + " admin to re-enable the FDE, run 'sudo sail sync' on a node that has"
                          + " not pulled main's roster, or name this box's FDE with 'sudo sail host"
                          + " config set sync-handle <you>'."));
    }
    return operations
        .boxActorForCredential(request.bearer())
        .map(actor -> actingAs(request, new Caller.Box(actor)))
        .orElseGet(
            () ->
                problem(
                    401,
                    "Missing or unknown credential. Requests on this socket must present the"
                        + " SAIL_RUN_CREDENTIAL of a live run (a finished run's credential is"
                        + " revoked) or this box's ambient box.credential as a bearer token."));
  }

  private ApiResponse actingAs(LocalApiRequest request, Caller caller) {
    return Actor.call(caller.actor(), () -> route(request, caller));
  }

  /** Routes an authenticated request, acting as its caller. */
  private ApiResponse route(LocalApiRequest request, Caller caller) {
    var path = request.path();
    if ("/v1/sync".equals(path)) {
      return "GET".equals(request.method())
          ? ApiResponse.ok(SyncViews.status(operations.syncStatus()))
          : problem(405, "Only GET is available.");
    }
    if ("/v1/conflicts".equals(path)) {
      return "GET".equals(request.method())
          ? ApiResponse.ok(SyncViews.conflicts(operations.conflicts()))
          : problem(405, "Only GET is available.");
    }
    if (path.startsWith("/v1/conflicts/")
        && path.endsWith("/resolve")
        && path.length() > "/v1/conflicts//resolve".length()) {
      if (!"POST".equals(request.method())) return problem(405, "Conflict resolution accepts POST");
      var id = path.substring("/v1/conflicts/".length(), path.length() - "/resolve".length());
      var body =
          request.headers().getOrDefault("content-type", "").startsWith("application/json")
              ? YamlUtil.parseMapStrict(request.bodyText())
              : request.form();
      var strategy = body.get("strategy");
      if (!(strategy instanceof String text) || text.isBlank()) {
        return problem(400, "resolution strategy is required");
      }
      var merged = body.get("merged");
      if (merged != null && !(merged instanceof String)) return problem(400, "merged must be text");
      var resolution =
          new Resolution(
              Resolution.Strategy.valueOf(text.toUpperCase(Locale.ROOT)), (String) merged);
      return ApiResponse.ok(
          SyncViews.conflict(
              operations.resolveConflict(request.query().get("type"), id, resolution)));
    }
    if (path.startsWith("/v1/conflicts/")
        && Boolean.parseBoolean(request.query().get("template"))) {
      if (!"GET".equals(request.method())) return problem(405, "A merge template accepts GET");
      var id = path.substring("/v1/conflicts/".length());
      return ApiResponse.ok(
          Map.of("template", operations.conflictMergeTemplate(request.query().get("type"), id)));
    }
    if (WHOAMI.equals(path)) {
      return whoami(request, caller);
    }
    if (EVENTS.equals(path)) {
      return events(request, caller);
    }
    if (RUN_MESSAGES.equals(path)) {
      return runMessages(request, caller);
    }
    if (RUN_SESSION.equals(path)) {
      return runSession(request, caller);
    }
    if (SPECS.equals(path)) {
      return specsCollection(request);
    }
    if ((SPECS + "/board").equals(path)) {
      return board(request);
    }
    if (path.startsWith(SPECS + "/")) {
      return specItem(request, caller, path.substring((SPECS + "/").length()));
    }
    return problem(404, "No route for " + path);
  }

  /**
   * Reflects the authenticated identity: a run's minted principal with the FDE it acts for, or the
   * box FDE covered by the ambient credential.
   */
  private static ApiResponse whoami(LocalApiRequest request, Caller caller) {
    if (!"GET".equals(request.method())) {
      return problem(405, "whoami accepts GET");
    }
    var body = new LinkedHashMap<String, Object>();
    switch (caller) {
      case Caller.Run(var run, var actor) -> {
        body.put("handle", run.principal());
        body.put("owner", run.owner());
        body.put("role", actor.role().name().toLowerCase());
        body.put("lane", actor.lane().name().toLowerCase());
        body.put("run_id", run.id());
        body.put("project", run.project());
      }
      case Caller.Box(var fde) -> {
        body.put("handle", fde.handle());
        body.put("role", fde.role().name().toLowerCase());
        body.put("lane", fde.lane().name().toLowerCase());
        body.put("credential", "box");
      }
    }
    return new ApiResponse(200, body);
  }

  /**
   * The credential, not the client body, decides what an event is about: the published event names
   * the authenticated run, and the one event door ({@link LocalLaneOperations#publishEvent}) takes
   * its project and conversation from that run, so a run can never address another run's lifecycle
   * or another spec's pipeline. Which types a run may narrate is the event rule's ({@code
   * EventAuthority}): the non-terminal agent-hook types alone. Operator, watcher, sync, and
   * control-plane types carry authority this lane does not have, and the terminal session types
   * ({@code agent_session_stopped}, {@code agent_session_completed}) are
   * watcher-and-reconciler-only — they complete the run, revoke its credential, and release its
   * repo reservation, so accepting them here would let a still-running agent finish itself and
   * admit an overlapping dispatch beside its live process. The reserved authoritative-stop fields
   * ({@code source}, {@code exit_code}, {@code watcher_pid}) are stripped as well, so nothing an
   * agent publishes can impersonate the watcher's verified exit.
   */
  private ApiResponse events(LocalApiRequest request, Caller caller) {
    if (!"POST".equals(request.method())) {
      return problem(405, "events accepts POST");
    }
    if (!(caller instanceof Caller.Run(var run, _))) {
      return problem(
          403,
          "Events narrate a run's lifecycle and require a run credential; the box credential"
              + " has no run to speak for.");
    }
    Event event;
    try {
      event = Event.fromJsonLine(request.bodyText());
    } catch (RuntimeException malformed) {
      return problem(400, "malformed event");
    }
    var data = new LinkedHashMap<String, Object>(event.data());
    data.put(Event.WellKnownData.RUN_ID, run.id());
    data.remove(Event.WellKnownData.SOURCE);
    data.remove(Event.WellKnownData.EXIT_CODE);
    data.remove(Event.WellKnownData.WATCHER_PID);
    var scoped =
        new Event(
            event.v(),
            0L,
            event.ts(),
            run.project(),
            run.specId(),
            event.type(),
            run.principal(),
            run.project(),
            data);
    return switch (operations.publishEvent(scoped)) {
      case Result.Success<EventPublishResponse> published ->
          new ApiResponse(202, Map.of("id", published.value().id()));
      case Result.Failure<EventPublishResponse> refused ->
          problem(refused.code(), refused.errorMessage());
    };
  }

  /**
   * Spec creation is the spec rule's, like every spec write: a read-only credential — a room
   * session's, or a viewer's box credential — is refused there, never minting a work item.
   */
  private ApiResponse specsCollection(LocalApiRequest request) {
    return switch (request.method()) {
      case "GET" -> ApiResponse.from(operations.globalSpecs(filterFrom(request.query())));
      case "POST" ->
          ApiResponse.fromCreated(operations.createGlobalSpec(createFrom(request.form())));
      default -> problem(405, "specs accepts GET or POST");
    };
  }

  private ApiResponse board(LocalApiRequest request) {
    if (!"GET".equals(request.method())) {
      return problem(405, "board accepts GET");
    }
    return ApiResponse.from(operations.globalBoard(request.query().get("project")));
  }

  private ApiResponse specItem(LocalApiRequest request, Caller caller, String tail) {
    var slash = tail.indexOf('/');
    if (slash >= 0) {
      var id = tail.substring(0, slash);
      var sub = tail.substring(slash + 1);
      return switch (sub) {
        case "content" -> content(request, caller, id);
        case "messages" -> messages(request, caller, id);
        default -> problem(404, "No route for spec sub-resource " + sub);
      };
    }
    return switch (request.method()) {
      case "GET" -> ApiResponse.from(operations.globalSpec(tail));
      case "PUT" -> ApiResponse.from(operations.updateGlobalSpec(tail, updateFrom(request.form())));
      case "DELETE" -> ApiResponse.from(operations.deleteGlobalSpec(tail));
      default -> problem(405, "spec accepts GET, PUT, or DELETE");
    };
  }

  private ApiResponse messages(LocalApiRequest request, Caller caller, String id) {
    return switch (request.method()) {
      case "GET" -> {
        var before = request.query().get("before");
        var after = request.query().get("after");
        var result =
            operations.roomMessages(id, before, after, clampedLimit(request.query().get("limit")));
        markDeliveredOnSelfRead(caller, id, result);
        yield ApiResponse.from(result);
      }
      case "POST" -> {
        if (caller instanceof Caller.Run(var run, _)
            && run.readOnlyLane()
            && !id.equals(run.conversationId())) {
          yield problem(403, "A room session posts only to its own room.");
        }
        var form = request.form();
        yield ApiResponse.fromCreated(
            operations.postRoomMessage(
                id,
                new SpecMessageRequest(
                    form.get("body"),
                    form.get("reply_to"),
                    Boolean.parseBoolean(form.get("question"))),
                caller.actor().handle()));
      }
      default -> problem(405, "messages accepts GET or POST");
    };
  }

  /**
   * A run reading a page of its own spec's room is a delivery of exactly what the page showed:
   * those messages need no mid-run injection or stop-gate last look, so their identities join the
   * run's delivery ledger — and nothing else does, so a message the page's limit omitted is still
   * owed its delivery.
   */
  private void markDeliveredOnSelfRead(
      Caller caller, String specId, Result<SpecMessagesResponse> result) {
    if (!(caller instanceof Caller.Run(var run, _))
        || !specId.equals(run.specId())
        || !(result instanceof Result.Success<SpecMessagesResponse>(var response, var ignored))
        || response.messages().isEmpty()) {
      return;
    }
    operations.ackRunMessages(
        run.id(), response.messages().stream().map(SpecMessageView::id).toList());
  }

  /**
   * The run-scoped delivery lane: the relay and the stop gate know only their run credential — the
   * fix lane deliberately carries no {@code SAIL_SPEC_ID} — so the credential names the run and the
   * run names the spec. {@code GET} reads the undelivered inbox; {@code POST} acknowledges exactly
   * the messages the caller showed ({@code delivered=<id>[,<id>...]}, idempotent).
   */
  private ApiResponse runMessages(LocalApiRequest request, Caller caller) {
    if (!(caller instanceof Caller.Run(var run, _))) {
      return problem(
          403,
          "Run message delivery requires a run credential; the box credential has no run"
              + " to deliver to.");
    }
    return switch (request.method()) {
      case "GET" -> ApiResponse.from(operations.runInbox(run.id()));
      case "POST" ->
          ApiResponse.from(
              operations.ackRunMessages(run.id(), deliveredIds(request.form().get("delivered"))));
      default -> problem(405, "run messages accepts GET or POST");
    };
  }

  /**
   * The session-identity lane — one door, two credentials. A run caller: the SessionStart hook
   * knows only its run credential, so the credential names the run and the report names the
   * conversation; {@code POST} records the payload's {@code session_id}, {@code source}, and {@code
   * transcript_path} on the run row, last write wins — a resume, clear, or compact restart
   * re-reports the new conversation, and a revoked credential never resolves to a caller, so a
   * finished run cannot rewrite its session. A box caller has no run row: it may report only a
   * room-bound interactive conversation ({@code room_id}, the {@code SAIL_ROOM_ID} its terminal
   * session exported), which becomes a record-class {@code agent_conversation_started} event in the
   * room — the seam that later lets one conversation be reopened through either door.
   */
  private ApiResponse runSession(LocalApiRequest request, Caller caller) {
    if (caller instanceof Caller.Box && Strings.isBlank(request.form().get("room_id"))) {
      return problem(
          403,
          "Session reports name a run's conversation and require a run credential; the box"
              + " credential reports only a room-bound conversation (room_id).");
    }
    if (!"POST".equals(request.method())) {
      return problem(405, "run session accepts POST");
    }
    var form = request.form();
    return switch (caller) {
      case Caller.Run(var run, _) ->
          ApiResponse.from(
              operations.recordRunSession(
                  run.id(),
                  form.get("session_id"),
                  form.get("source"),
                  form.get("transcript_path")));
      case Caller.Box box ->
          ApiResponse.from(
              operations.recordRoomConversation(
                  form.get("room_id"),
                  form.get("agent"),
                  form.get("session_id"),
                  form.get("source"),
                  form.get("transcript_path")));
    };
  }

  private static List<String> deliveredIds(String value) {
    if (Strings.isBlank(value)) {
      return List.of();
    }
    return Arrays.stream(value.split(",")).map(String::strip).filter(id -> !id.isEmpty()).toList();
  }

  private static int clampedLimit(String value) {
    if (Strings.isBlank(value)) {
      return 50;
    }
    try {
      return Math.clamp(Integer.parseInt(value), 1, 100);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("limit must be an integer");
    }
  }

  private ApiResponse content(LocalApiRequest request, Caller caller, String id) {
    return switch (request.method()) {
      case "GET" -> ApiResponse.from(operations.globalSpecContent(id));
      case "PUT" -> {
        var form = request.form();
        yield ApiResponse.from(
            operations.setGlobalSpecContent(
                id, new SpecContentRequest(form.get("body"), form.get("plan"))));
      }
      default -> problem(405, "content accepts GET or PUT");
    };
  }

  private static SpecStore.SpecFilter filterFrom(Map<String, String> query) {
    return new SpecStore.SpecFilter(
        query.get("project"),
        query.get("status"),
        query.get("assignee"),
        query.get("repo"),
        query.get("search"));
  }

  private static SpecCreateRequest createFrom(Map<String, String> form) {
    return new SpecCreateRequest(
        form.get("id"),
        form.get("project"),
        form.get("title"),
        form.getOrDefault("status", "draft"),
        form.get("assignee"),
        form.get("agent"),
        form.get("model"),
        form.get("reasoning_effort"),
        form.get("branch"),
        intOr(form.get("priority"), 0),
        csv(form.get("depends_on")),
        csv(form.get("repos")),
        form.get("body"),
        form.get("plan"),
        form.get("room_id"));
  }

  private static SpecUpdateRequest updateFrom(Map<String, String> form) {
    return new SpecUpdateRequest(
        form.get("project"),
        form.get("title"),
        form.get("status"),
        form.get("assignee"),
        form.get("agent"),
        form.get("model"),
        form.get("reasoning_effort"),
        form.get("branch"),
        form.containsKey("priority") ? intOr(form.get("priority"), 0) : null,
        form.containsKey("depends_on") ? csv(form.get("depends_on")) : null,
        form.containsKey("repos") ? csv(form.get("repos")) : null,
        form.get("wake"),
        Boolean.parseBoolean(form.get("force")));
  }

  private static List<String> csv(String value) {
    if (Strings.isBlank(value)) {
      return List.of();
    }
    return List.of(value.split(",")).stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  private static int intOr(String value, int fallback) {
    if (Strings.isBlank(value)) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static ApiResponse problem(int status, String message) {
    return new ApiResponse(status, Map.of("error", message == null ? "error" : message));
  }
}
