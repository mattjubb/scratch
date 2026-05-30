package com.example.vd.dto;

import com.example.vd.model.Artifact;
import com.example.vd.model.LeadDeveloper;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "Payload for creating a new project")
public class CreateProjectRequest {

    @Schema(description = "Unique dot-separated identifier — must not already exist",
            required = true, example = "payments.gateway")
    public String id;

    @Schema(description = "Human-readable display name", example = "Payments Gateway")
    public String name = "";

    @Schema(description = "Free-text description")
    public String description = "";

    @Schema(description = "GitHub repository URL")
    public String githubRepo = "";

    @Schema(description = "Docker image tag prefix")
    public String imageTag = "";

    @Schema(description = "Initial lead developers (may be empty)")
    public List<LeadDeveloper> leadDevelopers = new ArrayList<>();

    @Schema(description = "Initial artifacts (may be empty)")
    public List<Artifact> artifacts = new ArrayList<>();
}
