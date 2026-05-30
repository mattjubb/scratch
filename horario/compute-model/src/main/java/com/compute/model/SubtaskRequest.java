package com.compute.model;

import java.util.Map;

/**
 * One unit of work inside a parent task. The {@code kind} steers which executor
 * (registered in the worker image via SPI) handles it; {@code args} is a free-form
 * payload.
 */
public record SubtaskRequest(
        String subtaskId,
        String kind,
        Map<String, Object> args
) {}
