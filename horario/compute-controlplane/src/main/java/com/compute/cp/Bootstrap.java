package com.compute.cp;

import com.compute.temporal.TemporalConfig;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    public static void main(String[] args) {
        ControlPlaneConfig cp = ControlPlaneConfig.fromEnv();
        TemporalConfig tc = TemporalConfig.fromEnv();

        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new ControlPlaneVerticle(cp, tc))
                .onFailure(err -> {
                    log.error("control-plane failed to start", err);
                    System.exit(1);
                });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down");
            vertx.close();
        }));
    }
}
