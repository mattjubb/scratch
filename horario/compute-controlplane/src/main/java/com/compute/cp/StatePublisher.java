package com.compute.cp;

import com.compute.model.Lane;
import com.compute.model.LaneRef;
import com.compute.yaml.DefinitionLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically fans out Temporal workflow-state queries for every lane and
 * publishes a JSON snapshot to the Vert.x EventBus.
 *
 * <p>Each snapshot is a JSON object with the shape:
 * <pre>
 * {
 *   "lane":     "dev",
 *   "date":     "2026-05-25",
 *   "services": [...],
 *   "jobs":     [...],
 *   "tasks":    [...],
 *   "ts":       1716624000000
 * }
 * </pre>
 * Snapshots are published on address {@code compute.events.<lane-code>}. The
 * {@code /api/events} SSE endpoint subscribes to this address and streams
 * each snapshot to connected browsers as a plain SSE {@code data:} frame.
 */
public final class StatePublisher {

    private static final Logger log = LoggerFactory.getLogger(StatePublisher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Vertx vertx;
    private final WorkflowQueries queries;
    private final Path definitionsRoot;
    private long timerId = -1;

    public StatePublisher(Vertx vertx, WorkflowQueries queries, Path definitionsRoot) {
        this.vertx = vertx;
        this.queries = queries;
        this.definitionsRoot = definitionsRoot;
    }

    /**
     * Starts the periodic publisher. An initial snapshot is sent on the next
     * event-loop tick so that newly connected SSE clients receive data immediately
     * without waiting for the first interval.
     *
     * @param intervalMs publish cadence in milliseconds (e.g. 3000)
     */
    public void start(long intervalMs) {
        timerId = vertx.setPeriodic(intervalMs, id -> publishAll());
        // Fire one snapshot immediately after the HTTP server is fully up
        vertx.runOnContext(v -> publishAll());
    }

    /** Cancels the periodic timer; safe to call if {@link #start} was never called. */
    public void stop() {
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    /** Vert.x EventBus address for the given lane's state snapshots. */
    public static String address(Lane lane) {
        return "compute.events." + lane.code();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void publishAll() {
        for (Lane lane : Lane.values()) {
            publishLane(lane);
        }
    }

    private void publishLane(Lane lane) {
        LocalDate today = LocalDate.now();
        vertx.executeBlocking(() -> {
            DefinitionLoader.Result loaded = new DefinitionLoader(definitionsRoot, lane).load();

            List<Map<String, Object>> services = queries.services(loaded.services());
            List<Map<String, Object>> jobs     = queries.jobs(loaded.jobs(), today);

            Set<LaneRef> refs = new LinkedHashSet<>();
            loaded.services().forEach(s -> refs.add(s.laneRef()));
            loaded.jobs().forEach(j -> refs.add(j.laneRef()));
            List<Map<String, Object>> tasks = queries.listTasks(new ArrayList<>(refs));

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("lane",     lane.code());
            snapshot.put("date",     today.toString());
            snapshot.put("services", services);
            snapshot.put("jobs",     jobs);
            snapshot.put("tasks",    tasks);
            snapshot.put("ts",       System.currentTimeMillis());
            return JSON.writeValueAsString(snapshot);

        }).onSuccess(json -> {
            vertx.eventBus().publish(address(lane), json);
            log.trace("published state snapshot for lane {}", lane.code());
        }).onFailure(e ->
            log.warn("state publish failed for lane {}: {}", lane.code(), e.getMessage())
        );
    }
}
