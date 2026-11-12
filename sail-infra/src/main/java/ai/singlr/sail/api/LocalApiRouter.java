/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.ReadFilter;
import ai.singlr.sail.access.Whoami;
import ai.singlr.sail.api.RouteTable.Served;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

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
 * box.credential} file sharing the socket's bind mount), resolving to the box's own person. Both
 * resolve through the role rule, as every door's credential does. Run credentials resolve first, so
 * a launched agent is always its principal. A missing, revoked, or unknown credential fails loud
 * with 401 and never falls back to a client-chosen actor.
 *
 * <p>Every route is one of the table's ({@link #routes}), each declaring its {@link Gate}, which
 * this router applies as the caller before the route's handler runs: spec writes are decided by the
 * spec rule ({@code SpecAuthority}) as the journal records them; reads see only the caller's scope,
 * and a project the path names that the caller cannot read is not there, whatever is asked of it.
 * The events route is run-only, and the dispatch/stop routes do not exist here at all.
 */
final class LocalApiRouter implements LocalApiHandler, RouteTable {

  private static final String GET = "GET";
  private static final String POST = "POST";
  private static final String PUT = "PUT";
  private static final String DELETE = "DELETE";
  private static final String PROJECT = "project";
  private static final String CONFLICTS = "/v1/conflicts";
  private static final String RESOLVE = "/v1/conflicts/{id...}/resolve";
  private static final String CONFLICT = "/v1/conflicts/{id...}";
  private static final String WHOAMI = "/v1/whoami";
  private static final String EVENTS = "/v1/events";
  private static final String RUN_MESSAGES = "/v1/run/messages";
  private static final String RUN_SESSION = "/v1/run/session";
  private static final String SKILLS = "/v1/projects/{project}/skills";
  private static final String SKILL = "/v1/projects/{project}/skills/{name}";
  private static final String SERVE = "/v1/projects/{project}/serve";
  private static final String PORTS = "/v1/projects/{project}/ports";
  private static final String PROJECT_PATH = "/v1/projects/{project}/{rest...}";
  private static final String SPECS = "/v1/specs";
  private static final String BOARD = "/v1/specs/board";
  private static final String SPEC = "/v1/specs/{id}";
  private static final String CONTENT = "/v1/specs/{id}/content";
  private static final String MESSAGES = "/v1/specs/{id}/messages";
  private static final String SPEC_PATH = "/v1/specs/{id}/{sub...}";

  private final LocalLaneOperations operations;
  private final List<Route<Handler>> routes;

