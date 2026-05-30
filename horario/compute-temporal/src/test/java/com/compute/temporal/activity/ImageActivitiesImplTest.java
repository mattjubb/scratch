package com.compute.temporal.activity;

import com.compute.model.ImageSpec;
import com.compute.model.Lane;
import com.compute.temporal.activity.impl.ImageActivitiesImpl;
import com.compute.image.ImageVersionClient;
import io.vertx.core.Vertx;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageActivitiesImplTest {

    private Vertx vertx;
    private ImageActivitiesImpl impl;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        // Empty baseUrl → dry-run mode (returns synthetic image)
        impl = new ImageActivitiesImpl(new ImageVersionClient(vertx, ""));
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    void fetchReturnsDryRunImageInDryRunMode() {
        List<ImageSpec> images = impl.fetch("rates", "swaps", Lane.DEV, "dev");
        assertThat(images).hasSize(1);
        assertThat(images.get(0).imageRef()).contains("dryrun");
        assertThat(images.get(0).name()).isEqualTo("dryrun");
    }

    @Test
    void fetchWithProdLane() {
        List<ImageSpec> images = impl.fetch("fx", "options", Lane.PROD, "1.2.3");
        assertThat(images).hasSize(1);
        assertThat(images.get(0).imageRef()).contains("1.2.3");
    }
}
