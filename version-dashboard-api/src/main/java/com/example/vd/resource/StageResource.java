package com.example.vd.resource;

import com.example.vd.dto.DriftInfo;
import com.example.vd.dto.PromoteResult;
import com.example.vd.dto.UpdateDepPinRequest;
import com.example.vd.dto.UpdateStageRequest;
import com.example.vd.model.DepPin;
import com.example.vd.model.Project;
import com.example.vd.model.PullRequest;
import com.example.vd.model.Stage;
import com.example.vd.store.ProjectStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage-level operations for a project: reading and patching stage data,
 * promoting, rebasing, managing PRs, and querying dep-pin drift.
 */
@Path("/api/projects/{id}/stages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Stages", description = "Stage versions, promotion, rebase, PRs, and dep pins")
public class StageResource {

    /** The nine valid stage keys in pipeline order. */
    private static final Set<String> VALID_STAGE_KEYS = Set.copyOf(ProjectStore.STAGE_KEYS);

    /** Stages from which promotion is allowed. */
    private static final Set<String> PROMOTABLE = Set.of(
            "snapshot-next",   "snapshot-current",
            "candidate-next",  "candidate-current",
            "release-next"
    );

    @Inject
    ProjectStore store;

    // -----------------------------------------------------------------------
    // Stage reads
    // -----------------------------------------------------------------------

