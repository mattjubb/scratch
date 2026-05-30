package com.compute.model;

import java.util.Map;

public record SubtaskResult(
        String subtaskId,
        boolean success,
        String message,
        Map<String, Object> output
) {
    public static SubtaskResult ok(String id, Map<String, Object> output) {
        return new SubtaskResult(id, true, "", output);
    }

    public static SubtaskResult failed(String id, String message) {
        return new SubtaskResult(id, false, message, Map.of());
    }
}
