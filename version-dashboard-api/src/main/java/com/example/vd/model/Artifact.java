package com.example.vd.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A published build artifact (JAR, SDK, etc.)")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Artifact {

    @Schema(description = "Artifact display name", example = "core-datapedia-api")
    public String name = "";

    @Schema(description = "URL to the artifact in the registry",
            example = "https://artifactory.example.com/core-datapedia-api")
    public String url = "";

    public Artifact() {}

    public Artifact(String name, String url) {
        this.name = name;
        this.url = url;
    }
}
