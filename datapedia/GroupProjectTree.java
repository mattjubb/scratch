package com.vasara.datapedia.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Hierarchical tree of all schemas organized by group and project")
public class GroupProjectTree {

    @Schema(description = "Groups mapped to their projects, each project mapped to its schema summaries")
    private Map<String, Map<String, List<SchemaSummary>>> groups;

    @Schema(description = "Total number of registered schemas", example = "10")
    private int totalSchemas;

    @Schema(description = "Total number of distinct groups", example = "3")
    private int totalGroups;

    @Schema(description = "Total number of distinct projects", example = "6")
    private int totalProjects;

    public GroupProjectTree() {
    }

    public GroupProjectTree(Map<String, Map<String, List<SchemaSummary>>> groups,
                            int totalSchemas, int totalGroups, int totalProjects) {
        this.groups = groups;
        this.totalSchemas = totalSchemas;
        this.totalGroups = totalGroups;
        this.totalProjects = totalProjects;
    }

    public Map<String, Map<String, List<SchemaSummary>>> getGroups() {
        return groups;
    }

    public void setGroups(Map<String, Map<String, List<SchemaSummary>>> groups) {
        this.groups = groups;
    }

    public int getTotalSchemas() {
        return totalSchemas;
    }

    public void setTotalSchemas(int totalSchemas) {
        this.totalSchemas = totalSchemas;
    }

    public int getTotalGroups() {
        return totalGroups;
    }

    public void setTotalGroups(int totalGroups) {
        this.totalGroups = totalGroups;
    }

    public int getTotalProjects() {
        return totalProjects;
    }

    public void setTotalProjects(int totalProjects) {
        this.totalProjects = totalProjects;
    }
}
