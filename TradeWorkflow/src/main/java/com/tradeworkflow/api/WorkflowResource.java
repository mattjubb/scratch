package com.tradeworkflow.api;

import com.tradeworkflow.engine.WorkflowEngine;
import com.tradeworkflow.model.Workflow;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST resource for workflow CRUD, YAML import/export, and graph data.
 *
 * <p>Base path: {@code /api/workflows}
 */
@Path("/workflows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowResource {

    private final WorkflowEngine engine = WorkflowEngine.getInstance();

    // ------------------------------------------------------------------
    // List / Create
    // ------------------------------------------------------------------

    @GET
    public Collection<Workflow> list() {
        return engine.getAllWorkflows();
    }

    @POST
    public Response create(Workflow workflow) {
        if (workflow == null) {
            return badRequest("Request body is required");
        }
        Workflow created = engine.registerWorkflow(workflow);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    // ------------------------------------------------------------------
    // Single workflow CRUD
    // ------------------------------------------------------------------

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        return engine.getWorkflow(id)
                .map(wf -> Response.ok(wf).build())
                .orElse(notFound("Workflow not found: " + id));
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") String id, Workflow workflow) {
        try {
            Workflow updated = engine.updateWorkflow(id, workflow);
            return Response.ok(updated).build();
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        boolean removed = engine.deleteWorkflow(id);
        return removed
                ? Response.noContent().build()
                : notFound("Workflow not found: " + id);
    }

    // ------------------------------------------------------------------
    // YAML import / export
    // ------------------------------------------------------------------

    @POST
    @Path("/import")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response importYaml(String yaml) {
        try {
            Workflow wf = engine.importWorkflowYaml(yaml);
            return Response.status(Response.Status.CREATED).entity(wf).build();
        } catch (RuntimeException e) {
            return badRequest("YAML import failed: " + e.getMessage());
        }
    }

    @GET
    @Path("/{id}/export")
    @Produces(MediaType.TEXT_PLAIN)
    public Response exportYaml(@PathParam("id") String id) {
        try {
            String yaml = engine.exportWorkflowYaml(id);
            return Response.ok(yaml).build();
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Graph data for vis.js
    // ------------------------------------------------------------------

    @GET
    @Path("/{id}/graph")
    public Response graph(@PathParam("id") String id) {
        try {
            Map<String, Object> graph = engine.buildGraphData(id);
            return Response.ok(graph).build();
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        }
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
