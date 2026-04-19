package com.vasara.datapedia.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request payload for registering a new schema or a new version of an existing schema")
public class SchemaRegistrationRequest {

    @Schema(description = "Top-level organizational group", example = "trading", requiredMode = Schema.RequiredMode.REQUIRED)
    private String group;

    @Schema(description = "Project within the group (no hyphens allowed)", example = "orders", requiredMode = Schema.RequiredMode.REQUIRED)
    private String project;

    @Schema(description = "Schema name", example = "OrderEvent", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "Schema serialization format", requiredMode = Schema.RequiredMode.REQUIRED)
    private SchemaType schemaType;

    @Schema(description = "Human-readable documentation for this version",
            example = "Represents a trading order lifecycle event across all asset classes.")
    private String doc;

    @Schema(description = "The raw schema definition (Avro JSON, Protobuf descriptor, etc.)", requiredMode = Schema.RequiredMode.REQUIRED)
    private JsonNode schema;

    public SchemaRegistrationRequest() {
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

    public SchemaType getSchemaType() {
        return schemaType;
    }

    public void setSchemaType(SchemaType schemaType) {
        this.schemaType = schemaType;
    }

    public String getDoc() {
        return doc;
    }

    public void setDoc(String doc) {
        this.doc = doc;
    }

    public JsonNode getSchema() {
        return schema;
    }

    public void setSchema(JsonNode schema) {
        this.schema = schema;
    }
}
