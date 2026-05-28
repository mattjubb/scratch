package com.example.mapreduce.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface MapActivity {

    @ActivityMethod
    long processTask(int taskIndex);
}
