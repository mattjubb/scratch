package com.vasara.datapedia.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A directed reference from one schema field to another schema")
public class SchemaReference {

    @Schema(description = "Registry ID of the source schema", example = "1001")
    private long fromSchemaId;

    @Schema(description = "Name of the source schema", example = "OrderEvent")
    private String fromSchemaName;

    @Schema(description = "Field name on the source schema that holds the reference", example = "instrument")
    private String fieldName;

    @Schema(description = "Registry ID of the target schema", example = "1003")
    private long toSchemaId;

    @Schema(description = "Name of the target schema", example = "InstrumentRef")
    private String toSchemaName;

    public SchemaReference() {
    }

    public SchemaReference(long fromSchemaId, String fromSchemaName, String fieldName,
                           long toSchemaId, String toSchemaName) {
        this.fromSchemaId = fromSchemaId;
        this.fromSchemaName = fromSchemaName;
        this.fieldName = fieldName;
        this.toSchemaId = toSchemaId;
        this.toSchemaName = toSchemaName;
    }

    public long getFromSchemaId() {
        return fromSchemaId;
    }

    public void setFromSchemaId(long fromSchemaId) {
        this.fromSchemaId = fromSchemaId;
    }

    public String getFromSchemaName() {
        return fromSchemaName;
    }

    public void setFromSchemaName(String fromSchemaName) {
        this.fromSchemaName = fromSchemaName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public long getToSchemaId() {
        return toSchemaId;
    }

    public void setToSchemaId(long toSchemaId) {
        this.toSchemaId = toSchemaId;
    }

    public String getToSchemaName() {
        return toSchemaName;
    }

    public void setToSchemaName(String toSchemaName) {
        this.toSchemaName = toSchemaName;
    }
}
