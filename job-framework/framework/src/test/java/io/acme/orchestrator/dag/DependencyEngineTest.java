package io.acme.orchestrator.dag;

import io.acme.orchestrator.model.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the core DAG rules without touching Kubernetes or Vert.x.
 * <p>
 * The engine contract under test:
 * <ol>
 *   <li>A scheduled fire with no deps runs immediately.</li>
 *   <li>A scheduled fire with unsatisfied deps sits in PENDING_DEPS.</li>
 *   <li>When an upstream Job terminates successfully, its downstreams
 *       re-evaluate and fire — regardless of whether the upstream finished
 *       before or after the downstream was scheduled.</li>
 *   <li>A success outside the scheduling window does NOT satisfy the dep.</li>
 *   <li>Failure never satisfies anything.</li>
 *   <li>Cold-start terminal events synthesize in-memory state so downstream
 *       jobs can still treat them as dependency satisfaction.</li>
 *   <li>The fire handler receives the engine's runId, not a fresh one.</li>
 * </ol>
 */
class DependencyEngineTest {

    private static final Instant T = Instant.parse("2026-01-01T02:00:00Z");

    private static RuntimeSpec rt() {
        return new RuntimeSpec(
                "runtime:latest", "Main", List.of(),
                List.of(), List.of(),
                null, null);
    }

    /** Default: 25-hour window (matches JobDefinition's default). */
    private static JobDefinition def(String name, List<String> deps) {
        return new JobDefinition(
                name, "ns", new Schedule.Manual(), deps, rt(),
                Duration.ofMinutes(5), 0, null);
    }

    private static JobDefinition defWithWindow(String name, List<String> deps, Duration window) {
        return new JobDefinition(
                name, "ns", new Schedule.Manual(), deps, rt(),
                Duration.ofMinutes(5), 0, window);
    }

    private record Fired(String name, String runId) {}

    private static class Capture {
        final List<Fired> events = new ArrayList<>();
        void accept(JobDefinition d, String runId) { events.add(new Fired(d.name(), runId)); }
        List<String> names() { return events.stream().map(Fired::name).toList(); }
    }

    // -----------------------------------------------------------------------
    // 1. No dependencies — fire immediately.
    // -----------------------------------------------------------------------
    @Test
    void no_deps_fires_immediately() {
        DependencyEngine engine = new DependencyEngine();
        Capture cap = new Capture();
        engine.onReadyToFire(cap::accept);
        engine.register(def("extract", List.of()));

        engine.scheduleFire("extract", "r1", T);

        assertEquals(List.of("extract"), cap.names());
        assertEquals("r1", cap.events.get(0).runId(), "engine must pass through the runId");
    }

    // -----------------------------------------------------------------------
    // 2. Realistic timing: parent finishes BEFORE child fires.
    //    This is the common case — previously broken by the inverted
    //    isBefore() comparison.
    // -----------------------------------------------------------------------
    @Test
    void parent_finishing_before_child_fires_satisfies_dependency() {
        DependencyEngine engine = new DependencyEngine();
        Capture cap = new Capture();
        engine.onReadyToFire(cap::accept);
        engine.register(def("extract", List.of()));
        engine.register(def("report",  List.of("extract")));

        // Parent: scheduled at 02:00, finishes at 02:01:30.
        engine.scheduleFire("extract", "e1", T);
        engine.markSubmitted("extract", "e1");
        engine.onJobTerminal("extract", "e1", true, T, T.plusSeconds(90));

        // Child: scheduled at 02:05. Should fire immediately — parent
        // finished 3.5 minutes ago, well within the default 25h window.
        engine.scheduleFire("report", "r1", T.plusSeconds(300));

        assertEquals(List.of("extract", "report"), cap.names());
    }

    // -----------------------------------------------------------------------
    // 3. Parent fires LATE: child was already in PENDING_DEPS when parent
    //    eventually succeeds. Downstream still fires.
    // -----------------------------------------------------------------------
    @Test
    void pending_deps_fires_when_parent_succeeds_after_child_was_scheduled() {
        DependencyEngine engine = new DependencyEngine();
        Capture cap = new Capture();
        engine.onReadyToFire(cap::accept);
        engine.register(def("extract", List.of()));
        engine.register(def("report",  List.of("extract")));

        // Child queued first.
        engine.scheduleFire("report", "r1", T);
        assertTrue(cap.names().isEmpty(), "report must wait for parent");

        // Parent fires and succeeds after the child was scheduled.
        engine.scheduleFire("extract", "e1", T.plusSeconds(60));
        engine.markSubmitted("extract", "e1");
        engine.onJobTerminal("extract", "e1", true, T.plusSeconds(60), T.plusSeconds(120));

        assertEquals(List.of("extract", "report"), cap.names());
    }

