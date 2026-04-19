package com.vasara.datapedia.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "A registered schema entry within a group and project, identified by a unique numeric ID")
public class SchemaEntry {

    @Schema(description = "Unique numeric registry ID", example = "1001", requiredMode = Schema.RequiredMode.REQUIRED)
    private long id;

    @Schema(description = "Top-level organizational group", example = "trading", requiredMode = Schema.RequiredMode.REQUIRED)
    private String group;

    @Schema(description = "Project within the group (no hyphens)", example = "orders", requiredMode = Schema.RequiredMode.REQUIRED)
    private String project;

    @Schema(description = "Schema name", example = "OrderEvent", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "Fully qualified namespace (com.vasara.schemas.{group}.{project})",
            example = "com.vasara.schemas.trading.orders",
            accessMode = Schema.AccessMode.READ_ONLY)
    private String namespace;

    @Schema(description = "Schema serialization format", requiredMode = Schema.RequiredMode.REQUIRED)
    private SchemaType schemaType;

    @Schema(description = "Latest version number", example = "2", accessMode = Schema.AccessMode.READ_ONLY)
    private int latestVersion;

    @Schema(description = "All registered versions of this schema, ordered ascending")
    private List<SchemaVersion> versions;

    public SchemaEntry() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNamespace() {
        return "com.vasara.schemas." + group + "." + project;
    }

    public void setNamespace(String namespace) {
        // derived — setter is a no-op, present for deserialization tolerance
    }

    public SchemaType getSchemaType() {
        return schemaType;
    }

    public void setSchemaType(SchemaType schemaType) {
        this.schemaType = schemaType;
    }

    public int getLatestVersion() {
        return versions != null && !versions.isEmpty()
                ? versions.get(versions.size() - 1).getVersion()
                : 0;
    }

    public void setLatestVersion(int latestVersion) {
        // derived — no-op
    }

    public List<SchemaVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<SchemaVersion> versions) {
        this.versions = versions;
    }
}
