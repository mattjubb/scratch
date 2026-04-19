package com.vasara.datapedia.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The serialization format of a registered schema")
public enum SchemaType {

    AVRO,
    PROTOBUF;
}
