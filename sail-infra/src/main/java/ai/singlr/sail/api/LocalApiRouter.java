/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Spec writes flow through {@link SpecPolicy} either way; the events route is run-only, and the
 * dispatch/stop routes do not exist here at all.
 */
final class LocalApiRouter implements LocalApiHandler {

  private static final String SPECS = "/v1/specs";
  private static final String EVENTS = "/v1/events";
  private static final String WHOAMI = "/v1/whoami";
  private static final String RUN_MESSAGES = "/v1/run/messages";
  private static final String RUN_SESSION = "/v1/run/session";

  private static final Set<String> AGENT_EVENT_TYPES =
      Set.of(
          Event.WellKnownTypes.AGENT_SESSION_STARTED,
          Event.WellKnownTypes.AGENT_STOP_NUDGED,
          Event.WellKnownTypes.AGENT_TOOL_STARTED,
          Event.WellKnownTypes.AGENT_TOOL_FINISHED);

  private final EventBus bus;
  private final LocalLaneOperations operations;

  LocalApiRouter(EventBus bus, LocalLaneOperations operations) {
    this.bus = bus;
    this.operations = operations;
  }

  @Override
  public ApiResponse handle(LocalApiRequest request) {
    try {
      return route(request);
    } catch (IllegalArgumentException bad) {
      return problem(400, bad.getMessage());
    } catch (RuntimeException unexpected) {
      ApiLog.unexpected(request.method() + " " + request.path(), unexpected);
      return problem(500, unexpected.getMessage());
    }
  }

  /**
   * Who is on the other end of the socket: a sail-launched run authenticated by its minted
   * credential, or an interactive session covered by the box's ambient FDE credential. Both carry
   * the authorship string and the policy {@link Actor} every write route needs; only the run caller
   * may narrate run lifecycle on the events route.
   */
  private sealed interface Caller {
    String author();

    Actor actor();

    record Run(RunStore.RunRow run) implements Caller {
      @Override
      public String author() {
        return run.principal();
      }

      /**
       * A read-only lane run's credential — a {@code room} wake — resolves to the read-and-converse
       * room principal, never the write-capable agent principal: the lane's authority is decided
       * here, at the boundary, by the run row the server minted — not by anything the session says.
       * A full turn carries the member-tier agent principal a dispatched agent holds, nothing more.
       */
      @Override
      public Actor actor() {
        return run.readOnlyLane()
            ? Actor.roomPrincipal(run.principal(), run.owner())
            : Actor.agentPrincipal(run.principal(), run.owner());
      }
    }

    record Box(Actor fde) implements Caller {
      @Override
      public String author() {
        return fde.handle();
      }

      @Override
      public Actor actor() {
        return fde;
      }
    }
  }

  private ApiResponse route(LocalApiRequest request) {
    var caller = resolve(request.bearer());
    if (caller == null) {
      return problem(
          401,
          "Missing or unknown credential. Requests on this socket must present the"
              + " SAIL_RUN_CREDENTIAL of a live run (a finished run's credential is revoked) or"
              + " this box's ambient box.credential as a bearer token.");
    }
    var path = request.path();
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
      return specsCollection(request, caller);
    }
    if ((SPECS + "/board").equals(path)) {
      return board(request);
    }
    if (path.startsWith(SPECS + "/")) {
      return specItem(request, caller, path.substring((SPECS + "/").length()));
    }
    return problem(404, "No route for " + path);
  }

  private Caller resolve(String bearer) {
    var run = operations.runForCredential(bearer).orElse(null);
    if (run != null) {
      return new Caller.Run(run);
    }
    return operations.boxActorForCredential(bearer).<Caller>map(Caller.Box::new).orElse(null);
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
      case Caller.Run(var run) -> {
        var actor = caller.actor();
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
   * The credential, not the client body, decides what an event is about: the published event's
   * project, spec, run id, and authorship all come from the authenticated run, so a run can never
   * address another run's lifecycle or another spec's pipeline. Only the non-terminal agent-hook
   * event types are accepted: operator, watcher, sync, and control-plane types carry authority this
   * lane does not have, and the terminal session types ({@code agent_session_stopped}, {@code
   * agent_session_completed}) are watcher-and-reconciler-only — they complete the run, revoke its
   * credential, and release its repo reservation, so accepting them here would let a still-running
   * agent finish itself and admit an overlapping dispatch beside its live process. The reserved
   * authoritative-stop fields ({@code source}, {@code exit_code}, {@code watcher_pid}) are stripped
   * as well, so nothing an agent publishes can impersonate the watcher's verified exit.
   */
  private ApiResponse events(LocalApiRequest request, Caller caller) {
    if (!"POST".equals(request.method())) {
      return problem(405, "events accepts POST");
    }
    if (!(caller instanceof Caller.Run(var run))) {
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
    if (!AGENT_EVENT_TYPES.contains(event.type())) {
      return problem(
          403, "Event type '" + event.type() + "' is not available to agent principals.");
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
    var stamped = bus.publish(scoped);
    return new ApiResponse(202, Map.of("id", stamped.id()));
  }

  /**
   * Spec creation takes no policy actor today, so the room lane's refusal lives here at the route:
   * a chat session reads and converses, it never mints work items.
   */
  private ApiResponse specsCollection(LocalApiRequest request, Caller caller) {
    return switch (request.method()) {
      case "GET" -> ApiResponse.from(operations.globalSpecs(filterFrom(request.query())));
      case "POST" -> {
        if (caller.actor().roomLane()) {
          yield problem(403, "A room session reads and converses; it cannot create specs.");
        }
        yield ApiResponse.fromCreated(
            operations.createGlobalSpec(
                createFrom(request.form(), caller.author()), caller.actor()));
      }
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
      case "PUT" ->
          ApiResponse.from(
              operations.updateGlobalSpec(
                  tail, updateFrom(request.form(), caller.author()), caller.actor()));
      case "DELETE" -> ApiResponse.from(operations.deleteGlobalSpec(tail, caller.actor()));
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
        if (caller instanceof Caller.Run(var run)
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
                caller.actor(),
                caller.author()));
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
    if (!(caller instanceof Caller.Run(var run))
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
    if (!(caller instanceof Caller.Run(var run))) {
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
      case Caller.Run(var run) ->
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
                  form.get("transcript_path"),
                  box.actor()));
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
                id, new SpecContentRequest(form.get("body"), form.get("plan")), caller.actor()));
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

  private static SpecCreateRequest createFrom(Map<String, String> form, String principal) {
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
            null,
            form.get("room_id"))
        .withCreatedBy(principal);
  }

  private static SpecUpdateRequest updateFrom(Map<String, String> form, String principal) {
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
            null,
            Boolean.parseBoolean(form.get("force")))
        .withUpdatedBy(principal);
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