  /** A router over {@code operations}. */
  LocalApiRouter(LocalLaneOperations operations) {
    this.operations = operations;
    var reads = Gate.READS_IN_SCOPE;
    var journal = Gate.JOURNAL;
    var serving = Gate.decides(Action.SERVE);
    var sharing = Gate.decides(Action.SHARE);
    this.routes =
        List.of(
            route(GET, CONFLICTS, reads, this::conflicts),
            unserved(CONFLICTS, "Only GET is available."),
            route(POST, RESOLVE, Gate.decides(Action.CHANGE), this::resolve),
            unserved(RESOLVE, "Conflict resolution accepts POST"),
            route(GET, CONFLICT, reads, this::template),
            route(
                Route.ANY,
                CONFLICT,
                Gate.UNSERVED,
                (request, caller, path) ->
                    template(request)
                        ? problem(405, "A merge template accepts GET")
                        : noRoute(request)),
            route(GET, WHOAMI, reads, LocalApiRouter::whoami),
            unserved(WHOAMI, "whoami accepts GET"),
            route(POST, EVENTS, journal, this::events),
            unserved(EVENTS, "events accepts POST"),
            route(GET, RUN_MESSAGES, Gate.RUNS_OWN, aRunsOwn(this::runMessages)),
            route(POST, RUN_MESSAGES, Gate.RUNS_OWN, aRunsOwn(this::runAck)),
            route(
                Route.ANY,
                RUN_MESSAGES,
                Gate.RUNS_OWN,
                aRunsOwn((request, run) -> problem(405, "run messages accepts GET or POST"))),
            route(POST, RUN_SESSION, journal, aRoomsOrARunsOwn(this::runSession)),
            route(
                Route.ANY,
                RUN_SESSION,
                Gate.UNSERVED,
                aRoomsOrARunsOwn(answering("run session accepts POST"))),
            route(GET, SKILLS, reads, this::skills),
            route(POST, SKILLS, sharing, this::unshared),
            unserved(SKILLS, "skills accepts GET or POST"),
            route(GET, SKILL, reads, this::skill),
            route(DELETE, SKILL, sharing, this::unshared),
            unserved(SKILL, "a skill accepts GET or DELETE"),
            route(POST, SERVE, serving, this::serve),
            route(DELETE, SERVE, serving, this::stop),
            unserved(SERVE, "serve accepts POST or DELETE"),
            route(GET, PORTS, reads, this::ports),
            unserved(PORTS, "ports accepts GET"),
            route(
                Route.ANY,
                PROJECT_PATH,
                Gate.UNSERVED,
                (request, caller, path) -> noRoute(request)),
            route(GET, SPECS, reads, this::specs),
            route(POST, SPECS, journal, this::create),
            unserved(SPECS, "specs accepts GET or POST"),
            route(GET, BOARD, reads, this::board),
            unserved(BOARD, "board accepts GET"),
            route(GET, SPEC, reads, this::spec),
            route(PUT, SPEC, journal, this::update),
            route(DELETE, SPEC, journal, this::delete),
            unserved(SPEC, "spec accepts GET, PUT, or DELETE"),
            route(GET, CONTENT, reads, this::content),
            route(PUT, CONTENT, journal, this::setContent),
            unserved(CONTENT, "content accepts GET or PUT"),
            route(GET, MESSAGES, reads, this::roomMessages),
            route(POST, MESSAGES, journal, this::messages),
            unserved(MESSAGES, "messages accepts GET or POST"),
            route(
                Route.ANY,
                SPEC_PATH,
                Gate.UNSERVED,
                (request, caller, path) ->
                    problem(404, "No route for spec sub-resource " + path.get("sub"))));
  }

