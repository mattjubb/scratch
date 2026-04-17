package io.acme.orchestrator.k8s;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * One {@link ObjectMapper} configured identically across the codebase.
 * Registers the JSR-310 module for {@link java.time.Duration} / {@link java.time.Instant}
 * handling used by our records.
 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private Json() {}
}
