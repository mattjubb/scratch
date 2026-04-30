package com.tradeworkflow.api;

import com.tradeworkflow.engine.WorkflowEngine;
import com.tradeworkflow.model.Trade;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.*;

/**
 * REST resource for trade lifecycle management and event triggering.
 *
 * <p>Base path: {@code /api/trades}
 */
@Path("/trades")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TradeResource {

    private final WorkflowEngine engine = WorkflowEngine.getInstance();

    // ------------------------------------------------------------------
    // List / Create
    // ------------------------------------------------------------------

    @GET
    public Collection<Trade> list(@QueryParam("workflowId") String workflowId) {
        if (workflowId != null && !workflowId.isBlank()) {
            return engine.getTradesByWorkflow(workflowId);
        }
        return engine.getAllTrades();
    }

    @POST
    public Response create(CreateTradeRequest req) {
        if (req == null || req.getWorkflowId() == null) {
            return badRequest("workflowId is required");
        }
        try {
            Map<String, Object> meta = req.getMetadata() != null ? req.getMetadata() : new LinkedHashMap<>();
            Trade trade = engine.createTrade(req.getWorkflowId(), meta);
            return Response.status(Response.Status.CREATED).entity(trade).build();
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (RuntimeException e) {
            return badRequest(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Single trade
    // ------------------------------------------------------------------

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        return engine.getTrade(id)
                .map(t -> Response.ok(t).build())
                .orElse(notFound("Trade not found: " + id));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        return engine.deleteTrade(id)
                ? Response.noContent().build()
                : notFound("Trade not found: " + id);
    }

    @GET
    @Path("/{id}/state")
    public Response state(@PathParam("id") String id) {
        return engine.getTrade(id)
                .map(t -> Response.ok(Map.of("tradeId", t.getId(),
                        "currentState", t.getCurrentState() != null ? t.getCurrentState() : "")).build())
                .orElse(notFound("Trade not found: " + id));
    }

    @GET
    @Path("/{id}/history")
    public Response history(@PathParam("id") String id) {
        return engine.getTrade(id)
                .map(t -> Response.ok(t.getHistory()).build())
                .orElse(notFound("Trade not found: " + id));
    }

    // ------------------------------------------------------------------
    // Available events + trigger
    // ------------------------------------------------------------------

    @GET
    @Path("/{id}/events")
    public Response availableEvents(@PathParam("id") String id) {
        try {
            List<String> events = engine.getAvailableEvents(id);
            return Response.ok(Map.of("tradeId", id, "events", events)).build();
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        }
    }

    /**
     * Trigger a named EXTERNAL event on a trade.
     * Body is optional; any JSON fields are passed as {@code params} to declarative transitions.
     */
    @POST
    @Path("/{id}/events/{eventName}")
    public Response triggerEvent(@PathParam("id") String id,
                                 @PathParam("eventName") String eventName,
                                 Map<String, Object> params) {
        try {
            Trade updated = engine.triggerEvent(id, eventName,
                    params != null ? params : Collections.emptyMap());
            return Response.ok(updated).build();
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ------------------------------------------------------------------
    // Inner request DTO
    // ------------------------------------------------------------------

    public static class CreateTradeRequest {
        private String workflowId;
        private Map<String, Object> metadata;

        public String getWorkflowId() { return workflowId; }
        public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", msg)).build();
    }

    private Response notFound(String msg) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", msg)).build();
    }
}
