package com.tradeworkflow.model;

public enum TriggerType {
    /** Event must be triggered by an external API call. */
    EXTERNAL,
    /** Event fires automatically when the trade enters the source state. */
    AUTO
}
