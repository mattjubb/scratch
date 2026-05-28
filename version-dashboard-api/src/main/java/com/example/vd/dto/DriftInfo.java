package com.example.vd.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Describes whether a dependency's pinned version at a stage has drifted "
        + "from the dependency project's live version at the same stage")
public class DriftInfo {

    @Schema(description = "The dependency project id", example = "rates.pricer")
    public String depId;

    @Schema(description = "True if the pinned artifact version differs from the dep's live version")
    public boolean versionDrifted;

    @Schema(description = "True if the pinned image version differs from the dep's live image version")
    public boolean imageDrifted;

    @Schema(description = "Artifact version pinned in this project's stage", example = "2.7.0-SNAPSHOT")
    public String pinnedVersion;

    @Schema(description = "Artifact version currently in the dependency project's same stage",
            example = "2.7.1-SNAPSHOT")
    public String actualVersion;

    @Schema(description = "Image version pinned in this project's stage")
    public String pinnedImageVersion;

    @Schema(description = "Image version currently in the dependency project's same stage")
    public String actualImageVersion;
}
