package io.acme.orchestrator.server;

import io.acme.orchestrator.dag.DependencyEngine;
import io.acme.orchestrator.dag.PendingRunRecord;
import io.acme.orchestrator.k8s.DefinitionStore;
import io.acme.orchestrator.k8s.Json;
import io.acme.orchestrator.k8s.PendingRunStore;
import io.acme.orchestrator.model.JobDefinition;
import io.acme.orchestrator.model.ServiceDefinition;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * Thin REST façade. Writes to ConfigMaps; does NOT call the engine directly.
 * <p>
 * The CM informer is the single writer to engine state. This means:
 * <ul>
 *   <li>Steady-state registration and cold-start recovery share the same path.</li>
 *   <li>A future leader-elected replica setup works without changes here.</li>
 *   <li>The API is idempotent by virtue of server-side apply.</li>
 * </ul>
 * The one exception is {@code POST /jobs/:name/run}: a manual trigger is an
 * in-flight action rather than a persisted configuration change, so it
 * writes a pending-run CM directly and waits for the engine to observe it
 * via the normal scheduleFire path.
 * <p>
 * Note that responses report 201 on successful CM writes, but engine
 * registration is asynchronous — the informer callback runs on the event
 * loop and might have fired yet by the time the client's POST returns. For
 * most workflows this is fine; if you need strong read-your-writes, issue
 * a GET afterward.
 */
public final class ApiVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(ApiVerticle.class);

    private final DependencyEngine engine;
    private final DefinitionStore<JobDefinition> jobStore;
    private final DefinitionStore<ServiceDefinition> serviceStore;
    private final PendingRunStore pendingRunStore;
    private final int port;

    public ApiVerticle(DependencyEngine engine,
                       DefinitionStore<JobDefinition> jobStore,
                       DefinitionStore<ServiceDefinition> serviceStore,
                       PendingRunStore pendingRunStore,
                       int port) {
        this.engine = engine;
        this.jobStore = jobStore;
        this.serviceStore = serviceStore;
        this.pendingRunStore = pendingRunStore;
        this.port = port;
    }

    @Override
    public void start() {
        Router r = Router.router(vertx);
        r.route().handler(BodyHandler.create());

        r.post("/jobs").handler(this::registerJob);
        r.post("/services").handler(this::registerService);
        r.delete("/jobs/:name").handler(this::deleteJob);
        r.delete("/services/:name").handler(this::deleteService);
        r.post("/jobs/:name/run").handler(this::triggerRun);
        r.get("/jobs").handler(this::listJobs);
        r.get("/runs").handler(this::listRuns);
        r.get("/health").handler(c -> c.response().end("ok"));

        vertx.createHttpServer().requestHandler(r).listen(port)
                .onSuccess(s -> log.info("api listening on {}", port))
                .onFailure(t -> log.error("api bind failed", t));
    }

    // --- jobs ---------------------------------------------------------------

    private void registerJob(RoutingContext ctx) {
        try {
            JobDefinition def = Json.MAPPER.readValue(ctx.body().asString(), JobDefinition.class);
            jobStore.upsert(def);
            ctx.response().setStatusCode(201).putHeader("content-type", "application/json")
                    .end(Json.MAPPER.writeValueAsString(def));
        } catch (Exception e) {
            fail(ctx, 400, e);
        }
    }

    private void deleteJob(RoutingContext ctx) {
        try {
            jobStore.delete(ctx.pathParam("name"));
            ctx.response().setStatusCode(204).end();
        } catch (Exception e) {
            fail(ctx, 500, e);
        }
    }

    private void triggerRun(RoutingContext ctx) {
        String name = ctx.pathParam("name");
        if (engine.get(name).isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        try {
            String runId = UUID.randomUUID().toString().substring(0, 8);
            Instant fireTime = Instant.now();
            // Persist the intent; engine will observe it on the event loop.
            // We write the CM directly, then also schedule the fire so the
            // engine picks it up promptly (rather than only after the next
            // restart re-lists pending runs).
            pendingRunStore.upsert(new PendingRunRecord(name, runId, fireTime, fireTime));
            vertx.runOnContext(v -> engine.scheduleFire(name, runId, fireTime));
            ctx.response().putHeader("content-type", "application/json")
                    .end("{\"runId\":\"" + runId + "\"}");
        } catch (Exception e) {
            fail(ctx, 500, e);
        }
    }

    // --- services -----------------------------------------------------------

    private void registerService(RoutingContext ctx) {
        try {
            ServiceDefinition def = Json.MAPPER.readValue(ctx.body().asString(), ServiceDefinition.class);
            serviceStore.upsert(def);
            ctx.response().setStatusCode(201).putHeader("content-type", "application/json")
                    .end(Json.MAPPER.writeValueAsString(def));
        } catch (Exception e) {
            fail(ctx, 400, e);
        }
    }

    private void deleteService(RoutingContext ctx) {
        try {
            serviceStore.delete(ctx.pathParam("name"));
            ctx.response().setStatusCode(204).end();
        } catch (Exception e) {
            fail(ctx, 500, e);
        }
    }

    // --- read-only inspectors ----------------------------------------------

    private void listJobs(RoutingContext ctx) {
        try {
            ctx.response().putHeader("content-type", "application/json")
                    .end(Json.MAPPER.writeValueAsString(engine.all()));
        } catch (Exception e) { fail(ctx, 500, e); }
    }

    private void listRuns(RoutingContext ctx) {
        try {
            ctx.response().putHeader("content-type", "application/json")
                    .end(Json.MAPPER.writeValueAsString(engine.snapshot()));
        } catch (Exception e) { fail(ctx, 500, e); }
    }

    private void fail(RoutingContext ctx, int code, Exception e) {
        log.warn("api error", e);
        HttpServerResponse r = ctx.response().setStatusCode(code);
        r.putHeader("content-type", "application/json");
        String msg = e.getMessage() == null ? e.getClass().getSimpleName()
                : e.getMessage().replace("\"", "\\\"");
        r.end("{\"error\":\"" + msg + "\"}");
    }
}