    @GET
    @Operation(
        summary = "Get all stages",
        description = "Returns the complete map of all 9 stage keys to their version data."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "All 9 stages"),
        @APIResponse(responseCode = "404", description = "Project not found")
    })
    public Map<String, Stage> getAllStages(
            @Parameter(description = "Project id") @PathParam("id") String id) {
        return store.findById(id)
                .map(p -> p.stages)
                .orElseThrow(() -> new ProjectStore.ProjectNotFoundException(id));
    }

    @GET
    @Path("/{stageKey}")
    @Operation(summary = "Get a single stage")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The stage"),
        @APIResponse(responseCode = "404", description = "Project or stage not found")
    })
    public Stage getStage(
            @PathParam("id") String id,
            @Parameter(description = "Stage key, e.g. snapshot-current",
                       example = "candidate-current")
            @PathParam("stageKey") String stageKey) {
        requireValidStageKey(stageKey);
        Stage stage = store.getStage(id, stageKey);
        if (stage == null) throw new ProjectStore.StageNotFoundException(id, stageKey);
        return stage;
    }

    // -----------------------------------------------------------------------
    // Stage patch
    // -----------------------------------------------------------------------

    @PATCH
    @Path("/{stageKey}")
    @Operation(
        summary = "Update stage fields",
        description = "Patch version, imageVersion, testsPassed, testsTotal, or lastUpdatedBy. "
                    + "Omit (or null) any field to leave it unchanged. "
                    + "lastUpdated is auto-stamped whenever lastUpdatedBy is provided."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated stage"),
        @APIResponse(responseCode = "404", description = "Project or stage not found")
    })
    public Stage updateStage(
            @PathParam("id") String id,
            @PathParam("stageKey") String stageKey,
            @RequestBody(required = true,
                content = @Content(schema = @Schema(implementation = UpdateStageRequest.class)))
            UpdateStageRequest req) {
        requireValidStageKey(stageKey);
        return store.updateStage(id, stageKey,
                req.version, req.imageVersion,
                req.testsPassed, req.testsTotal,
                req.lastUpdatedBy);
    }

    // -----------------------------------------------------------------------
    // Promote
    // -----------------------------------------------------------------------

    @POST
    @Path("/{stageKey}/promote")
    @Consumes(MediaType.WILDCARD)
    @Operation(
        summary = "Promote a stage",
        description = "Moves a version forward in the pipeline according to promotion rules:\n\n"
                    + "- `*-next` → `*-current`: the current slot shifts to previous, "
                    + "next becomes current, and dep pins on the new current are rebased.\n"
                    + "- `*-current` (snapshot or candidate) → next lane's `*-next`: "
                    + "the stage is copied and dep pins are rebased.\n"
                    + "- `release-next` → `release-current` (final release step).\n\n"
                    + "Returns only the stages that were modified."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The stages that changed"),
        @APIResponse(responseCode = "400", description = "Stage is not promotable"),
        @APIResponse(responseCode = "404", description = "Project or stage not found")
    })
    public PromoteResult promote(
            @PathParam("id") String id,
            @Parameter(description = "Stage key to promote from", example = "snapshot-next")
            @PathParam("stageKey") String stageKey,
            @Parameter(description = "Username to stamp on lastUpdatedBy")
            @QueryParam("actor") String actor) {
        requireValidStageKey(stageKey);
        if (!PROMOTABLE.contains(stageKey)) {
            throw new IllegalArgumentException(
                    "Stage '" + stageKey + "' cannot be promoted. "
                    + "Promotable stages: " + PROMOTABLE);
        }
        Map<String, Stage> changed = store.promote(id, stageKey, actor);
        PromoteResult result = new PromoteResult();
        changed.forEach(result::put);
        return result;
    }

    // -----------------------------------------------------------------------
    // Rebase
    // -----------------------------------------------------------------------

    @POST
    @Path("/{stageKey}/rebase")
    @Consumes(MediaType.WILDCARD)
    @Operation(
        summary = "Rebase dep pins at a stage",
        description = "Refreshes every dependency's pinned version at this stage to match "
                    + "the dependency project's live version at the same stage."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated stage"),
        @APIResponse(responseCode = "400", description = "Stage is not rebaseable (previous stages are read-only)"),
        @APIResponse(responseCode = "404", description = "Project or stage not found")
    })
    public Stage rebaseStage(
            @PathParam("id") String id,
            @PathParam("stageKey") String stageKey,
            @QueryParam("actor") String actor) {
        requireValidStageKey(stageKey);
        requireNotPrevious(stageKey);
        return store.rebaseStage(id, stageKey, actor);
    }

    @POST
    @Path("/rebase-all")
    @Consumes(MediaType.WILDCARD)
    @Operation(
        summary = "Rebase all dep pins across all stages",
        description = "Rebases every dep pin in every stage for this project."
    )
    @APIResponse(responseCode = "200", description = "Full updated project")
    public Project rebaseAll(@PathParam("id") String id) {
        return store.rebaseAll(id);
    }

    // -----------------------------------------------------------------------
    // Dep pins
    // -----------------------------------------------------------------------

    @GET
    @Path("/{stageKey}/deps")
    @Operation(summary = "Get dep pins for a stage")
    @APIResponse(responseCode = "200", description = "Map of dep-id → DepPin")
    public Map<String, DepPin> getDepPins(
            @PathParam("id") String id,
            @PathParam("stageKey") String stageKey) {
        requireValidStageKey(stageKey);
        return getStage(id, stageKey).deps;
    }

    @PATCH
    @Path("/{stageKey}/deps/{depId}")
    @Operation(
        summary = "Update a single dep pin",
        description = "Manually overrides the pinned version/imageVersion for one dependency "
                    + "at one stage. Omit a field to leave it unchanged."
    )
    @APIResponse(responseCode = "200", description = "Updated stage")
    public Stage updateDepPin(
            @PathParam("id") String id,
            @PathParam("stageKey") String stageKey,
            @Parameter(description = "Dependency project id") @PathParam("depId") String depId,
            @RequestBody(required = true,
                content = @Content(schema = @Schema(implementation = UpdateDepPinRequest.class)))
            UpdateDepPinRequest req) {
        requireValidStageKey(stageKey);
        return store.updateDepPin(id, stageKey, depId, req.version, req.imageVersion);
    }

    @POST
    @Path("/{stageKey}/deps/{depId}/rebase")
    @Consumes(MediaType.WILDCARD)
    @Operation(
        summary = "Rebase one dep pin at one stage",
        description = "Refreshes the pinned versions for a single dependency at a single stage."
    )
    @APIResponse(responseCode = "200", description = "Updated stage")
    public Stage rebaseCell(
            @PathParam("id") String id,
            @PathParam("stageKey") String stageKey,
            @PathParam("depId") String depId) {
        requireValidStageKey(stageKey);
        return store.rebaseCell(id, stageKey, depId);
    }

    @POST
    @Path("/deps/{depId}/rebase")
    @Consumes(MediaType.WILDCARD)
    @Operation(
        summary = "Rebase one dependency across all stages",
        description = "Refreshes the pinned versions for a single dependency at every stage."
    )
    @APIResponse(responseCode = "200", description = "Updated project")
    public Project rebaseDep(
            @PathParam("id") String id,
            @PathParam("depId") String depId,
            @QueryParam("actor") String actor) {
        return store.rebaseDep(id, depId, actor);
    }

    // -----------------------------------------------------------------------
    // Drift
    // -----------------------------------------------------------------------

    @GET
    @Path("/{stageKey}/drift")
    @Operation(
        summary = "Get dep-pin drift at a stage",
        description = "For each declared dependency, reports whether the pinned version "
                    + "differs from the dependency project's live version at the same stage."
    )
    @APIResponse(responseCode = "200", description = "List of drift info records (one per dependency)")
    public List<DriftInfo> drift(
            @PathParam("id") String id,
            @PathParam("stageKey") String stageKey) {
        requireValidStageKey(stageKey);
        return store.drift(id, stageKey);
    }

    // -----------------------------------------------------------------------
    // Pull requests
    // -----------------------------------------------------------------------

    @GET
    @Path("/{stageKey}/prs")
    @Operation(summary = "List PRs merged into a stage")
    public List<PullRequest> getPrs(
            @PathParam("id") String id,
            @PathParam("stageKey") String stageKey) {
        requireValidStageKey(stageKey);
        return getStage(id, stageKey).prs;
    }

    @POST
    @Path("/{stageKey}/prs")
    @Operation(
        summary = "Add a PR to a stage",
        description = "Appends a pull request record to a stage. PR numbers must be unique within a stage."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated stage"),
        @APIResponse(responseCode = "409", description = "PR number already exists at this stage")
    })
    public Stage addPr(
            @PathParam("id") String id,
            @PathParam("stageKey") String stageKey,
            @RequestBody(required = true,
                content = @Content(schema = @Schema(implementation = PullRequest.class)))
            PullRequest pr) {
        requireValidStageKey(stageKey);
        if (pr.number <= 0) throw new IllegalArgumentException("PR number must be a positive integer");
        return store.addPr(id, stageKey, pr);
    }

    @DELETE
    @Path("/{stageKey}/prs/{prNumber}")
    @Operation(summary = "Remove a PR from a stage")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated stage"),
        @APIResponse(responseCode = "404", description = "Project or stage not found")
    })
    public Stage removePr(
            @PathParam("id") String id,
            @PathParam("stageKey") String stageKey,
            @Parameter(description = "PR number to remove") @PathParam("prNumber") int prNumber) {
        requireValidStageKey(stageKey);
        return store.removePr(id, stageKey, prNumber);
    }

    // -----------------------------------------------------------------------
    // Guards
    // -----------------------------------------------------------------------

    private static void requireValidStageKey(String stageKey) {
        if (!VALID_STAGE_KEYS.contains(stageKey)) {
            throw new IllegalArgumentException(
                    "'" + stageKey + "' is not a valid stage key. "
                    + "Valid keys: " + String.join(", ", ProjectStore.STAGE_KEYS));
        }
    }

    private static void requireNotPrevious(String stageKey) {
        if (stageKey.endsWith("-previous")) {
            throw new IllegalArgumentException(
                    "'" + stageKey + "' is a read-only previous stage — rebase is not allowed");
        }
    }
}
