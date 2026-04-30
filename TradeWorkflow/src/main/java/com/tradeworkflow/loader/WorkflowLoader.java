package com.tradeworkflow.loader;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradeworkflow.model.Workflow;

/**
 * Serialises and deserialises {@link Workflow} definitions from/to YAML.
 */
public class WorkflowLoader {

    private final ObjectMapper yaml;

    public WorkflowLoader() {
        YAMLFactory factory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
        yaml = new ObjectMapper(factory)
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public Workflow fromYaml(String yamlText) throws Exception {
        return yaml.readValue(yamlText, Workflow.class);
    }

    public String toYaml(Workflow workflow) throws Exception {
        return yaml.writeValueAsString(workflow);
    }
}
