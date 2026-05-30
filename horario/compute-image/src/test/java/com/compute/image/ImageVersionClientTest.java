package com.compute.image;

import com.compute.model.ImageSpec;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import io.vertx.core.Vertx;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageVersionClientTest {

    Vertx vertx;

    @BeforeEach
    void setup() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void teardown() {
        vertx.close();
    }

    @Test
    void dryRunReturnsSyntheticImage() throws Exception {
        ImageVersionClient c = new ImageVersionClient(vertx, "");
        List<ImageSpec> images = c.fetch(new LaneRef("rates", "swaps", Lane.DEV), "dev")
                .toCompletionStage().toCompletableFuture().get();
        assertThat(images).hasSize(1);
        assertThat(images.get(0).imageRef()).contains("dryrun");
        c.close();
    }

    @Test
    void parsesAndSortsByOrder() {
        List<ImageSpec> images = ImageVersionClient.parse("""
                { "images": [
                    { "name": "ext", "imageRef": "r/ext:1", "order": 2 },
                    { "name": "core", "imageRef": "r/core:1", "order": 0 },
                    { "name": "mid", "imageRef": "r/mid:1", "order": 1 }
                ] }
                """);
        assertThat(images).extracting(ImageSpec::name).containsExactly("core", "mid", "ext");
    }

    @Test
    void parseSingleImageUsesInsertionOrderAsDefaultOrder() {
        List<ImageSpec> images = ImageVersionClient.parse("""
                { "images": [ { "name": "only", "imageRef": "r/only:1" } ] }
                """);
        assertThat(images).hasSize(1);
        assertThat(images.get(0).name()).isEqualTo("only");
        assertThat(images.get(0).order()).isEqualTo(0);
    }

    @Test
    void parseThrowsOnInvalidJson() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
            ImageVersionClient.parse("not-json")
        );
    }

    @Test
    void fetchWithStringArgsForwardsToDef() throws Exception {
        ImageVersionClient c = new ImageVersionClient(vertx, "");
        List<ImageSpec> images = c.fetch("rates", "swaps", Lane.DEV, "dev")
                .toCompletionStage().toCompletableFuture().get();
        assertThat(images).hasSize(1);
        c.close();
    }
}
