package com.compute.temporal.activity.impl;

import com.compute.image.ImageVersionClient;
import com.compute.model.ImageSpec;
import com.compute.model.Lane;
import com.compute.model.LaneRef;
import com.compute.temporal.activity.ImageActivities;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class ImageActivitiesImpl implements ImageActivities {

    private final ImageVersionClient client;

    public ImageActivitiesImpl(ImageVersionClient client) {
        this.client = client;
    }

    @Override
    public List<ImageSpec> fetch(String group, String project, Lane lane, String version) {
        try {
            return client.fetch(new LaneRef(group, project, lane), version)
                    .toCompletionStage().toCompletableFuture().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
