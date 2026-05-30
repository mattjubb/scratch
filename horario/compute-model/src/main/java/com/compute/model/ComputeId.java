package com.compute.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Slash-prefixed identifier derived from a YAML definition's path relative to
 * a definitions root, e.g. {@code services/trade/booking/booker.yaml} →
 * {@code /trade/booking/booker}.
 */
public record ComputeId(String path) {

    public ComputeId {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            throw new IllegalArgumentException("invalid id: " + path);
        }
    }

    public static ComputeId of(String path) {
        return new ComputeId(path);
    }

    /** Last segment: {@code /a/b/c → c}. */
    public String name() {
        int i = path.lastIndexOf('/');
        return path.substring(i + 1);
    }

    /** Group is the first segment: {@code /rates/swaps/pricer → rates}. */
    public String group() {
        return segments().get(0);
    }

    /** Project is the second segment, if present. */
    public String project() {
        List<String> s = segments();
        return s.size() >= 2 ? s.get(1) : "";
    }

    public List<String> segments() {
        return List.of(path.substring(1).split("/"));
    }

    /**
     * Derive an id from a path relative to the kind root. The kind root is
     * the directory holding all definitions of that kind, e.g. the {@code services/}
     * directory.
     */
    public static ComputeId fromRelativePath(Path relative) {
        String s = relative.toString().replace('\\', '/');
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return new ComputeId("/" + s);
    }
}
