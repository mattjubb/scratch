package com.vasara.datapedia.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Lightweight summary of a schema for tree/listing views (no version payloads)")
public class SchemaSummary {

    @Schema(description = "Unique numeric registry ID", example = "1001")
    private long id;

    @Schema(description = "Top-level organizational group", example = "trading")
    private String group;

    @Schema(description = "Project within the group", example = "orders")
    private String project;

    @Schema(description = "Schema name", example = "OrderEvent")
    private String name;

    @Schema(description = "Fully qualified namespace", example = "com.vasara.schemas.trading.orders")
    private String namespace;

    @Schema(description = "Schema serialization format")
    private SchemaType schemaType;

    @Schema(description = "Latest version number", example = "2")
    private int latestVersion;

    @Schema(description = "Total number of registered versions", example = "2")
    private int versionCount;

    public SchemaSummary() {
    }

    public SchemaSummary(SchemaEntry entry) {
        this.id = entry.getId();
        this.group = entry.getGroup();
        this.project = entry.getProject();
        this.name = entry.getName();
        this.namespace = entry.getNamespace();
        this.schemaType = entry.getSchemaType();
        this.latestVersion = entry.getLatestVersion();
        this.versionCount = entry.getVersions() != null ? entry.getVersions().size() : 0;
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
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public SchemaType getSchemaType() {
        return schemaType;
    }

    public void setSchemaType(SchemaType schemaType) {
        this.schemaType = schemaType;
    }

    public int getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(int latestVersion) {
        this.latestVersion = latestVersion;
    }

    public int getVersionCount() {
        return versionCount;
    }

    public void setVersionCount(int versionCount) {
        this.versionCount = versionCount;
    }
}