    // -----------------------------------------------------------------------
    // 4. Stale parent success is OUTSIDE the window → child waits.
    // -----------------------------------------------------------------------
    @Test
    void stale_parent_success_outside_window_does_not_satisfy() {
        DependencyEngine engine = new DependencyEngine();
        Capture cap = new Capture();
        engine.onReadyToFire(cap::accept);
        engine.register(def("extract", List.of()));
        // Tight window: 1 hour.
        engine.register(defWithWindow("report", List.of("extract"), Duration.ofHours(1)));

        // Parent ran yesterday and succeeded.
        Instant yesterday = T.minus(Duration.ofHours(25));
        engine.scheduleFire("extract", "e0", yesterday);
        engine.markSubmitted("extract", "e0");
        engine.onJobTerminal("extract", "e0", true,
                yesterday, yesterday.plusSeconds(60));

        // Child fires today. Window is 1h, parent finished 25h ago → too old.
        engine.scheduleFire("report", "r1", T);

        assertFalse(cap.names().contains("report"),
                "report must not fire on stale upstream outside window");
    }

    // -----------------------------------------------------------------------
    // 5. Parent failure: child stays pending forever.
    // -----------------------------------------------------------------------
    @Test
    void upstream_failure_blocks_downstream_forever() {
        DependencyEngine engine = new DependencyEngine();
        Capture cap = new Capture();
        engine.onReadyToFire(cap::accept);
        engine.register(def("extract", List.of()));
        engine.register(def("report",  List.of("extract")));

        engine.scheduleFire("report",  "r1", T);
        engine.scheduleFire("extract", "e1", T);
        engine.markSubmitted("extract", "e1");
        engine.onJobTerminal("extract", "e1", false, T, T.plusSeconds(30));

        assertEquals(List.of("extract"), cap.names(), "report must stay pending");
    }

    // -----------------------------------------------------------------------
    // 6. Duplicate schedule while the prior run is still live: dropped.
    // -----------------------------------------------------------------------
    @Test
    void duplicate_schedule_while_running_is_ignored() {
        DependencyEngine engine = new DependencyEngine();
        Capture cap = new Capture();
        engine.onReadyToFire(cap::accept);
        engine.register(def("extract", List.of()));

        engine.scheduleFire("extract", "e1", T);
        engine.markSubmitted("extract", "e1");
        engine.scheduleFire("extract", "e2", T.plusSeconds(1));  // still running

        assertEquals(1, cap.events.size(), "must not fire a second run while first is live");
        assertEquals("e1", cap.events.get(0).runId());
    }

    // -----------------------------------------------------------------------
    // 7. Cold-start replay: informer delivers a terminal event for a run the
    //    engine has no in-memory state about. State is synthesized and
    //    downstream dependencies are re-evaluated against it.
    // -----------------------------------------------------------------------
    @Test
    void cold_start_replay_synthesizes_state_and_satisfies_downstream() {
        DependencyEngine engine = new DependencyEngine();
        Capture cap = new Capture();
        engine.onReadyToFire(cap::accept);
        engine.register(def("extract", List.of()));
        engine.register(def("report",  List.of("extract")));

        // Simulate the informer's initial sync replaying a completed Job.
        // Engine has NO prior in-memory state for "extract".
        engine.onJobTerminal("extract", "e-recovered", true,
                T, T.plusSeconds(60));

        // Downstream is now scheduled; parent's (recovered) success is fresh.
        engine.scheduleFire("report", "r1", T.plusSeconds(300));

        assertEquals(List.of("report"), cap.names(),
                "downstream must fire using cold-start-recovered parent state");

        Map<String, RunStatus> snap = engine.snapshot();
        assertEquals(RunStatus.Phase.SUCCEEDED, snap.get("extract").phase());
        assertEquals("e-recovered", snap.get("extract").runId());
    }

