package com.compute.cp;

import com.compute.model.ComputeId;
import com.compute.model.JobDefinition;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import com.compute.model.ServiceDefinition;
import com.compute.model.SubtaskRequest;
import com.compute.model.TaskRequest;
import com.compute.temporal.NamespaceResolver;
import com.compute.temporal.TaskQueues;
import com.compute.temporal.WorkflowIds;
import com.compute.temporal.workflow.JobWorkflow;
import com.compute.temporal.workflow.ServiceWorkflow;
import com.compute.temporal.workflow.TaskWorkflow;
import com.compute.yaml.DefinitionLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RestRouter {

    private static final Logger log = LoggerFactory.getLogger(RestRouter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Vertx vertx;
    private final Path definitionsRoot;
    private final WorkflowServiceStubs stubs;
    private final NamespaceResolver namespaces;
    private final WorkflowQueries queries;
    private final LogStreamRegistry logs;
    private final List<JobDefinition> schedulerDefs;

    public RestRouter(Vertx vertx, Path definitionsRoot, WorkflowServiceStubs stubs,
                      NamespaceResolver namespaces, WorkflowQueries queries,
                      LogStreamRegistry logs, List<JobDefinition> schedulerDefs) {
        this.vertx = vertx;
        this.definitionsRoot = definitionsRoot;
        this.stubs = stubs;
        this.namespaces = namespaces;
        this.queries = queries;
        this.logs = logs;
        this.schedulerDefs = schedulerDefs;
    }

    public Router build() {
        Router r = Router.router(vertx);
        r.route().handler(BodyHandler.create());

        r.get("/api/lanes").handler(this::lanes);
        r.get("/api/events").handler(this::sseState);
        r.get("/api/services").handler(this::listServices);
        r.post("/api/services/:action").handler(this::serviceAction);
        r.get("/api/jobs").handler(this::listJobs);
        r.post("/api/jobs/manual-run").handler(this::jobManualRun);
        r.post("/api/jobs/:action").handler(this::jobAction);
        r.get("/api/tasks").handler(this::listTasks);
        r.get("/api/tasks/:taskId").handler(this::getTask);
        r.post("/api/tasks").handler(this::submitTask);
        r.get("/api/logs/:kind/:id").handler(logs::handle);
        r.post("/api/scheduler/reload").handler(this::reloadScheduler);

        // UI
        r.route("/").handler(ctx -> ctx.redirect("/index.html"));
        r.route("/static/*").handler(StaticHandler.create("static").setCachingEnabled(false));
        r.route("/*").handler(StaticHandler.create("static").setCachingEnabled(false));

        return r;
    }

    private void lanes(RoutingContext ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Lane l : Lane.values()) {
            out.add(Map.of("code", l.code(), "name", l.name()));
        }
        ok(ctx, out);
    }

    private void listServices(RoutingContext ctx) {
        Lane lane = parseLane(ctx, "dev");
        String group = ctx.request().getParam("group");
        String project = ctx.request().getParam("project");
        ctx.vertx().<Object>executeBlocking(() -> {
            DefinitionLoader.Result loaded = new DefinitionLoader(definitionsRoot, lane).load();
            List<ServiceDefinition> filtered = loaded.services().stream()
                    .filter(s -> group == null || group.equals(s.group()))
                    .filter(s -> project == null || project.equals(s.project()))
                    .toList();
            return queries.services(filtered);
        }).onSuccess(r -> ok(ctx, r)).onFailure(e -> fail(ctx, 500, e.getMessage()));
    }

    private void serviceAction(RoutingContext ctx) {
        String action = ctx.pathParam("action");
        JsonObject body = ctx.body().asJsonObject();
        String idPath = body.getString("id");
        Lane lane = parseLane(ctx, body.getString("lane", "dev"));
        String group = body.getString("group");
        String project = body.getString("project");
        if (idPath == null || group == null || project == null) {
            fail(ctx, 400, "id, group, project required");
            return;
        }
        ctx.vertx().<Object>executeBlocking(() -> {
            String ns = namespaces.ensure(new LaneRef(group, project, lane));
            WorkflowClient c = WorkflowClient.newInstance(stubs,
                    WorkflowClientOptions.newBuilder().setNamespace(ns).build());
            ServiceWorkflow stub = c.newWorkflowStub(ServiceWorkflow.class, "service:" + idPath);
            switch (action) {
                case "start" -> stub.start();
                case "stop" -> stub.stop();
                case "restart" -> stub.restart();
                case "ice" -> stub.ice();
                default -> throw new IllegalArgumentException("unknown action: " + action);
            }
            return Map.of("ok", true, "action", action, "id", idPath);
        }).onSuccess(r -> ok(ctx, r)).onFailure(e -> fail(ctx, 400, e.getMessage()));
    }

    private void listJobs(RoutingContext ctx) {
        Lane lane = parseLane(ctx, "dev");
        String dateStr = ctx.request().getParam("date");
        LocalDate date = dateStr == null ? LocalDate.now() : LocalDate.parse(dateStr);
        String group = ctx.request().getParam("group");
        String project = ctx.request().getParam("project");
        ctx.vertx().<Object>executeBlocking(() -> {
            DefinitionLoader.Result loaded = new DefinitionLoader(definitionsRoot, lane).load();
            List<JobDefinition> filtered = loaded.jobs().stream()
                    .filter(j -> group == null || group.equals(j.group()))
                    .filter(j -> project == null || project.equals(j.project()))
                    .toList();
            return queries.jobs(filtered, date);
        }).onSuccess(r -> ok(ctx, r)).onFailure(e -> fail(ctx, 500, e.getMessage()));
    }

    private void jobManualRun(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        String idPath = body.getString("id");
        Lane lane = parseLane(ctx, body.getString("lane", "dev"));
        LocalDate date = body.getString("date") == null ? LocalDate.now() : LocalDate.parse(body.getString("date"));
        JsonObject argsObj = body.getJsonObject("args", new JsonObject());
        Map<String, String> args = new LinkedHashMap<>();
        argsObj.forEach(e -> args.put(e.getKey(), String.valueOf(e.getValue())));

        ctx.vertx().<Object>executeBlocking(() -> {
            DefinitionLoader.Result loaded = new DefinitionLoader(definitionsRoot, lane).load();
            JobDefinition def = loaded.jobs().stream()
                    .filter(j -> j.id().path().equals(idPath))
                    .findFirst().orElse(null);
            if (def == null) throw new IllegalArgumentException("job not found: " + idPath);

            String ns = namespaces.ensure(def.laneRef());
            WorkflowClient c = WorkflowClient.newInstance(stubs,
                    WorkflowClientOptions.newBuilder().setNamespace(ns).build());
            // ALLOW_DUPLICATE: manual re-run is an explicit user action; allow it regardless
            // of whether a previous execution completed, failed, or was cancelled.
            // If the workflow is currently RUNNING, start() throws and we fall through to
            // signal the existing instance directly.
            JobWorkflow stub = c.newWorkflowStub(JobWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(WorkflowIds.job(def.id(), date))
                            .setTaskQueue(TaskQueues.forNamespace(ns))
                            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                            .build());
            try {
                WorkflowClient.start(stub::run, def, date);
            } catch (Exception alreadyStarted) {
                // ok — workflow already running; signal it directly below
            }
            c.newWorkflowStub(JobWorkflow.class, WorkflowIds.job(def.id(), date)).manualRun(args);
            return Map.of("ok", true, "id", idPath, "date", date.toString());
        }).onSuccess(r -> ok(ctx, r)).onFailure(e -> fail(ctx, 400, e.getMessage()));
    }

    private void jobAction(RoutingContext ctx) {
        String action = ctx.pathParam("action");
        JsonObject body = ctx.body().asJsonObject();
        String idPath = body.getString("id");
        Lane lane = parseLane(ctx, body.getString("lane", "dev"));
        LocalDate date = body.getString("date") == null ? LocalDate.now() : LocalDate.parse(body.getString("date"));
        if (idPath == null) {
            fail(ctx, 400, "id required");
            return;
        }
        ctx.vertx().<Object>executeBlocking(() -> {
            DefinitionLoader.Result loaded = new DefinitionLoader(definitionsRoot, lane).load();
            JobDefinition def = loaded.jobs().stream()
                    .filter(j -> j.id().path().equals(idPath))
                    .findFirst().orElse(null);
            if (def == null) throw new IllegalArgumentException("job not found: " + idPath);

            String ns = namespaces.ensure(def.laneRef());
            WorkflowClient c = WorkflowClient.newInstance(stubs,
                    WorkflowClientOptions.newBuilder().setNamespace(ns).build());
            String wfId = WorkflowIds.job(def.id(), date);

            switch (action) {
                case "run" -> {
                    // Start the workflow if not already running; if running, signal manualRun.
                    var stub = c.newWorkflowStub(JobWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId(wfId)
                                    .setTaskQueue(TaskQueues.forNamespace(ns))
                                    .setWorkflowIdReusePolicy(
                                            WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                                    .build());
                    try {
                        WorkflowClient.start(stub::run, def, date);
                    } catch (Exception alreadyStarted) {
                        // Already running — fall through to signal
                    }
                    c.newWorkflowStub(JobWorkflow.class, wfId)
                            .manualRun(Map.of());
                }
                case "cancel" -> {
                    c.newWorkflowStub(JobWorkflow.class, wfId).cancel();
                }
                case "restart" -> {
                    // TERMINATE_IF_RUNNING atomically terminates any live execution and starts fresh.
                    var stub = c.newWorkflowStub(JobWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId(wfId)
                                    .setTaskQueue(TaskQueues.forNamespace(ns))
                                    .setWorkflowIdReusePolicy(
                                            WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING)
                                    .build());
                    WorkflowClient.start(stub::run, def, date);
                }
                default -> throw new IllegalArgumentException("unknown job action: " + action);
            }
            return Map.of("ok", true, "action", action, "id", idPath, "date", date.toString());
        }).onSuccess(r -> ok(ctx, r)).onFailure(e -> fail(ctx, 400, e.getMessage()));
    }

    private void listTasks(RoutingContext ctx) {
        Lane lane = parseLane(ctx, "dev");
        ctx.vertx().<Object>executeBlocking(() -> {
            DefinitionLoader.Result loaded = new DefinitionLoader(definitionsRoot, lane).load();
            java.util.Set<LaneRef> refs = new java.util.LinkedHashSet<>();
            loaded.services().forEach(s -> refs.add(s.laneRef()));
            loaded.jobs().forEach(j -> refs.add(j.laneRef()));
            return queries.listTasks(new ArrayList<>(refs));
        }).onSuccess(r -> ok(ctx, r)).onFailure(e -> fail(ctx, 500, e.getMessage()));
    }

    private void getTask(RoutingContext ctx) {
        String taskId = ctx.pathParam("taskId");
        String group = ctx.request().getParam("group");
        String project = ctx.request().getParam("project");
        Lane lane = parseLane(ctx, "dev");
        if (group == null || project == null) {
            fail(ctx, 400, "group and project query params required");
            return;
        }
        ctx.vertx().<Object>executeBlocking(() -> queries.task(new LaneRef(group, project, lane), taskId))
                .onSuccess(r -> ok(ctx, r)).onFailure(e -> fail(ctx, 500, e.getMessage()));
    }

    private void submitTask(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        String group = body.getString("group");
        String project = body.getString("project");
        Lane lane = parseLane(ctx, body.getString("lane", "dev"));
        String version = body.getString("version", lane.code());
        int parallelism = body.getInteger("parallelism", 1);
        var subtasksArr = body.getJsonArray("subtasks", new io.vertx.core.json.JsonArray());

        List<SubtaskRequest> subs = new ArrayList<>();
        for (int i = 0; i < subtasksArr.size(); i++) {
            JsonObject s = subtasksArr.getJsonObject(i);
            String sid = s.getString("subtaskId", "sub-" + i);
            String kind = s.getString("kind", "default");
            JsonObject args = s.getJsonObject("args", new JsonObject());
            Map<String, Object> map = new LinkedHashMap<>(args.getMap());
            subs.add(new SubtaskRequest(sid, kind, map));
        }
        TaskRequest req = new TaskRequest(
                TaskRequest.newTaskId(), group, project, lane, version, parallelism, subs);

        ctx.vertx().<Object>executeBlocking(() -> {
            String ns = namespaces.ensure(req.laneRef());
            WorkflowClient c = WorkflowClient.newInstance(stubs,
                    WorkflowClientOptions.newBuilder().setNamespace(ns).build());
            TaskWorkflow stub = c.newWorkflowStub(TaskWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(WorkflowIds.task(req.taskId()))
                            .setTaskQueue(TaskQueues.forNamespace(ns))
                            .build());
            WorkflowClient.start(stub::run, req);
            return Map.of("ok", true, "taskId", req.taskId());
        }).onSuccess(r -> ok(ctx, r)).onFailure(e -> fail(ctx, 500, e.getMessage()));
    }

    /**
     * SSE endpoint — streams state snapshots to the browser as they are published
     * by {@link StatePublisher}. Each frame is a plain {@code data: <json>\n\n} line
     * so the browser {@code EventSource} can parse it without a custom event name.
     *
     * <p>Query params: {@code lane} (default {@code dev}).
     * The connection is kept alive until the client disconnects; the EventBus consumer
     * is unregistered automatically on close so there are no memory leaks.
     */
    private void sseState(RoutingContext ctx) {
        Lane lane = parseLane(ctx, "dev");
        String address = StatePublisher.address(lane);

        ctx.response()
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .setChunked(true);

        // Send a comment line to confirm connection; browsers treat ": …\n\n" as a keepalive.
        ctx.response().write(": connected\n\n");

        MessageConsumer<String> consumer = vertx.eventBus().<String>consumer(
                address,
                msg -> {
                    if (!ctx.response().ended()) {
                        try {
                            ctx.response().write("data: " + msg.body() + "\n\n");
                        } catch (Exception ignore) {
                            // Response closed mid-write; the closeHandler below will unregister.
                        }
                    }
                });

        // Unregister the EventBus consumer when the HTTP connection is torn down.
        ctx.request().connection().closeHandler(v -> consumer.unregister());
        ctx.response().closeHandler(v -> consumer.unregister());
    }

    private void reloadScheduler(RoutingContext ctx) {
        ctx.vertx().<Object>executeBlocking(() -> {
            int triggered = 0;
            int failed = 0;
            // Trigger each Temporal Schedule immediately — this fires a JobWorkflow (scheduler
            // branch) right now regardless of the next cron time. If the previous reconcile
            // execution is still running, the SKIP overlap policy will drop the extra trigger.
            for (JobDefinition sched : schedulerDefs) {
                try {
                    String ns = namespaces.ensure(sched.laneRef());
                    ScheduleClient schedClient = ScheduleClient.newInstance(stubs,
                            ScheduleClientOptions.newBuilder().setNamespace(ns).build());
                    schedClient.getHandle(WorkflowIds.scheduleId(sched.id())).trigger();
                    triggered++;
                } catch (Exception e) {
                    log.warn("trigger failed for {}: {}", sched.id().path(), e.getMessage());
                    failed++;
                }
            }
            return Map.of("ok", true, "triggered", triggered, "failed", failed);
        }).onSuccess(r -> ok(ctx, r)).onFailure(e -> fail(ctx, 500, e.getMessage()));
    }

    private static Lane parseLane(RoutingContext ctx, String fallback) {
        String raw = ctx.request().getParam("lane", fallback);
        try { return Lane.fromCode(raw); } catch (Exception e) { return Lane.fromCode(fallback); }
    }

    private static void ok(RoutingContext ctx, Object body) {
        try {
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(JSON.writeValueAsString(body));
        } catch (Exception e) {
            fail(ctx, 500, "json encoding: " + e.getMessage());
        }
    }

    private static void fail(RoutingContext ctx, int code, String msg) {
        log.warn("HTTP {} {}: {}", ctx.request().method(), ctx.request().path(), msg);
        ctx.response()
                .setStatusCode(code)
                .putHeader("content-type", "application/json")
                .end("{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }

    /** Keep ComputeId import meaningful (callers may parse string paths into it). */
    @SuppressWarnings("unused")
    private static ComputeId unused(String s) { return ComputeId.of(s); }
}
