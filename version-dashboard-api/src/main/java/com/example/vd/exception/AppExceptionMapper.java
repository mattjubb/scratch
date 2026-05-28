package com.example.vd.exception;

import com.example.vd.store.ProjectStore;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Translates typed store exceptions and general validation failures into
 * structured JSON error responses.
 */
@Provider
public class AppExceptionMapper implements ExceptionMapper<RuntimeException> {

    @Override
    public Response toResponse(RuntimeException ex) {
        if (ex instanceof ProjectStore.ProjectNotFoundException) {
            return error(Response.Status.NOT_FOUND, ex.getMessage());
        }
        if (ex instanceof ProjectStore.StageNotFoundException) {
            return error(Response.Status.NOT_FOUND, ex.getMessage());
        }
        if (ex instanceof ProjectStore.ConflictException) {
            return error(Response.Status.CONFLICT, ex.getMessage());
        }
        if (ex instanceof IllegalArgumentException) {
            return error(Response.Status.BAD_REQUEST, ex.getMessage());
        }
        // Let other runtime exceptions propagate as 500
        throw ex;
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", message, "status", status.getStatusCode()))
                .build();
    }
}
