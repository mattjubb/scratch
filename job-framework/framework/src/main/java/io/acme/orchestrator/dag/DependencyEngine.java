package io.acme.orchestrator.dag;

import io.acme.orchestrator.model.JobDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * In-memory DAG + scheduling-window evaluator.
 * <p>
 * Single-threaded by contract: all mutating calls must happen on the Vert.x
 * event loop owned by {@code OrchestratorMain}. This removes the need for
 * locking around the composite "is everything ready?" check.
 * <p>
 * The engine does not itself submit Jobs to Kubernetes — it emits a
 * {@code readyToFire} callback, and the caller decides how to create
 * the {@code Job} resource. This keeps the engine pure and testable.
 */
public final class DependencyEngine {

    private static final Logger log = LoggerFactory.getLogger(DependencyEngine.class);

    private final Map<String, JobDefinition> definitions = new LinkedHashMap<>();

    /**
     * Latest run per logical job name.
     * <p>
     * Plain {@link HashMap} — the engine is single-threaded by contract.
     * {@link #snapshot()} produces a defensive copy for cross-thread reads.
     */
    private final Map<String, RunStatus> latestRun = new HashMap<>();

    /** Reverse adjacency: for each job, the jobs that depend on it. */
    private final Map<String, Set<String>> downstream = new HashMap<>();

    /**
     * Invoked when a job is ready to run. The engine passes the definition
     * AND the runId it has recorded in {@link #latestRun}, so the caller
     * submits to Kubernetes using the same id the engine is tracking.
     * (Using a fresh id here was a bug: the JobInformer's terminal events
     * would then be dropped by the runId mismatch guard.)
     */
    private BiConsumer<JobDefinition, String> fireHandler = (def, runId) -> {};

    /**
     * Invoked when a run enters PENDING_DEPS. Typically wired to a
     * {@code PendingRunStore} so the intent survives restart.
     */
    private BiConsumer<JobDefinition, RunStatus> onPending = (d, rs) -> {};

    /**
     * Invoked when a PENDING_DEPS run resolves (submitted, terminal, or
     * unregistered). Receives (logicalName, runId). Typically wired to
     * {@code PendingRunStore.delete(...)}.
     */
    private BiConsumer<String, String> onPendingResolved = (n, r) -> {};

    public void onReadyToFire(BiConsumer<JobDefinition, String> handler) {
        this.fireHandler = Objects.requireNonNull(handler);
    }

    public void onPendingRunCreated(BiConsumer<JobDefinition, RunStatus> handler) {
        this.onPending = Objects.requireNonNull(handler);
    }

    public void onPendingRunResolved(BiConsumer<String, String> handler) {
        this.onPendingResolved = Objects.requireNonNull(handler);
    }

    /**
     * Cold-start rehydration: reinsert a previously-persisted PENDING_DEPS
     * run without firing the persistence hook (it's already persisted) and
     * then re-evaluate in case dependencies have since completed.
     * <p>
     * Call this AFTER the Job informer has finished its initial LIST, so
     * {@link #evaluate(String)} sees any terminal parent state.
     */
    public void rehydratePending(String logicalName, String runId, Instant fireTime) {
        if (!definitions.containsKey(logicalName)) {
            log.warn("rehydratePending: no definition for {}, skipping", logicalName);
            return;
        }
        // Don't overwrite a live run — the cluster's Job state is fresher than
        // a stale pending-run CM (can happen if we crashed right after submitting).
        RunStatus existing = latestRun.get(logicalName);
        if (existing != null && !existing.phase().equals(RunStatus.Phase.PENDING_DEPS)) {
            log.info("rehydratePending: {} already {} in memory, skipping",
                    logicalName, existing.phase());
            return;
        }
        latestRun.put(logicalName, new RunStatus(
                logicalName, runId, fireTime, null, RunStatus.Phase.PENDING_DEPS));
        log.info("rehydrated pending run {}#{}", logicalName, runId);
        evaluate(logicalName);
    }

    /** Register (or replace) a definition. Rebuilds the downstream map. */
    public void register(JobDefinition def) {
        definitions.put(def.name(), def);
        rebuildAdjacency();
    }

