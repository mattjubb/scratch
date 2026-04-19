package com.vasara.datapedia.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A single versioned snapshot of a schema definition")
public class SchemaVersion {

    @Schema(description = "Version number (monotonically increasing)", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private int version;

    @Schema(description = "Human-readable documentation for this version", example = "Represents a trading order lifecycle event across all asset classes.")
    private String doc;

    @Schema(description = "The raw schema definition (Avro JSON, Protobuf descriptor, etc.)", requiredMode = Schema.RequiredMode.REQUIRED)
    private JsonNode schema;

    @Schema(description = "Timestamp when this version was registered")
    private Instant registeredAt;

    @Schema(description = "Identity of the principal that registered this version", example = "ci-pipeline/trading-orders")
    private String registeredBy;

    public SchemaVersion() {
    }

    public SchemaVersion(int version, String doc, JsonNode schema, Instant registeredAt, String registeredBy) {
        this.version = version;
        this.doc = doc;
        this.schema = schema;
        this.registeredAt = registeredAt;
        this.registeredBy = registeredBy;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
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

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public String getRegisteredBy() {
        return registeredBy;
    }

    public void setRegisteredBy(String registeredBy) {
        this.registeredBy = registeredBy;
    }
}
