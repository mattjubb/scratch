package com.compute.yaml;

import java.nio.file.Path;

public class DefinitionParseException extends RuntimeException {
    public DefinitionParseException(Path file, Throwable cause) {
        super("failed to parse " + file + ": " + cause.getMessage(), cause);
    }
}
