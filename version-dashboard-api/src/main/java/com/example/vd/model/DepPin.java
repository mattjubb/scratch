package com.example.vd.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Pinned versions of a dependency project at a given pipeline stage")
@JsonIgnoreProperties(ignoreUnknown = true)
public class DepPin {

    @Schema(description = "Pinned artifact version", example = "2.7.1-SNAPSHOT")
    public String version = "";

    @Schema(description = "Pinned Docker image version/tag", example = "dev-2026.05.20-b3140")
    public String imageVersion = "";

    public DepPin() {}

    public DepPin(String version, String imageVersion) {
        this.version = version;
        this.imageVersion = imageVersion;
    }

    public DepPin copy() {
        return new DepPin(this.version, this.imageVersion);
    }
}