    public void unregister(String name) {
        RunStatus existing = latestRun.remove(name);
        definitions.remove(name);
        rebuildAdjacency();
        if (existing != null && existing.phase() == RunStatus.Phase.PENDING_DEPS) {
            try { onPendingResolved.accept(name, existing.runId()); } catch (RuntimeException e) {
                log.error("onPendingResolved hook failed for {}#{}", name, existing.runId(), e);
            }
        }
    }

    public Optional<JobDefinition> get(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public Collection<JobDefinition> all() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    public Map<String, RunStatus> snapshot() {
        return Map.copyOf(latestRun);
    }

    /**
     * Called by the scheduler when a job's cron says it's due.
     * <p>
     * If dependencies are unsatisfied the firing is deferred — it sits in
     * {@link RunStatus.Phase#PENDING_DEPS} and will be picked up when the
     * last upstream dependency completes successfully.
     */
    public void scheduleFire(String name, String runId, Instant fireTime) {
        JobDefinition def = definitions.get(name);
        if (def == null) {
            log.warn("scheduleFire for unknown job {}", name);
            return;
        }
        RunStatus existing = latestRun.get(name);
        if (existing != null && !existing.isTerminal()) {
            log.info("skip firing {}: prior run {} still {}",
                    name, existing.runId(), existing.phase());
            return;
        }
        RunStatus rs = new RunStatus(
                name, runId, fireTime, null, RunStatus.Phase.PENDING_DEPS);
        latestRun.put(name, rs);
        // Persist the pending intent BEFORE evaluating — if evaluation fires
        // immediately and the Job is submitted, onPendingResolved will clean
        // up, and the intermediate CM write is harmless (last-write-wins).
        try { onPending.accept(def, rs); } catch (RuntimeException e) {
            log.error("onPending hook failed for {}", name, e);
        }
        evaluate(name);
    }

    /**
     * Called by the fabric8 Informer when a Kubernetes Job finishes.
     * <p>
     * Two distinct situations this handles:
     * <ol>
     *   <li><b>Live event:</b> we submitted the Job, it ran, it's done now.
     *       We have {@link #latestRun} state for it and simply transition it.</li>
     *   <li><b>Cold-start replay:</b> the orchestrator restarted and the
     *       informer's initial LIST is replaying terminal Jobs that completed
     *       before we existed. We have no in-memory state for these runs,
     *       but we need to record their outcome so that downstream jobs can
     *       treat their success as satisfying a dependency. We synthesize
     *       a {@link RunStatus} directly in the terminal phase.</li>
     * </ol>
     *
     * @param fireTime the run's nominal scheduled time, read from the
     *                 {@code orchestrator.acme.io/fire-time} annotation on
     *                 the Kubernetes Job. Required so that cold-start replay
     *                 can still satisfy the scheduling-window check in
     *                 {@link #evaluate(String)}.
     */
    public void onJobTerminal(String logicalName, String runId, boolean succeeded,
                              Instant fireTime, Instant finishedAt) {
        RunStatus.Phase phase = succeeded ? RunStatus.Phase.SUCCEEDED : RunStatus.Phase.FAILED;
        RunStatus prev = latestRun.get(logicalName);

        if (prev == null) {
            // Cold-start replay: no prior state, adopt what the cluster tells us.
            latestRun.put(logicalName,
                    new RunStatus(logicalName, runId, fireTime, finishedAt, phase));
            log.info("{} -> {} (from cluster replay)", logicalName, phase);
        } else if (!prev.runId().equals(runId)) {
            // A stale event about a run we've already superseded in memory.
            // Only overwrite if the replayed run is actually newer than what
            // we have; otherwise keep the in-memory view authoritative.
            if (prev.fireTime() == null || fireTime.isAfter(prev.fireTime())) {
                latestRun.put(logicalName,
                        new RunStatus(logicalName, runId, fireTime, finishedAt, phase));
                log.info("{} -> {} (newer run {} supersedes {})",
                        logicalName, phase, runId, prev.runId());
            } else {
                log.debug("ignoring terminal event for {}#{}: older than current {}",
                        logicalName, runId, prev.runId());
                return;
            }
        } else {
            // Normal case: the run we're tracking just finished.
            latestRun.put(logicalName,
                    new RunStatus(logicalName, runId, prev.fireTime(), finishedAt, phase));
            log.info("{} -> {}", logicalName, phase);
        }

        if (succeeded) {
            for (String downstreamName : downstream.getOrDefault(logicalName, Set.of())) {
                evaluate(downstreamName);
            }
        }

        // Defensive: if a terminal event arrives for a run that was still in
        // PENDING_DEPS (shouldn't happen in steady state — markSubmitted always
        // fires first — but can happen if the orchestrator crashed between
        // submitting the Job and calling markSubmitted), clear the stale CM.
        if (prev != null && prev.phase() == RunStatus.Phase.PENDING_DEPS
                && prev.runId().equals(runId)) {
            try { onPendingResolved.accept(logicalName, runId); } catch (RuntimeException e) {
                log.error("onPendingResolved hook failed for {}#{}", logicalName, runId, e);
            }
        }
    }

    /**
     * Mark that the Kubernetes Job has been created for this run.
     * Clears the pending-run CM — the Job resource is now the source of truth.
     */
    public void markSubmitted(String logicalName, String runId) {
        RunStatus updated = latestRun.computeIfPresent(logicalName, (n, rs) ->
                rs.runId().equals(runId)
                        ? new RunStatus(rs.logicalName(), rs.runId(), rs.fireTime(),
                                        rs.finishedAt(), RunStatus.Phase.SUBMITTED)
                        : rs);
        if (updated != null && updated.runId().equals(runId)) {
            try { onPendingResolved.accept(logicalName, runId); } catch (RuntimeException e) {
                log.error("onPendingResolved hook failed for {}#{}", logicalName, runId, e);
            }
        }
    }

    // ------- internal -----------------------------------------------------

    private void rebuildAdjacency() {
        downstream.clear();
        for (JobDefinition d : definitions.values()) {
            for (String dep : d.dependencies()) {
                downstream.computeIfAbsent(dep, k -> new HashSet<>()).add(d.name());
            }
        }
    }

    /**
     * A pending job fires iff every dependency has a successful latest run
     * whose finish time falls within this job's <b>scheduling window</b>:
     * <pre>
     *   |fireTime - finishedAt| within def.schedulingWindow()
     * </pre>
     * Equivalent in plain English: the parent must have finished recently
     * enough that its output is still fresh for this child's firing.
     * <p>
     * Sign convention:
     * <ul>
     *   <li>Parent finished <i>before</i> child fires (the common case):
     *       {@code fireTime - finishedAt} is positive; must be ≤ window.</li>
     *   <li>Parent finished <i>after</i> child fires (parent running late,
     *       child was queued pending):
     *       {@code fireTime - finishedAt} is negative; always accepted —
     *       the parent just succeeded, the child can now proceed.</li>
     * </ul>
     */
    private void evaluate(String name) {
        JobDefinition def = definitions.get(name);
        if (def == null) return;
        RunStatus rs = latestRun.get(name);
        if (rs == null || rs.phase() != RunStatus.Phase.PENDING_DEPS) return;

        Duration window = def.schedulingWindow();
        for (String dep : def.dependencies()) {
            RunStatus depRs = latestRun.get(dep);
            if (depRs == null
                    || depRs.phase() != RunStatus.Phase.SUCCEEDED
                    || depRs.finishedAt() == null) {
                log.debug("{} waiting on {} (no successful run yet)", name, dep);
                return;
            }
            Duration age = Duration.between(depRs.finishedAt(), rs.fireTime());
            if (age.compareTo(window) > 0) {
                log.debug("{} waiting on {}: last success {} is older than window {}",
                        name, dep, depRs.finishedAt(), window);
                return;
            }
        }
        log.info("{} dependencies satisfied, firing run {}", name, rs.runId());
        try {
            fireHandler.accept(def, rs.runId());
        } catch (RuntimeException e) {
            log.error("fire handler failed for {}", name, e);
        }
    }
}
