package com.example.vd.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Schema(description = "A software project tracked in the version pipeline")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Project {

    @Schema(description = "Unique dot-separated project identifier", required = true,
            example = "core.datapedia")
    public String id;

    @Schema(description = "Human-readable display name", example = "Core Datapedia")
    public String name = "";

    @Schema(description = "Free-text description of what the project does")
    public String description = "";

    @Schema(description = "Lead developers responsible for this project")
    public List<LeadDeveloper> leadDevelopers = new ArrayList<>();

    @Schema(description = "GitHub repository URL",
            example = "https://github.com/example-org/core-datapedia")
    public String githubRepo = "";

    @Schema(description = "Docker image tag prefix used for all stages", example = "datapedia")
    public String imageTag = "";

    @Schema(description = "Published build artifacts (JARs, SDKs, etc.)")
    public List<Artifact> artifacts = new ArrayList<>();

    @Schema(description = "IDs of other projects this project depends on at runtime")
    public List<String> dependencies = new ArrayList<>();

    @Schema(description = "Version data for each of the nine pipeline stages. "
            + "Keys are lane-iteration pairs: snapshot-previous, snapshot-current, snapshot-next, "
            + "candidate-previous, candidate-current, candidate-next, "
            + "release-previous, release-current, release-next.")
    public Map<String, Stage> stages = new LinkedHashMap<>();
}
