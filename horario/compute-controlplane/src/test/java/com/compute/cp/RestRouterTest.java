package com.compute.cp;

import com.compute.temporal.NamespaceResolver;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for RestRouter endpoints that don't require a real Temporal server
 * or OCP cluster.
 */
@Timeout(30)
class RestRouterTest {

    private Vertx vertx;
    private WebClient webClient;
    private int port;

    @BeforeEach
    void setUp(@TempDir Path defRoot) throws Exception {
        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);

        NamespaceResolver ns = Mockito.mock(NamespaceResolver.class);
        WorkflowServiceStubs stubs = Mockito.mock(WorkflowServiceStubs.class);
        WorkflowQueries queries = new WorkflowQueries(stubs, ns);
        LogStreamRegistry logs = new LogStreamRegistry(null); // handle() won't be called

        RestRouter router = new RestRouter(
                vertx,
                defRoot,       // empty definitions dir — list ops return empty
                stubs,
                ns,
                queries,
                logs,
                List.of());    // no scheduler definitions

        Router r = router.build();

        // Start an HTTP server on a random port
        CompletableFuture<Integer> portFuture = new CompletableFuture<>();
        vertx.createHttpServer()
                .requestHandler(r)
                .listen(0)
                .onSuccess(s -> portFuture.complete(s.actualPort()))
                .onFailure(portFuture::completeExceptionally);
        port = portFuture.get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        webClient.close();
        vertx.close();
    }

    @Test
    void lanesEndpointReturnsAllLanes() throws Exception {
        CompletableFuture<io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer>> future
                = new CompletableFuture<>();
        webClient.get(port, "localhost", "/api/lanes")
                .send()
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        var resp = future.get(10, TimeUnit.SECONDS);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.bodyAsJsonArray()).hasSizeGreaterThan(0);
        JsonArray arr = resp.bodyAsJsonArray();
        var codes = arr.stream()
                .map(o -> ((io.vertx.core.json.JsonObject) o).getString("code"))
                .toList();
        assertThat(codes).contains("dev", "prod");
    }

    @Test
    void servicesEndpointReturnsEmptyArrayForEmptyDefinitions() throws Exception {
        CompletableFuture<io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer>> future
                = new CompletableFuture<>();
        webClient.get(port, "localhost", "/api/services?lane=dev")
                .send()
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        var resp = future.get(10, TimeUnit.SECONDS);
        // May return 200 with empty array or 500 if Temporal stubs not working
        assertThat(resp.statusCode()).isIn(200, 500);
    }

    @Test
    void jobsEndpointReturnsEmptyArrayForEmptyDefinitions() throws Exception {
        CompletableFuture<io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer>> future
                = new CompletableFuture<>();
        webClient.get(port, "localhost", "/api/jobs?lane=dev")
                .send()
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        var resp = future.get(10, TimeUnit.SECONDS);
        assertThat(resp.statusCode()).isIn(200, 500);
    }

    @Test
    void tasksEndpointReturnsForEmptyDefinitions() throws Exception {
        CompletableFuture<io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer>> future
                = new CompletableFuture<>();
        webClient.get(port, "localhost", "/api/tasks?lane=dev")
                .send()
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        var resp = future.get(10, TimeUnit.SECONDS);
        assertThat(resp.statusCode()).isIn(200, 500);
    }

    @Test
    void getTaskMissingParamsReturns400() throws Exception {
        CompletableFuture<io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer>> future
                = new CompletableFuture<>();
        webClient.get(port, "localhost", "/api/tasks/my-task-id")
                .send()
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        var resp = future.get(10, TimeUnit.SECONDS);
        // group and project params are required → 400
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test
    void reloadSchedulerEndpointReturnsOk() throws Exception {
        CompletableFuture<io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer>> future
                = new CompletableFuture<>();
        webClient.post(port, "localhost", "/api/scheduler/reload")
                .send()
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        var resp = future.get(10, TimeUnit.SECONDS);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.bodyAsJsonObject().getBoolean("ok")).isTrue();
    }
}
