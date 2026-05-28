package com.example.vd.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload for adding a dependency relationship between two projects")
public class AddDependencyRequest {

    @Schema(description = "Project id of the dependency to add — must already exist",
            required = true, example = "rates.pricer")
    public String depId;
}
