package com.example.vd.dto;

import com.example.vd.model.Artifact;
import com.example.vd.model.LeadDeveloper;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Payload for updating project metadata. "
        + "All fields are optional — omit or null to leave unchanged.")
public class UpdateProjectRequest {

    @Schema(description = "New display name", example = "Core Datapedia v2")
    public String name;

    @Schema(description = "New description")
    public String description;

    @Schema(description = "New GitHub repository URL")
    public String githubRepo;

    @Schema(description = "New Docker image tag prefix")
    public String imageTag;

    @Schema(description = "Replace the entire lead-developers list (null = no change)")
    public List<LeadDeveloper> leadDevelopers;

    @Schema(description = "Replace the entire artifacts list (null = no change)")
    public List<Artifact> artifacts;
}
