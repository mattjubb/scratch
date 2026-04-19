package com.vasara.datapedia.resource;

import com.vasara.datapedia.model.*;
import com.vasara.datapedia.service.SchemaRegistryService;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.List;

@OpenAPIDefinition(
        info = @Info(
                title = "Vasara Datapedia",
                version = "1.0.0",
                description = "Schema registry and browser API for the Vasara platform. "
                        + "Schemas are organized into groups and projects, each identified by a unique "
                        + "numeric ID with support for multiple versions. Namespaces follow the convention "
                        + "com.vasara.schemas.{group}.{project}.",
                contact = @Contact(name = "Vasara Platform Engineering")
        )
)
@Path("/api/v1/schemas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Schema Registry", description = "Browse, search, and register schemas in the Vasara Datapedia")
public class SchemaRegistryResource {

    private final SchemaRegistryService registryService;

    @Inject
    public SchemaRegistryResource(SchemaRegistryService registryService) {
        this.registryService = registryService;
    }

    // ── Tree / Navigation ──────────────────────────────────────────────────────

    @GET
    @Path("/tree")
    @Operation(
            summary = "Get the schema tree",
            description = "Returns the full hierarchical tree of groups → projects → schema summaries "
                    + "for rendering the sidebar browser. Supports an optional search filter across "
                    + "schema name, group, and project."
    )
    @ApiResponse(responseCode = "200", description = "Schema tree",
            content = @Content(schema = @Schema(implementation = GroupProjectTree.class)))
    public GroupProjectTree getTree(
            @QueryParam("filter")
            @Parameter(description = "Case-insensitive substring filter across name, group, and project")
            String filter) {

        return registryService.getTree(filter);
    }

