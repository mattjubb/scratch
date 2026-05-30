package com.example.vd.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Schema(description = "Version data for one of the nine pipeline stages (lane × iteration)")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Stage {

    @Schema(description = "Artifact version string", example = "3.4.1-SNAPSHOT")
    public String version = "";

    @Schema(description = "Docker image version / tag", example = "dev-2026.05.20-b4501")
    public String imageVersion = "";

    @Schema(description = "Pinned dependency versions at this stage, keyed by dependency project id")
    public Map<String, DepPin> deps = new LinkedHashMap<>();

    @Schema(description = "Pull requests merged into this stage's version")
    public List<PullRequest> prs = new ArrayList<>();

    @Schema(description = "ISO-8601 timestamp of the last manual edit, null if never edited",
            example = "2026-05-22T14:30:00.000Z")
    public String lastUpdated;

    @Schema(description = "Username of the last person to edit this stage", example = "asha-patel")
    public String lastUpdatedBy = "";

    @Schema(description = "Number of tests that passed in the latest CI run for this version",
            example = "312")
    public int testsPassed;

    @Schema(description = "Total number of tests in the latest CI run for this version",
            example = "400")
    public int testsTotal;

    public Stage() {}

    /** Deep copy — used during promote/rollback so stages are independent objects. */
    public Stage copy() {
        Stage s = new Stage();
        s.version = this.version;
        s.imageVersion = this.imageVersion;
        s.lastUpdated = this.lastUpdated;
        s.lastUpdatedBy = this.lastUpdatedBy;
        s.testsPassed = this.testsPassed;
        s.testsTotal = this.testsTotal;
        for (Map.Entry<String, DepPin> e : this.deps.entrySet()) {
            s.deps.put(e.getKey(), e.getValue().copy());
        }
        for (PullRequest pr : this.prs) {
            PullRequest copy = new PullRequest(pr.number, pr.title, pr.author, pr.url);
            s.prs.add(copy);
        }
        return s;
    }

    /** Returns an empty stage with the same dep keys initialised to empty pins. */
    public static Stage empty(Stage template) {
        Stage s = new Stage();
        if (template != null) {
            for (String depId : template.deps.keySet()) {
                s.deps.put(depId, new DepPin());
            }
        }
        return s;
    }
}
