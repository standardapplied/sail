/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Lane;
import ai.singlr.sail.store.RunStore;
import java.util.List;

/**
 * What the review pipeline asks of the project container: start a reviewer or a fix agent, read
 * what a finished run said, and rescue work a fix agent left uncommitted. Nothing here waits on an
 * agent — {@link #launch} returns once the run is started, and the pipeline hears how it ended from
 * the run's authoritative stop on the bus, as it does for a build.
 */
public interface ReviewLanes {

  /**
   * One reviewer or fix invocation, launched as its own run.
   *
   * @param lane {@link Lane#REVIEW} or {@link Lane#FIX}
   * @param reviewId the review the run serves, recorded on its run row
   * @param agent the agent CLI type (e.g. {@code codex}, {@code claude-code})
   * @param task the review prompt or the fix task
   * @param branch the spec's branch; with {@code repos} it scopes a fix agent's stop gate to the
   *     spec's own repos
   * @param model the spec's model for a fix agent, which is the spec's own agent; null for a
   *     reviewer, since model names are agent-specific and the reviewer is usually the other agent
   * @param reasoningEffort the spec's reasoning effort: a spec dispatched at {@code xhigh} is
   *     judged and fixed at {@code xhigh}
   * @param shown the ids of the room messages {@code task} rendered, which the run therefore owes
   *     no second delivery
   */
  record Invocation(
      Lane lane,
      String reviewId,
      String project,
      String specId,
      String agent,
      String task,
      String branch,
      List<String> repos,
      String model,
      String reasoningEffort,
      List<String> shown) {

    public Invocation {
      repos = List.copyOf(repos);
      shown = List.copyOf(shown);
    }
  }

  /** How a launch left the review it serves. */
  sealed interface Launch {

    /** The agent is running as run {@code runId}; the pipeline hears how it ends from its stop. */
    record Started(String runId) implements Launch {}

    /**
     * Nothing was started: another run holds what this one would claim. Not a failure — the review
     * waits on that run, and takes its step when it has ended.
     *
     * @param holderRunId the run that holds the claim
     * @param why what that run holds, as the gate said it
     */
    record Deferred(String holderRunId, String why) implements Launch {}
  }

  /**
   * Launches {@code invocation} as a run this box executes for the FDE whose handle is {@code
   * boxHandle}. A claim the dispatch gate refuses is {@link Launch.Deferred}, and nothing was
   * written for it; a launch that fails throws. {@code claimed} runs once the claim has landed —
   * the run row exists — and before the agent's unit starts: whatever must be true of the review
   * only while a run serves it is written there. A failure before the agent exists leaves no run
   * {@code running}; one after it — the launch command or the status read failing over a unit that
   * did start — leaves the run {@code running} with its agent, whose stop still reaches the
   * pipeline.
   */
  Launch launch(Invocation invocation, String boxHandle, Runnable claimed);

  /**
   * What {@code run}'s agent said last: the final answer of a streamed log, or the whole log of an
   * agent that does not stream. Read from the run's own log, so one run's answer is never
   * another's.
   */
  String output(RunStore.RunRow run) throws Exception;

  /** One rescued repo and the files the rescue commit swept up, for the guardrail event. */
  record Rescue(String repo, List<String> files) {}

  /**
   * Commits and pushes any work a fix agent left uncommitted on the spec's branch, using {@code
   * commitMessage} so the commit explains the work it contains. The fix lane runs gated, but the
   * gate is a nudge, not a jail — a fix agent can still end its second stop with a dirty tree,
   * contaminating the shared clone and starving the re-review of the very fixes it is about to
   * judge. This is the deterministic backstop: a repo is rescued only when it is checked out on the
   * spec's own branch, never any other.
   *
   * @return the repos that had uncommitted work, now committed, each with the files it swept
   */
  List<Rescue> ensureCommitted(
      String project, List<String> repos, String branch, String commitMessage) throws Exception;
}