    @GET
    @Path("/groups")
    @Operation(summary = "List all groups", description = "Returns all distinct top-level group names.")
    @ApiResponse(responseCode = "200", description = "List of group names",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = String.class))))
    public List<String> getGroups() {
        return registryService.getGroups();
    }

    @GET
    @Path("/groups/{group}/projects")
    @Operation(summary = "List projects in a group", description = "Returns all distinct project names within the given group.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of project names",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = String.class)))),
            @ApiResponse(responseCode = "404", description = "Group not found")
    })
    public Response getProjects(
            @PathParam("group")
            @Parameter(description = "Group name", required = true, example = "trading")
            String group) {

        List<String> projects = registryService.getProjects(group);
        if (projects.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorBody("Group not found: " + group))
                    .build();
        }
        return Response.ok(projects).build();
    }

    // ── Listing / Search ───────────────────────────────────────────────────────

    @GET
    @Operation(
            summary = "List schemas",
            description = "Returns lightweight schema summaries. All query parameters are optional and "
                    + "can be combined. Results are sorted by group, project, then name."
    )
    @ApiResponse(responseCode = "200", description = "List of schema summaries",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = SchemaSummary.class))))
    public List<SchemaSummary> listSchemas(
            @QueryParam("group")
            @Parameter(description = "Filter by exact group name", example = "trading")
            String group,

            @QueryParam("project")
            @Parameter(description = "Filter by exact project name", example = "orders")
            @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9]*$", message = "Project names must not contain hyphens")
            String project,

            @QueryParam("schemaType")
            @Parameter(description = "Filter by schema type")
            SchemaType schemaType,

            @QueryParam("filter")
            @Parameter(description = "Case-insensitive substring search across name, group, and project")
            String filter) {

        return registryService.listSchemas(group, project, schemaType, filter);
    }

    // ── Schema by ID ───────────────────────────────────────────────────────────

    @GET
    @Path("/{id}")
    @Operation(
            summary = "Get schema by ID",
            description = "Returns the full schema entry including all registered versions."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schema entry with all versions",
                    content = @Content(schema = @Schema(implementation = SchemaEntry.class))),
            @ApiResponse(responseCode = "404", description = "Schema not found")
    })
    public Response getSchemaById(
            @PathParam("id")
            @Positive
            @Parameter(description = "Unique schema registry ID", required = true, example = "1001")
            long id) {

        return registryService.getSchemaById(id)
                .map(entry -> Response.ok(entry).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(errorBody("Schema not found: " + id))
                        .build());
    }

    // ── Schema by Qualified Name ───────────────────────────────────────────────

    @GET
    @Path("/by-name/{group}/{project}/{name}")
    @Operation(
            summary = "Get schema by qualified name",
            description = "Looks up a schema by its group, project, and name triple. "
                    + "Equivalent to resolving com.vasara.schemas.{group}.{project}.{name}."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schema entry",
                    content = @Content(schema = @Schema(implementation = SchemaEntry.class))),
            @ApiResponse(responseCode = "404", description = "Schema not found")
    })
    public Response getSchemaByQualifiedName(
            @PathParam("group")
            @Parameter(description = "Group name", required = true, example = "trading")
            String group,

            @PathParam("project")
            @Parameter(description = "Project name", required = true, example = "orders")
            @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9]*$", message = "Project names must not contain hyphens")
            String project,

            @PathParam("name")
            @Parameter(description = "Schema name", required = true, example = "OrderEvent")
            String name) {

        return registryService.getSchemaByQualifiedName(group, project, name)
                .map(entry -> Response.ok(entry).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(errorBody("Schema not found: " + group + "/" + project + "/" + name))
                        .build());
    }

    // ── Versions ───────────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/versions/{version}")
    @Operation(
            summary = "Get a specific schema version",
            description = "Returns a single versioned snapshot of the schema definition."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schema version",
                    content = @Content(schema = @Schema(implementation = SchemaVersion.class))),
            @ApiResponse(responseCode = "404", description = "Schema or version not found")
    })
    public Response getSchemaVersion(
            @PathParam("id")
            @Positive
            @Parameter(description = "Schema registry ID", required = true, example = "1001")
            long id,

            @PathParam("version")
            @Positive
            @Parameter(description = "Version number", required = true, example = "1")
            int version) {

        return registryService.getSchemaVersion(id, version)
                .map(v -> Response.ok(v).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(errorBody("Version " + version + " not found for schema " + id))
                        .build());
    }

    @GET
    @Path("/{id}/versions/latest")
    @Operation(
            summary = "Get the latest schema version",
            description = "Returns the most recently registered version of the schema."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Latest schema version",
                    content = @Content(schema = @Schema(implementation = SchemaVersion.class))),
            @ApiResponse(responseCode = "404", description = "Schema not found")
    })
    public Response getLatestSchemaVersion(
            @PathParam("id")
            @Positive
            @Parameter(description = "Schema registry ID", required = true, example = "1001")
            long id) {

        return registryService.getLatestSchemaVersion(id)
                .map(v -> Response.ok(v).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(errorBody("Schema not found: " + id))
                        .build());
    }

    // ── Registration ───────────────────────────────────────────────────────────

    @POST
    @Operation(
            summary = "Register a schema",
            description = "Registers a new schema or appends a new version to an existing schema. "
                    + "If a schema with the same group, project, and name already exists, a new version "
                    + "is appended. Otherwise a new schema entry is created with a freshly allocated ID. "
                    + "The namespace is derived automatically as com.vasara.schemas.{group}.{project}."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Schema registered successfully",
                    content = @Content(schema = @Schema(implementation = SchemaEntry.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request payload"),
            @ApiResponse(responseCode = "409", description = "Schema version conflict (e.g. identical schema already registered)")
    })
    public Response registerSchema(
            @NotNull @Valid
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Schema registration payload",
                    required = true,
                    content = @Content(schema = @Schema(implementation = SchemaRegistrationRequest.class)))
            SchemaRegistrationRequest request) {

        if (request.getProject() != null && request.getProject().contains("-")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorBody("Project names must not contain hyphens: " + request.getProject()))
                    .build();
        }

        SchemaEntry entry = registryService.registerSchema(request);
        return Response.created(URI.create("/api/v1/schemas/" + entry.getId()))
                .entity(entry)
                .build();
    }

    // ── Graph / References ─────────────────────────────────────────────────────

    @GET
    @Path("/graph")
    @Operation(
            summary = "Get the schema dependency graph",
            description = "Returns nodes and edges representing schema reference relationships. "
                    + "If rootId is provided, returns the subgraph reachable from that schema. "
                    + "If omitted, returns the full graph across all schemas. "
                    + "Depth limits the traversal depth (default unlimited)."
    )
    @ApiResponse(responseCode = "200", description = "Schema graph",
            content = @Content(schema = @Schema(implementation = SchemaGraph.class)))
    public SchemaGraph getGraph(
            @QueryParam("rootId")
            @Parameter(description = "Root schema ID to start traversal from (omit for full graph)", example = "1001")
            Long rootId,

            @QueryParam("depth")
            @Parameter(description = "Maximum traversal depth (omit for unlimited)", example = "3")
            Integer depth) {

        return registryService.getGraph(rootId, depth);
    }

    @GET
    @Path("/{id}/referenced-by")
    @Operation(
            summary = "Get reverse dependencies",
            description = "Returns all schemas that contain a field referencing the given schema ID."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schemas referencing this schema",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = SchemaSummary.class)))),
            @ApiResponse(responseCode = "404", description = "Schema not found")
    })
    public Response getReferencedBy(
            @PathParam("id")
            @Positive
            @Parameter(description = "Schema registry ID", required = true, example = "1003")
            long id) {

        if (registryService.getSchemaById(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorBody("Schema not found: " + id))
                    .build();
        }
        return Response.ok(registryService.getReferencedBy(id)).build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String errorBody(String message) {
        return "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
    }
}
