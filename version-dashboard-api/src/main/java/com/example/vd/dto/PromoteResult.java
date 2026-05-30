package com.example.vd.dto;

import com.example.vd.model.Stage;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

@Schema(description = "Stages that were modified as a result of a promote operation, "
        + "keyed by stage identifier")
public class PromoteResult {

    @Schema(description = "Map of stage-key → updated Stage for every stage that changed")
    public Map<String, Stage> updatedStages = new LinkedHashMap<>();

    public PromoteResult() {}

    public PromoteResult put(String key, Stage stage) {
        updatedStages.put(key, stage);
        return this;
    }
}
