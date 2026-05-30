package com.compute.image;

import com.compute.model.ImageSpec;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls the ImageVersionAPI keyed by {@code (group, project, lane[, version])} and
 * returns the ordered list of init-container images. The API response contract:
 *
 * <pre>
 * { "images": [ { "name": "core", "imageRef": "registry/x:1.2.3", "order": 0 }, ... ] }
 * </pre>
 *
 * In dry-run mode the configured baseUrl is empty and the client returns a single
 * synthetic image referencing the runner base — enough for local control-plane runs.
 */
public final class ImageVersionClient {

    private static final Logger log = LoggerFactory.getLogger(ImageVersionClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebClient client;
    private final String baseUrl;
    private final boolean dryRun;

    public ImageVersionClient(Vertx vertx, String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.dryRun = this.baseUrl.isBlank();
        this.client = WebClient.create(vertx, new WebClientOptions().setKeepAlive(true));
    }

    public Future<List<ImageSpec>> fetch(LaneRef ref, String version) {
        if (dryRun) {
            return Future.succeededFuture(List.of(
                    new ImageSpec("dryrun", "registry.local/compute/dryrun:" + version, 0)
            ));
        }
        String url = baseUrl + "/images?group=" + enc(ref.group())
                + "&project=" + enc(ref.project())
                + "&lane=" + enc(ref.lane().code())
                + "&version=" + enc(version);
        log.debug("ImageVersionAPI GET {}", url);
        return client.getAbs(url).send().map(resp -> {
            if (resp.statusCode() != 200) {
                throw new RuntimeException("ImageVersionAPI status " + resp.statusCode()
                        + " body=" + resp.bodyAsString());
            }
            return parse(resp.bodyAsString());
        });
    }

    static List<ImageSpec> parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode arr = root.path("images");
            if (!arr.isArray()) throw new IllegalStateException("missing 'images' array");
            List<ImageSpec> out = new ArrayList<>();
            for (JsonNode n : arr) {
                out.add(new ImageSpec(
                        n.path("name").asText(),
                        n.path("imageRef").asText(),
                        n.path("order").asInt(out.size())
                ));
            }
            out.sort((a, b) -> Integer.compare(a.order(), b.order()));
            return out;
        } catch (Exception e) {
            throw new RuntimeException("invalid ImageVersionAPI body: " + e.getMessage(), e);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    public void close() {
        client.close();
    }

    /** Convenience for callers without a LaneRef but with the three fields. */
    public Future<List<ImageSpec>> fetch(String group, String project, Lane lane, String version) {
        return fetch(new LaneRef(group, project, lane), version);
    }
}
