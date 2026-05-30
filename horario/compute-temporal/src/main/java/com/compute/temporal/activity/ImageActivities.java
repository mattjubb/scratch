package com.compute.temporal.activity;

import com.compute.model.ImageSpec;
import com.compute.model.Lane;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

/** ImageVersionAPI access used by service/job/task workflows. */
@ActivityInterface
public interface ImageActivities {

    @ActivityMethod
    List<ImageSpec> fetch(String group, String project, Lane lane, String version);
}
