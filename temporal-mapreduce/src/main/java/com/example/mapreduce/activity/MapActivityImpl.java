package com.example.mapreduce.activity;

public class MapActivityImpl implements MapActivity {

    @Override
    public long processTask(int taskIndex) {
        return (long) taskIndex * taskIndex;
    }
}
