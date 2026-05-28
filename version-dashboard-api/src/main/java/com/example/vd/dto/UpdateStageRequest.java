package com.example.vd.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload for patching one pipeline stage. "
        + "All fields are optional — omit or null to leave unchanged.")
public class UpdateStageRequest {

    @Schema(description = "New artifact version string", example = "3.5.0-SNAPSHOT")
    public String version;

    @Schema(description = "New Docker image version / tag", example = "dev-2026.05.22-b4530")
    public String imageVersion;

    @Schema(description = "Number of tests passed in the latest CI run")
    public Integer testsPassed;

    @Schema(description = "Total tests in the latest CI run")
    public Integer testsTotal;

    @Schema(description = "Username stamped on lastUpdatedBy (falls back to server-side no-op if null)")
    public String lastUpdatedBy;
}
