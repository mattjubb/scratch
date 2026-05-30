package com.example.vd.resource;

import com.example.vd.dto.AddDependencyRequest;
import com.example.vd.dto.CreateProjectRequest;
import com.example.vd.dto.UpdateProjectRequest;
import com.example.vd.model.Artifact;
import com.example.vd.model.LeadDeveloper;
import com.example.vd.model.Project;
import com.example.vd.store.ProjectStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.Context;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.util.List;

/**
 * Manages projects and their metadata (lead devs, artifacts, dependencies).
 * Stage-level operations live in {@link StageResource}.
 */
@Path("/api/projects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Projects", description = "CRUD for projects and their metadata")
public class ProjectResource {

    @Inject
    ProjectStore store;

    // -----------------------------------------------------------------------
    // Collection
    // -----------------------------------------------------------------------

    @GET
    @Operation(
        summary = "List all projects",
        description = "Returns every project in the store, optionally filtered by a search query "
                    + "that matches against id and name."
    )
    @APIResponse(responseCode = "200", description = "Array of projects")
    public List<Project> listProjects(
            @Parameter(description = "Case-insensitive substring filter on id and name")
            @QueryParam("q") String query) {

        List<Project> all = store.findAll();
        if (query == null || query.isBlank()) return all;
        String q = query.strip().toLowerCase();
        return all.stream()
                .filter(p -> p.id.toLowerCase().contains(q) || p.name.toLowerCase().contains(q))
                .sorted((a, b) -> a.id.compareToIgnoreCase(b.id))
                .toList();
    }