  @Override
  public List<Route<?>> routes() {
    return List.copyOf(routes);
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
      return ApiResponse.error(ApiException.of(ErrorCode.BAD_REQUEST, bad).failure());
    } catch (RuntimeException unexpected) {
      ApiLog.unexpected(request.method() + " " + request.path(), unexpected);
      return unexpected instanceof Remedied
          ? ApiResponse.error(ApiException.of(ErrorCode.INTERNAL, unexpected).failure())
          : problem(500, unexpected.getMessage());
    }
  }

  /** What answers a request a route serves, with the placeholders its path filled. */
  @FunctionalInterface
  private interface Handler {
    ApiResponse handle(LocalApiRequest request, Caller caller, Map<String, String> path)
        throws IOException, InterruptedException, TimeoutException;
  }

  private static Route<Handler> route(String method, String pattern, Gate gate, Handler handler) {
    return new Route<>(method, pattern, gate, handler);
  }

  /** Every method of {@code pattern} its routes above do not serve, answered as {@code why}. */
  private static Route<Handler> unserved(String pattern, String why) {
    return route(Route.ANY, pattern, Gate.UNSERVED, answering(why));
  }

  private static Handler answering(String methodNotAllowed) {
    return (request, caller, path) -> problem(405, methodNotAllowed);
  }

  /**
   * {@code handler} for a run, whose inbox the route is; the box credential has no run to deliver
   * to, which it hears before what its method is.
   */
  private static Handler aRunsOwn(
      BiFunction<LocalApiRequest, RunStore.RunRow, ApiResponse> handler) {
    return (request, caller, path) ->
        switch (caller) {
          case Caller.Box _ ->
              problem(
                  403,
                  "Run message delivery requires a run credential; the box credential has no run"
                      + " to deliver to.");
          case Caller.Run(var run, _) -> handler.apply(request, run);
        };
  }

  /**
   * {@code handler} for a run's session report, or the box credential's report of a room-bound one
   * ({@code room_id}): the box has no run whose conversation to report.
   */
  private static Handler aRoomsOrARunsOwn(Handler handler) {
    return (request, caller, path) ->
        caller instanceof Caller.Box && Strings.isBlank(request.form().get("room_id"))
            ? problem(
                403,
                "Session reports name a run's conversation and require a run credential;"
                    + " the box credential reports only a room-bound conversation"
                    + " (room_id).")
            : handler.handle(request, caller, path);
  }

  /**
   * Who is on the other end of the socket: a sail-launched run authenticated by its minted
   * credential, or an interactive session covered by the box's ambient credential. Each is the
   * {@link Actor} its request acts as; only the run caller has a run to narrate.
   */
  private sealed interface Caller {
    Actor actor();

    /** What {@code whoami} shows of the credential and the principal it resolved to. */
    Map<String, Object> whoami(Whoami whoami);

    /**
     * A run's credential, acting as {@code actor} ({@link LocalLaneOperations#runActor}): a
     * read-only lane run — a {@code room} wake — is the read-and-converse room principal, never the
     * write-capable agent principal, decided at this boundary by the run row the server minted, not
     * by anything the session says. A full turn carries the agent principal a dispatched agent
     * holds, nothing more.
     */
    record Run(RunStore.RunRow run, Actor actor) implements Caller {
      @Override
      public Map<String, Object> whoami(Whoami whoami) {
        var shown = new LinkedHashMap<String, Object>();
        shown.put("handle", actor.handle());
        shown.put("owner", run.owner());
        shown.put("role", whoami.role());
        shown.put("lane", whoami.lane());
        shown.put("run_id", run.id());
        shown.put("project", run.project());
        return shown;
      }
    }

    /** The box credential, acting as the box's own person. */
    record Box(Actor actor) implements Caller {
      @Override
      public Map<String, Object> whoami(Whoami whoami) {
        var shown = new LinkedHashMap<String, Object>();
        shown.put("handle", actor.handle());
        shown.put("role", whoami.role());
        shown.put("lane", whoami.lane());
        shown.put("credential", "box");
        return shown;
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
                  ApiResponse.error(
                      new ApiException(
                              ErrorCode.FORBIDDEN,
                              "This run acts for "
                                  + (Strings.isBlank(run.owner())
                                      ? "this box's FDE"
                                      : "FDE '" + run.owner() + "'")
                                  + ", whom this box cannot place: its roster has disabled that"
                                  + " FDE or does not know it yet, or this box names no FDE of"
                                  + " its own.",
                              Remedy.of(
                                  "Ask an admin to re-enable the FDE, run '",
                                  Remedy.sail("sync"),
                                  "' on a node that has not pulled main's roster, or name this"
                                      + " box's FDE with '",
                                  Remedy.sail("host config set sync-handle", "<you>"),
                                  "'."))
                          .failure()));
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

  /**
   * Routes an authenticated request, acting as its caller, as every router dispatches ({@link
   * RouteTable#serve}): a project the path names that the caller cannot read is not there; then the
   * route admits the caller through its gate and serves it. Every pattern's table answers every
   * method, its own words for one it does not serve, so a path no pattern matches is the one
   * request without a route.
   */
  private ApiResponse route(LocalApiRequest request, Caller caller) {
    try {
      return switch (RouteTable.serve(routes, request.method(), request.path())) {
        case Served.NotFound<Handler> _, Served.MethodNotAllowed<Handler> _ -> noRoute(request);
        case Served.Match<Handler>(var route, var path) -> {
          if (path.containsKey(PROJECT)) {
            project(caller, path.get(PROJECT));
          }
          route.gate().admit(caller.actor());
          yield route.handler().handle(request, caller, path);
        }
      };
    } catch (IOException | TimeoutException failed) {
      throw ApiException.of(ErrorCode.CONTAINER_ERROR, failed);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw ApiException.of(ErrorCode.CONTAINER_ERROR, interrupted);
    }
  }

  private ApiResponse conflicts(LocalApiRequest request, Caller caller, Map<String, String> path) {
    return ApiResponse.ok(SyncViews.conflicts(operations.conflicts()));
  }

  /**
   * The record a merge of a parked conflict starts from, named by its id, slashes and all, as a
   * file's {@code project/path} is, and asked for by {@code template=true}.
   */
  private ApiResponse template(LocalApiRequest request, Caller caller, Map<String, String> path) {
    if (!template(request)) {
      return noRoute(request);
    }
    return ApiResponse.ok(
        Map.of(
            "template",
            operations.conflictMergeTemplate(request.query().get("type"), path.get("id"))));
  }

  private static boolean template(LocalApiRequest request) {
    return Boolean.parseBoolean(request.query().get("template"));
  }

  /** Settles the parked conflict {@code id} names by the strategy the body gives. */
  private ApiResponse resolve(LocalApiRequest request, Caller caller, Map<String, String> path) {
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
        new Resolution(Resolution.Strategy.valueOf(text.toUpperCase(Locale.ROOT)), (String) merged);
    return ApiResponse.ok(
        SyncViews.conflict(
            operations.resolveConflict(request.query().get("type"), path.get("id"), resolution)));
  }

  /**
   * Reflects the authenticated identity as the access rule shows a principal its own ({@link
   * Whoami}): a run's minted principal with the FDE it acts for, or the box's person the ambient
   * credential stands for.
   */
  private static ApiResponse whoami(
      LocalApiRequest request, Caller caller, Map<String, String> path) {
    return new ApiResponse(200, caller.whoami(Whoami.of(caller.actor())));
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
  private ApiResponse events(LocalApiRequest request, Caller caller, Map<String, String> path) {
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
      case Result.Failure<EventPublishResponse> refused -> ApiResponse.error(refused);
    };
  }

  /**
   * Reads {@code project} as the caller, before anything is asked of it: a project the caller
   * cannot read is not there.
   */
  private static void project(Caller caller, String project) {
    NameValidator.requireValidProjectName(project);
    if (!ReadFilter.reads(caller.actor(), () -> project)) {
      throw ProjectReader.absent(project);
    }
  }

  private static ApiResponse noRoute(LocalApiRequest request) {
    return problem(404, "No route for " + request.path());
  }

  /**
   * Brings the project's stack up, {@code timeout} seconds at most (default 60), refused to a
   * read-only run.
   */
  private ApiResponse serve(LocalApiRequest request, Caller caller, Map<String, String> path)
      throws IOException, InterruptedException, TimeoutException {
    return ApiResponse.ok(
        operations
            .stack()
            .serve(
                path.get(PROJECT),
                Duration.ofSeconds(
                    ApiRouter.seconds(
                        Objects.requireNonNullElse(request.form().get("timeout"), "60")))));
  }

  /** Stops the project's processes, refused to a read-only run. */
  private ApiResponse stop(LocalApiRequest request, Caller caller, Map<String, String> path)
      throws IOException, InterruptedException, TimeoutException {
    return ApiResponse.ok(operations.stack().stop(path.get(PROJECT)));
  }

  private ApiResponse ports(LocalApiRequest request, Caller caller, Map<String, String> path)
      throws IOException, InterruptedException, TimeoutException {
    return ApiResponse.ok(operations.stack().ports(path.get(PROJECT)));
  }

  /**
   * Every skill an agent of the project can see, one of this socket's two project surfaces beside
   * its stack, naming no command its reader in the container cannot run.
   */
  private ApiResponse skills(LocalApiRequest request, Caller caller, Map<String, String> path)
      throws IOException, InterruptedException, TimeoutException {
    return ApiResponse.ok(operations.skills().rows(path.get(PROJECT)).inContainer());
  }

  /**
   * Sharing or removing a skill, which is a person's from outside the container: a shared skill is
   * instructions every other agent and every other box will follow, so no credential in a container
   * may share or remove one (A9), a run's or the box's. The socket serves neither, and answers the
   * refusal Share is decided with on the project, reading nothing of the container; a column that
   * granted it here would be a server error, never a share.
   */
  private ApiResponse unshared(LocalApiRequest request, Caller caller, Map<String, String> path) {
    return ApiResponse.from(
        Refusals.failure(operations.skills().sharing(path.get(PROJECT)).orElseThrow()));
  }

  /** One shared skill's {@code SKILL.md}. */
  private ApiResponse skill(LocalApiRequest request, Caller caller, Map<String, String> path)
      throws IOException, InterruptedException, TimeoutException {
    var name = path.get("name");
    return ApiResponse.ok(
        Map.of("name", name, "skill_md", operations.skills().manifest(path.get(PROJECT), name)));
  }

  private ApiResponse specs(LocalApiRequest request, Caller caller, Map<String, String> path) {
    return ApiResponse.from(operations.globalSpecs(filterFrom(request.query())));
  }

  /**
   * Spec creation is the spec rule's, like every spec write: a read-only credential — a room
   * session's, or a viewer's box credential — is refused there, never minting a work item.
   */
  private ApiResponse create(LocalApiRequest request, Caller caller, Map<String, String> path) {
    return ApiResponse.fromCreated(operations.createGlobalSpec(createFrom(request.form())));
  }

  private ApiResponse board(LocalApiRequest request, Caller caller, Map<String, String> path) {
    return ApiResponse.from(operations.globalBoard(request.query().get("project")));
  }

  /**
   * A spec's detail, with its measures when asked and reading them is the caller's ({@link
   * Action#READ_MEASURES}): in a container, an agent's own work alone; any other caller, or another
   * spec, is answered the detail exactly as without asking.
   */
  private ApiResponse spec(LocalApiRequest request, Caller caller, Map<String, String> path) {
    var id = path.get("id");
    return ApiResponse.from(
        SpecMeasures.asked(request.query().get("measures"))
            ? operations.measuredSpec(id)
            : operations.globalSpec(id));
  }

  private ApiResponse update(LocalApiRequest request, Caller caller, Map<String, String> path) {
    return ApiResponse.from(
        operations.updateGlobalSpec(path.get("id"), updateFrom(request.form())));
  }

  private ApiResponse delete(LocalApiRequest request, Caller caller, Map<String, String> path) {
    return ApiResponse.from(operations.deleteGlobalSpec(path.get("id")));
  }

  private ApiResponse content(LocalApiRequest request, Caller caller, Map<String, String> path) {
    return ApiResponse.from(operations.globalSpecContent(path.get("id")));
  }

  private ApiResponse setContent(LocalApiRequest request, Caller caller, Map<String, String> path) {
    var form = request.form();
    return ApiResponse.from(
        operations.setGlobalSpecContent(
            path.get("id"), new SpecContentRequest(form.get("body"), form.get("plan"))));
  }

  private ApiResponse roomMessages(
      LocalApiRequest request, Caller caller, Map<String, String> path) {
    var id = path.get("id");
    var result =
        operations.roomMessages(
            id,
            request.query().get("before"),
            request.query().get("after"),
            clampedLimit(request.query().get("limit")));
    markDeliveredOnSelfRead(caller, id, result);
    return ApiResponse.from(result);
  }

  /** A post in a room's conversation, an agent's only in its own work, as the message rule says. */
  private ApiResponse messages(LocalApiRequest request, Caller caller, Map<String, String> path) {
    var id = path.get("id");
    var form = request.form();
    return ApiResponse.fromCreated(
        operations.postRoomMessage(
            id,
            SpecMessageRequest.of(form.get("body"), form.get("reply_to"), form.get("question")),
            caller.actor().handle()));
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
   * run names the spec. A run reads its undelivered inbox here; the box has none ({@link
   * #aRunsOwn}).
   */
  private ApiResponse runMessages(LocalApiRequest request, RunStore.RunRow run) {
    return ApiResponse.from(operations.runInbox(run.id()));
  }

  /**
   * A run acknowledges exactly the messages it showed ({@code delivered=<id>[,<id>...]}),
   * idempotently.
   */
  private ApiResponse runAck(LocalApiRequest request, RunStore.RunRow run) {
    return ApiResponse.from(
        operations.ackRunMessages(run.id(), deliveredIds(request.form().get("delivered"))));
  }

  /**
   * The session-identity lane — one door, two credentials. A run caller: the SessionStart hook
   * knows only its run credential, so the credential names the run and the report names the
   * conversation, recording the payload's {@code session_id}, {@code source}, and {@code
   * transcript_path} on the run row, last write wins — a resume, clear, or compact restart
   * re-reports the new conversation, and a revoked credential never resolves to a caller, so a
   * finished run cannot rewrite its session. A box caller has no run row: it may report only a
   * room-bound interactive conversation ({@code room_id}, the {@code SAIL_ROOM_ID} its terminal
   * session exported), which becomes a record-class {@code agent_conversation_started} event in the
   * room — the seam that later lets one conversation be reopened through either door.
   */
  private ApiResponse runSession(LocalApiRequest request, Caller caller, Map<String, String> path) {
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
