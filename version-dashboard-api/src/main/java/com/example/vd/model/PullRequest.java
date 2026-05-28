package com.example.vd.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A pull request merged into a particular pipeline stage")
@JsonIgnoreProperties(ignoreUnknown = true)
public class PullRequest {

    @Schema(description = "PR number", example = "1894")
    public int number;

    @Schema(description = "PR title", example = "Cache reference data writes behind a debounced batcher")
    public String title = "";

    @Schema(description = "GitHub username of the author", example = "asha-patel")
    public String author = "";

    @Schema(description = "URL to the PR",
            example = "https://github.com/example-org/core-datapedia/pull/1894")
    public String url = "";

    public PullRequest() {}

    public PullRequest(int number, String title, String author, String url) {
        this.number = number;
        this.title = title;
        this.author = author;
        this.url = url;
    }
}