    // -----------------------------------------------------------------------
    // 8. Newer terminal event supersedes older in-memory state. Covers the
    //    case where the engine's view is actually stale (e.g. a missed event).
    // -----------------------------------------------------------------------
    @Test
    void newer_terminal_event_supersedes_older_in_memory_state() {
        DependencyEngine engine = new DependencyEngine();
        Capture cap = new Capture();
        engine.onReadyToFire(cap::accept);
        engine.register(def("extract", List.of()));

        // Engine's current view: a successful run from T.
        engine.scheduleFire("extract", "e1", T);
        engine.markSubmitted("extract", "e1");
        engine.onJobTerminal("extract", "e1", true, T, T.plusSeconds(60));

        // Newer terminal event arrives for a run the engine didn't track.
        Instant later = T.plusSeconds(3600);
        engine.onJobTerminal("extract", "e2", true, later, later.plusSeconds(60));

        RunStatus rs = engine.snapshot().get("extract");
        assertEquals("e2", rs.runId(), "newer run must win");
        assertEquals(later, rs.fireTime());
    }

    // -----------------------------------------------------------------------
    // 9. OLDER terminal event for a different run is ignored: the in-memory
    //    state is authoritative for the current window.
    // -----------------------------------------------------------------------
    @Test
    void older_terminal_event_is_ignored() {
        DependencyEngine engine = new DependencyEngine();
        engine.register(def("extract", List.of()));

        // Engine's current view: run at T.
        engine.scheduleFire("extract", "e-current", T);
        engine.markSubmitted("extract", "e-current");
        engine.onJobTerminal("extract", "e-current", true, T, T.plusSeconds(60));

        // Late-arriving event about an older run.
        Instant earlier = T.minusSeconds(3600);
        engine.onJobTerminal("extract", "e-old", true, earlier, earlier.plusSeconds(60));

        assertEquals("e-current", engine.snapshot().get("extract").runId(),
                "older replayed event must not clobber current state");
    }

    // -----------------------------------------------------------------------
    // 10. The onPendingRunCreated hook fires exactly once when a run enters
    //     PENDING_DEPS — and does NOT fire a second time if the run fires
    //     immediately (no deps). The persistence layer can therefore rely on
    //     "one CM write per pending intent."
    // -----------------------------------------------------------------------
    @Test
    void pending_hook_fires_once_per_scheduleFire() {
        DependencyEngine engine = new DependencyEngine();
        List<String> pending = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        engine.onPendingRunCreated((def, rs) -> pending.add(rs.runId()));
        engine.onPendingRunResolved((name, runId) -> resolved.add(runId));
        engine.onReadyToFire((def, runId) -> { /* no-op; we're not testing firing */ });
        engine.register(def("extract", List.of()));

        engine.scheduleFire("extract", "e1", T);

        assertEquals(List.of("e1"), pending,
                "onPendingRunCreated fires exactly once on scheduleFire");
        // No resolved events yet — we haven't called markSubmitted.
        assertTrue(resolved.isEmpty());
    }

    // -----------------------------------------------------------------------
    // 11. markSubmitted triggers onPendingRunResolved — the CM can be deleted
    //     because the Kubernetes Job is now authoritative.
    // -----------------------------------------------------------------------
    @Test
    void resolved_hook_fires_on_markSubmitted() {
        DependencyEngine engine = new DependencyEngine();
        List<String> resolved = new ArrayList<>();
        engine.onPendingRunResolved((name, runId) -> resolved.add(name + "#" + runId));
        engine.onReadyToFire((def, runId) -> { /* swallow */ });
        engine.register(def("extract", List.of()));

        engine.scheduleFire("extract", "e1", T);
        engine.markSubmitted("extract", "e1");

        assertEquals(List.of("extract#e1"), resolved);
    }

    // -----------------------------------------------------------------------
    // 12. Cold-start recovery: rehydratePending restores a PENDING_DEPS run
    //     and immediately re-evaluates against already-loaded terminal parent
    //     state. If the parent had succeeded before the crash, the child
    //     fires on rehydration — this is the whole point of persistence.
    // -----------------------------------------------------------------------
    @Test
    void rehydrate_fires_when_parent_already_succeeded_before_crash() {
        DependencyEngine engine = new DependencyEngine();
        Capture cap = new Capture();
        engine.onReadyToFire(cap::accept);
        engine.register(def("extract", List.of()));
        engine.register(def("report",  List.of("extract")));

        // Simulate restart ordering:
        //   1. Job informer replays parent's terminal success.
        //   2. Pending-run store rehydrates the child's PENDING_DEPS intent.
        engine.onJobTerminal("extract", "e1", true, T, T.plusSeconds(60));
        // At this point there's no pending child, so no downstream fires yet.
        assertTrue(cap.names().isEmpty());

        // Now rehydrate the child that was pending at crash time.
        engine.rehydratePending("report", "r1", T.plusSeconds(300));

        assertEquals(List.of("report"), cap.names(),
                "rehydrated child must fire immediately against recovered parent");
    }
}

