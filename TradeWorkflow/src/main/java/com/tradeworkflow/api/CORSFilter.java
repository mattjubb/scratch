package com.tradeworkflow.api;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.ext.Provider;

@Provider
public class CORSFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        res.getHeaders().add("Access-Control-Allow-Origin",  "*");
        res.getHeaders().add("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization");
        res.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD");
        // Disable caching so static-file changes are always picked up during dev.
        res.getHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate");
        res.getHeaders().add("Pragma", "no-cache");
    }
}
