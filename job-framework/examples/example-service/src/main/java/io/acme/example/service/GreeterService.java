package io.acme.example.service;

import io.acme.example.shared.Greeter;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * Toy always-on HTTP service.
 * <p>
 * Mirrors a real internal service: /health/ready and /health/live for the
 * Kubernetes probes the orchestrator wires in, plus a {@code /greet/:name}
 * endpoint that exercises the shared layer. If the {@code shared-lib} init
 * container fails to stage its jar, this class will not classload and the
 * pod will crash — which is exactly the feedback loop you want.
 */
public final class GreeterService {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Vertx vertx = Vertx.vertx();

        Router r = Router.router(vertx);
        r.get("/health/live").handler(c -> c.response().end("live"));
        r.get("/health/ready").handler(c -> c.response().end("ready"));
        r.get("/greet/:name").handler(c ->
                c.response().end(Greeter.greet(c.pathParam("name"))));

        vertx.createHttpServer().requestHandler(r).listen(port)
                .onSuccess(s -> System.out.println("GreeterService listening on " + port))
                .onFailure(Throwable::printStackTrace);
    }
}
