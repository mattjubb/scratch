package com.example.vd.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A lead developer contact for a project")
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeadDeveloper {

    @Schema(description = "Full display name", example = "Asha Patel")
    public String name = "";

    @Schema(description = "Work e-mail address", example = "asha.patel@example.com")
    public String email = "";

    public LeadDeveloper() {}

    public LeadDeveloper(String name, String email) {
        this.name = name;
        this.email = email;
    }
}
