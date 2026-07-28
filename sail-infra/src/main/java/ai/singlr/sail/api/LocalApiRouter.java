/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes requests arriving over the local Unix-domain socket to a deliberately small surface: event
 * publishing (the in-container event helper), global spec CRUD (the in-container {@code spec} CLI
 * an agent uses), and {@code whoami}. Spec calls go through the very same {@link Operations} the
 * TCP API uses, so a spec an agent creates over the socket is indistinguishable from one the
 * engineer creates with {@code sail spec} — one database, one source of truth.
 *
 * <p>The socket is the transport, not the identity: every request must present the run credential
 * minted with its run's reservation ({@code Authorization: Bearer}, carried into the container as
 * {@code SAIL_RUN_CREDENTIAL}). The credential resolves to the run's agent principal — handle plus
 * owning FDE — which becomes the {@link Actor} and the attribution on every write; a missing,
 * revoked, or expired credential fails loud with 401 and never falls back to a client-chosen actor.
 * The principal is member-tier on this surface — spec and event writes gated by {@link SpecPolicy}
 * through its owner — and the dispatch/stop routes do not exist here at all.
 */
final class LocalApiRouter implements LocalApiHandler {

  private static final String SPECS = "/v1/specs";
  private static final String EVENTS = "/v1/events";
  private static final String WHOAMI = "/v1/whoami";

  private final EventBus bus;
  private final Operations operations;

  LocalApiRouter(EventBus bus, Operations operations) {
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

  private ApiResponse route(LocalApiRequest request) {
    var run = operations.runForCredential(request.bearer()).orElse(null);
    if (run == null) {
      return problem(
          401,
          "Missing or unknown run credential. Requests on this socket must present the"
              + " SAIL_RUN_CREDENTIAL of a live run as a bearer token; a finished run's"
              + " credential is revoked.");
    }
    var path = request.path();
    if (WHOAMI.equals(path)) {
      return whoami(request, run);
    }
    if (EVENTS.equals(path)) {
      return events(request, run);
    }
    if (SPECS.equals(path)) {
      return specsCollection(request, run);
    }
    if ((SPECS + "/board").equals(path)) {
      return board(request);
    }
    if (path.startsWith(SPECS + "/")) {
      return specItem(request, run, path.substring((SPECS + "/").length()));
    }
    return problem(404, "No route for " + path);
  }

  /**
   * Reflects the authenticated run's principal: the minted handle, the FDE it acts for, and the
   * fixed tier of the agent lane.
   */
  private static ApiResponse whoami(LocalApiRequest request, RunStore.RunRow run) {
    if (!"GET".equals(request.method())) {
      return problem(405, "whoami accepts GET");
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("handle", run.principal());
    body.put("owner", run.owner());
    body.put("role", Role.MEMBER.name().toLowerCase());
    body.put("lane", Actor.Lane.AGENT.name().toLowerCase());
    body.put("run_id", run.id());
    body.put("project", run.project());
    return new ApiResponse(200, body);
  }

  private ApiResponse events(LocalApiRequest request, RunStore.RunRow run) {
    if (!"POST".equals(request.method())) {
      return problem(405, "events accepts POST");
    }
    Event event;
    try {
      event = Event.fromJsonLine(request.bodyText());
    } catch (RuntimeException malformed) {
      return problem(400, "malformed event");
    }
    var stamped = bus.publish(event.withAgent(run.principal()));
    return new ApiResponse(202, Map.of("id", stamped.id()));
  }

  private ApiResponse specsCollection(LocalApiRequest request, RunStore.RunRow run) {
    return switch (request.method()) {
      case "GET" -> ApiResponse.from(operations.globalSpecs(filterFrom(request.query())));
      case "POST" ->
          ApiResponse.fromCreated(
              operations.createGlobalSpec(createFrom(request.form(), run.principal())));
      default -> problem(405, "specs accepts GET or POST");
    };
  }

  private ApiResponse board(LocalApiRequest request) {
    if (!"GET".equals(request.method())) {
      return problem(405, "board accepts GET");
    }
    return ApiResponse.from(operations.globalBoard(request.query().get("project")));
  }

  private ApiResponse specItem(LocalApiRequest request, RunStore.RunRow run, String tail) {
    var slash = tail.indexOf('/');
    if (slash >= 0) {
      var id = tail.substring(0, slash);
      var sub = tail.substring(slash + 1);
      if (!"content".equals(sub)) {
        return problem(404, "No route for spec sub-resource " + sub);
      }
      return content(request, run, id);
    }
    return switch (request.method()) {
      case "GET" -> ApiResponse.from(operations.globalSpec(tail));
      case "PUT" ->
          ApiResponse.from(
              operations.updateGlobalSpec(
                  tail, updateFrom(request.form(), run.principal()), actorFrom(run)));
      case "DELETE" -> ApiResponse.from(operations.deleteGlobalSpec(tail, actorFrom(run)));
      default -> problem(405, "spec accepts GET, PUT, or DELETE");
    };
  }

  private ApiResponse content(LocalApiRequest request, RunStore.RunRow run, String id) {
    return switch (request.method()) {
      case "GET" -> ApiResponse.from(operations.globalSpecContent(id));
      case "PUT" -> {
        var form = request.form();
        yield ApiResponse.from(
            operations.setGlobalSpecContent(
                id, new SpecContentRequest(form.get("body"), form.get("plan")), actorFrom(run)));
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
            null)
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
            null,
            Boolean.parseBoolean(form.get("force")))
        .withUpdatedBy(principal);
  }

  /**
   * The resource-scoped {@link Actor} for the authenticated run: its principal handle, member tier,
   * and the FDE it acts for — so every spec mutation flows through the same {@link SpecPolicy}
   * matrix as the API lane, passing exactly where its owner would.
   */
  private static Actor actorFrom(RunStore.RunRow run) {
    return Actor.agentPrincipal(run.principal(), run.owner());
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
