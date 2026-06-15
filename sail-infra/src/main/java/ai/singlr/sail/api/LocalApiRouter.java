/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Map;

/**
 * Routes requests arriving over the local Unix-domain socket to a deliberately small surface: event
 * publishing (the in-container event helper) and global spec CRUD (the in-container {@code spec}
 * CLI an agent uses). Spec calls go through the very same {@link ApiOperations} the TCP API uses,
 * so a spec an agent creates over the socket is indistinguishable from one the engineer creates
 * with {@code sail spec} — one database, one source of truth.
 *
 * <p>Trust here is the socket itself: an Incus container that can write the bind-mounted socket is
 * trusted, so requests carry no bearer token (the same model the event listener has always used).
 * Authorship is taken from the {@code actor} form field, defaulting to {@code agent}; cross-project
 * spoofing from inside a container is out of scope because an FDE already owns their own container.
 */
final class LocalApiRouter implements LocalApiHandler {

  private static final String SPECS = "/v1/specs";
  private static final String EVENTS = "/v1/events";
  private static final String DEFAULT_ACTOR = "agent";

  private final EventBus bus;
  private final ApiOperations operations;

  LocalApiRouter(EventBus bus, ApiOperations operations) {
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
      return problem(500, unexpected.getMessage());
    }
  }

  private ApiResponse route(LocalApiRequest request) {
    var path = request.path();
    if (EVENTS.equals(path)) {
      return events(request);
    }
    if (SPECS.equals(path)) {
      return specsCollection(request);
    }
    if ((SPECS + "/board").equals(path)) {
      return board(request);
    }
    if (path.startsWith(SPECS + "/")) {
      return specItem(request, path.substring((SPECS + "/").length()));
    }
    return problem(404, "No route for " + path);
  }

  private ApiResponse events(LocalApiRequest request) {
    if (!"POST".equals(request.method())) {
      return problem(405, "events accepts POST");
    }
    Event event;
    try {
      event = Event.fromJsonLine(request.bodyText());
    } catch (RuntimeException malformed) {
      return problem(400, "malformed event");
    }
    var stamped = bus.publish(event);
    return new ApiResponse(202, Map.of("id", stamped.id()));
  }

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

  private ApiResponse specItem(LocalApiRequest request, String tail) {
    var slash = tail.indexOf('/');
    if (slash >= 0) {
      var id = tail.substring(0, slash);
      var sub = tail.substring(slash + 1);
      if (!"content".equals(sub)) {
        return problem(404, "No route for spec sub-resource " + sub);
      }
      return content(request, id);
    }
    return switch (request.method()) {
      case "GET" -> ApiResponse.from(operations.globalSpec(tail));
      case "PUT" -> ApiResponse.from(operations.updateGlobalSpec(tail, updateFrom(request.form())));
      case "DELETE" -> ApiResponse.from(operations.deleteGlobalSpec(tail));
      default -> problem(405, "spec accepts GET, PUT, or DELETE");
    };
  }

  private ApiResponse content(LocalApiRequest request, String id) {
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
            null)
        .withCreatedBy(actorOf(form));
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
            null)
        .withUpdatedBy(actorOf(form));
  }

  private static String actorOf(Map<String, String> form) {
    var actor = form.get("actor");
    return Strings.isBlank(actor) ? DEFAULT_ACTOR : actor;
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
