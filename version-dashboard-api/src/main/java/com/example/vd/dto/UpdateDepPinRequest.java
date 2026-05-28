package com.example.vd.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload for updating the pinned versions of one dependency at one stage")
public class UpdateDepPinRequest {

    @Schema(description = "New pinned artifact version", example = "2.8.0-SNAPSHOT")
    public String version;

    @Schema(description = "New pinned Docker image version / tag", example = "dev-2026.05.22-b3200")
    public String imageVersion;
}
