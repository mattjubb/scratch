package com.compute.model;

import java.util.Objects;

/**
 * One init-container image returned by ImageVersionAPI. Each image holds a Spring Boot
 * fat jar at {@code /app/app.jar}; the init container unpacks its {@code BOOT-INF/}
 * onto the shared classpath emptyDir.
 *
 * @param name short identifier (becomes part of init container name)
 * @param imageRef fully qualified image reference, including tag or digest
 * @param order precedence (lower = earlier init container; first writer wins for duplicate jar names)
 */
public record ImageSpec(String name, String imageRef, int order) {
    public ImageSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(imageRef, "imageRef");
    }
}
