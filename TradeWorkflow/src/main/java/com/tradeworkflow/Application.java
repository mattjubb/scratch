package com.tradeworkflow;

import com.tradeworkflow.api.JerseyConfig;
import com.tradeworkflow.engine.WorkflowEngine;
import org.glassfish.grizzly.http.server.CLStaticHttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static final int    PORT     = 8080;
    public static final String API_BASE = "http://0.0.0.0:" + PORT + "/api/";

    public static void main(String[] args) throws Exception {
        WorkflowEngine engine = WorkflowEngine.getInstance();
        log.info("WorkflowEngine ready – {} workflow(s) loaded", engine.getAllWorkflows().size());

        HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
                URI.create(API_BASE), new JerseyConfig(), false);

        // Serve static dashboard files from classpath:/static/ at /, with no-cache headers
        CLStaticHttpHandler staticHandler =
                new CLStaticHttpHandler(Application.class.getClassLoader(), "static/") {
                    @Override
                    public void service(Request request, Response response) throws Exception {
                        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                        response.addHeader("Pragma", "no-cache");
                        super.service(request, response);
                    }
                };
        staticHandler.setFileCacheEnabled(false);
        server.getServerConfiguration().addHttpHandler(staticHandler, "/");

        server.start();

        log.info("=======================================================");
        log.info("  Trade Workflow System started on port {}", PORT);
        log.info("  Dashboard : http://localhost:{}/", PORT);
        log.info("  REST API  : http://localhost:{}/api/", PORT);
        log.info("  OpenAPI   : http://localhost:{}/openapi.yaml", PORT);
        log.info("  Press Ctrl+C to stop");
        log.info("=======================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            server.shutdownNow();
        }));

        Thread.currentThread().join();
    }
}