    @POST
    @Operation(
        summary = "Create a project",
        description = "Creates a new project with empty stages. The id must be unique."
    )
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created project"),
        @APIResponse(responseCode = "400", description = "Missing or invalid fields"),
        @APIResponse(responseCode = "409", description = "A project with that id already exists")
    })
    public Response createProject(
            @RequestBody(required = true,
                content = @Content(schema = @Schema(implementation = CreateProjectRequest.class)))
            CreateProjectRequest req,
            @Context UriInfo uriInfo) {

        if (req == null || req.id == null || req.id.isBlank()) {
            throw new IllegalArgumentException("Field 'id' is required");
        }
        if (store.exists(req.id)) {
            throw new ProjectStore.ConflictException(
                    "A project with id '" + req.id + "' already exists");
        }

        Project project = new Project();
        project.id             = req.id.strip();
        project.name           = req.name          != null ? req.name          : req.id;
        project.description    = req.description   != null ? req.description   : "";
        project.githubRepo     = req.githubRepo     != null ? req.githubRepo     : "";
        project.imageTag       = req.imageTag       != null ? req.imageTag       : "";
        project.leadDevelopers = req.leadDevelopers != null ? req.leadDevelopers : List.of();
        project.artifacts      = req.artifacts      != null ? req.artifacts      : List.of();

        store.add(project);

        URI location = uriInfo.getAbsolutePathBuilder().path(project.id).build();
        return Response.created(location).entity(project).build();
    }

    // -----------------------------------------------------------------------
    // Single project
    // -----------------------------------------------------------------------

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a project by id")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The project"),
        @APIResponse(responseCode = "404", description = "Project not found")
    })
    public Project getProject(
            @Parameter(description = "Project id", example = "core.datapedia")
            @PathParam("id") String id) {
        return store.findById(id)
                .orElseThrow(() -> new ProjectStore.ProjectNotFoundException(id));
    }

    @PATCH
    @Path("/{id}")
    @Operation(
        summary = "Update project metadata",
        description = "Patch any subset of: name, description, githubRepo, imageTag, "
                    + "leadDevelopers, artifacts. Omit a field (or set it to null) to leave it unchanged."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated project"),
        @APIResponse(responseCode = "404", description = "Project not found")
    })
    public Project updateProject(
            @PathParam("id") String id,
            @RequestBody(required = true,
                content = @Content(schema = @Schema(implementation = UpdateProjectRequest.class)))
            UpdateProjectRequest req) {

        Project p = store.update(id, req.name, req.description, req.githubRepo, req.imageTag);
        if (req.leadDevelopers != null) store.replaceLeadDevelopers(id, req.leadDevelopers);
        if (req.artifacts      != null) store.replaceArtifacts(id, req.artifacts);
        return p;
    }

    @DELETE
    @Path("/{id}")
    @Operation(
        summary = "Delete a project",
        description = "Deletes the project and removes it from every other project's "
                    + "dependency list and dep-pin maps."
    )
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Deleted"),
        @APIResponse(responseCode = "404", description = "Project not found")
    })
    public Response deleteProject(
            @Parameter(description = "Project id to delete")
            @PathParam("id") String id) {
        store.delete(id);
        return Response.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Lead developers (convenience sub-resource)
    // -----------------------------------------------------------------------

    @GET
    @Path("/{id}/lead-developers")
    @Operation(summary = "Get lead developers for a project")
    public List<LeadDeveloper> getLeadDevelopers(@PathParam("id") String id) {
        return getProject(id).leadDevelopers;
    }

    @PUT
    @Path("/{id}/lead-developers")
    @Operation(
        summary = "Replace the lead-developers list",
        description = "Replaces the entire list. Send an empty array to clear."
    )
    @APIResponse(responseCode = "200", description = "Updated project")
    public Project replaceLeadDevelopers(
            @PathParam("id") String id,
            @RequestBody(required = true) List<LeadDeveloper> devs) {
        return store.replaceLeadDevelopers(id, devs);
    }

    // -----------------------------------------------------------------------
    // Artifacts (convenience sub-resource)
    // -----------------------------------------------------------------------

    @GET
    @Path("/{id}/artifacts")
    @Operation(summary = "Get artifacts for a project")
    public List<Artifact> getArtifacts(@PathParam("id") String id) {
        return getProject(id).artifacts;
    }

    @PUT
    @Path("/{id}/artifacts")
    @Operation(
        summary = "Replace the artifacts list",
        description = "Replaces the entire list. Send an empty array to clear."
    )
    @APIResponse(responseCode = "200", description = "Updated project")
    public Project replaceArtifacts(
            @PathParam("id") String id,
            @RequestBody(required = true) List<Artifact> artifacts) {
        return store.replaceArtifacts(id, artifacts);
    }

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    @GET
    @Path("/{id}/dependencies")
    @Operation(
        summary = "List dependency ids",
        description = "Returns the ordered list of project ids that this project depends on."
    )
    public List<String> getDependencies(@PathParam("id") String id) {
        return getProject(id).dependencies;
    }

    @POST
    @Path("/{id}/dependencies")
    @Operation(
        summary = "Add a dependency",
        description = "Adds the given project as a dependency and seeds dep-pin entries for "
                    + "all 9 stages with the dependency's current live versions."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated project"),
        @APIResponse(responseCode = "400", description = "Invalid depId or circular dependency"),
        @APIResponse(responseCode = "404", description = "Project or dependency not found")
    })
    public Project addDependency(
            @PathParam("id") String id,
            @RequestBody(required = true,
                content = @Content(schema = @Schema(implementation = AddDependencyRequest.class)))
            AddDependencyRequest req) {

        if (req == null || req.depId == null || req.depId.isBlank()) {
            throw new IllegalArgumentException("Field 'depId' is required");
        }
        if (id.equals(req.depId)) {
            throw new IllegalArgumentException("A project cannot depend on itself");
        }
        return store.addDependency(id, req.depId);
    }

    @DELETE
    @Path("/{id}/dependencies/{depId}")
    @Operation(
        summary = "Remove a dependency",
        description = "Removes the dependency and deletes all associated dep-pin entries "
                    + "across all 9 stages."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated project"),
        @APIResponse(responseCode = "404", description = "Project not found")
    })
    public Project removeDependency(
            @PathParam("id") String id,
            @Parameter(description = "Dependency project id to remove")
            @PathParam("depId") String depId) {
        return store.removeDependency(id, depId);
    }

    // -----------------------------------------------------------------------
    // Admin
    // -----------------------------------------------------------------------

    @POST
    @Path("/reset")
    @Consumes(MediaType.WILDCARD)
    @Operation(
        summary = "Reset store to seed data",
        description = "⚠ Destructive. Discards all in-memory state and reloads from projects.json."
    )
    @APIResponse(responseCode = "200", description = "All projects after reset")
    public List<Project> reset() {
        store.reset();
        return store.findAll();
    }
}
