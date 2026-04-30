package com.tradeworkflow.api;

import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;

public class JerseyConfig extends ResourceConfig {

    public JerseyConfig() {
        packages("com.tradeworkflow.api");
        register(JacksonFeature.class);
        register(JacksonConfig.class);
        register(CORSFilter.class);
    }
}
