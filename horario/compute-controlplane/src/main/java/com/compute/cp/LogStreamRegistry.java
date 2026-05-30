package com.compute.cp;

import com.compute.ocp.LogStreamer;
import com.compute.ocp.ResourceNamer;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-Sent Events streaming of pod logs. The kind/id path identifies the resource:
 *
 * <ul>
 *   <li>{@code /api/logs/service/{group}/{project}/{lane}/{idTail}} →
 *       labels: kind=service, id={sanitize(idTail)}, group=..., project=..., lane=...</li>
 *   <li>{@code /api/logs/job/{group}/{project}/{lane}/{idTail}?date=YYYYMMDD}</li>
 *   <li>{@code /api/logs/task/{taskId}}</li>
 * </ul>
 *
 * <p>To keep the route table simple this handler parses the structured id from query
 * params instead of from path segments.</p>
 */
public final class LogStreamRegistry {

    private final LogStreamer streamer;

    public LogStreamRegistry(LogStreamer streamer) {
        this.streamer = streamer;
    }

    public void handle(RoutingContext ctx) {
        String kind = ctx.pathParam("kind");
        String id = ctx.pathParam("id");
        String group = ctx.request().getParam("group", "");
        String project = ctx.request().getParam("project", "");
        String lane = ctx.request().getParam("lane", "");
        String date = ctx.request().getParam("date", "");
        String taskId = ctx.request().getParam("taskId", id);

        var labels = new java.util.LinkedHashMap<String, String>();
        labels.put(ResourceNamer.LABEL_KIND, kind);
        if (!group.isBlank()) labels.put(ResourceNamer.LABEL_GROUP, group);
        if (!project.isBlank()) labels.put(ResourceNamer.LABEL_PROJECT, project);
        if (!lane.isBlank()) labels.put(ResourceNamer.LABEL_LANE, lane);
        if ("task".equals(kind)) {
            labels.put(ResourceNamer.LABEL_TASK_ID, taskId);
        } else if (id != null && !id.isBlank()) {
            labels.put(ResourceNamer.LABEL_ID, ResourceNamer.sanitize(id));
        }
        if ("job".equals(kind) && !date.isBlank()) {
            labels.put(ResourceNamer.LABEL_RUN_DATE, date);
        }

        ctx.response()
                .putHeader("content-type", "text/event-stream")
                .putHeader("cache-control", "no-cache")
                .setChunked(true);

        AtomicBoolean stop = new AtomicBoolean(false);
        ctx.response().closeHandler(v -> stop.set(true));

        streamer.follow(labels, line -> {
            try {
                ctx.response().write("data: " + line.replace("\n", "\\n") + "\n\n");
            } catch (Exception e) {
                stop.set(true);
            }
        }, stop::get);
    }

    /** Used to keep Map import meaningful in the API. */
    @SuppressWarnings("unused")
    private static Map<String, String> unused() { return Map.of(); }
}
