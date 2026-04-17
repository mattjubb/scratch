package io.acme.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * When a job should fire.
 * <p>
 * Two concrete forms are supported:
 * <ul>
 *   <li>{@link Cron} — a standard 5-field Quartz-style expression</li>
 *   <li>{@link TimeOfDay} — fires once per day at a wall-clock local time</li>
 * </ul>
 * A third {@link Manual} variant exists for jobs that only run as dependencies
 * of other jobs or via explicit API trigger.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Schedule.Cron.class, name = "cron"),
        @JsonSubTypes.Type(value = Schedule.TimeOfDay.class, name = "timeOfDay"),
        @JsonSubTypes.Type(value = Schedule.Manual.class, name = "manual")
})
public sealed interface Schedule
        permits Schedule.Cron, Schedule.TimeOfDay, Schedule.Manual {

    /** Used by the scheduler to convert to a cron expression evaluated per-tick. */
    String toCronExpression();

    ZoneId zone();

    record Cron(
            @JsonProperty("expression") String expression,
            @JsonProperty("zone") ZoneId zone
    ) implements Schedule {
        private static final CronParser PARSER =
                new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

        @JsonCreator
        public Cron {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("cron expression required");
            }
            PARSER.parse(expression).validate();
            if (zone == null) zone = ZoneId.systemDefault();
        }

        @Override public String toCronExpression() { return expression; }
    }

    record TimeOfDay(
            @JsonProperty("at") LocalTime at,
            @JsonProperty("zone") ZoneId zone
    ) implements Schedule {
        @JsonCreator
        public TimeOfDay {
            if (at == null) throw new IllegalArgumentException("time required");
            if (zone == null) zone = ZoneId.systemDefault();
        }

        /** Quartz cron: sec min hour dayOfMonth month dayOfWeek */
        @Override public String toCronExpression() {
            return "%d %d %d ? * *".formatted(at.getSecond(), at.getMinute(), at.getHour());
        }
    }

    record Manual() implements Schedule {
        @Override public String toCronExpression() { return ""; }
        @Override public ZoneId zone() { return ZoneId.systemDefault(); }
    }
}
