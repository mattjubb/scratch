package io.acme.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single layer of the runtime classpath.
 * <p>
 * Each layer is an OCI image that ships JARs under {@link #sourcePath()}.
 * At pod startup an init container is created from this image whose sole
 * job is to copy {@code sourcePath/*.jar} into the shared emptyDir mount,
 * where the main container picks them up on its classpath.
 *
 * @param name        stable logical name; becomes the init container name
 * @param image       fully-qualified image reference (registry/repo:tag)
 * @param sourcePath  directory inside the image containing the JARs to copy
 *                    (defaults to {@code /app/lib})
 */
public record CodebaseLayer(
        @JsonProperty("name") String name,
        @JsonProperty("image") String image,
        @JsonProperty("sourcePath") String sourcePath
) {
    public static final String DEFAULT_SOURCE_PATH = "/app/lib";

    @JsonCreator
    public CodebaseLayer {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CodebaseLayer.name is required");
        }
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("CodebaseLayer.image is required");
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            sourcePath = DEFAULT_SOURCE_PATH;
        }
    }

    public static CodebaseLayer of(String name, String image) {
        return new CodebaseLayer(name, image, DEFAULT_SOURCE_PATH);
    }
}
