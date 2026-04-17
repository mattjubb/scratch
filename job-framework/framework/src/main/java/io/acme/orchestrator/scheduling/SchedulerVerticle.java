package io.acme.orchestrator.scheduling;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.acme.orchestrator.dag.DependencyEngine;
import io.acme.orchestrator.model.JobDefinition;
import io.acme.orchestrator.model.Schedule;
import io.vertx.core.AbstractVerticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Vert.x verticle that ticks once per second and asks each scheduled job:
 * "has your cron fired since I last looked?" If yes, it notifies the
 * {@link DependencyEngine}, which in turn decides whether to fire immediately
 * or defer until dependencies complete.
 * <p>
 * We compute "next fire time" per job at registration and every time we fire,
 * so drift over missed ticks is bounded to one second.
 */
public final class SchedulerVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(SchedulerVerticle.class);
    private static final CronParser PARSER =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    private final DependencyEngine engine;
    private final Map<String, ZonedDateTime> nextFire = new HashMap<>();

    public SchedulerVerticle(DependencyEngine engine) { this.engine = engine; }

    @Override
    public void start() {
        // Per-second tick. Cheap: one map walk per second.
        vertx.setPeriodic(1000, tid -> tick());
        log.info("scheduler started");
    }

    public void track(JobDefinition def) {
        if (def.schedule() instanceof Schedule.Manual) {
            nextFire.remove(def.name());
            return;
        }
        nextFire.put(def.name(), computeNext(def, ZonedDateTime.now(def.schedule().zone())));
        log.info("tracking {} next fire at {}", def.name(), nextFire.get(def.name()));
    }

    public void untrack(String name) { nextFire.remove(name); }

    private void tick() {
        for (Map.Entry<String, ZonedDateTime> e : new HashMap<>(nextFire).entrySet()) {
            JobDefinition def = engine.get(e.getKey()).orElse(null);
            if (def == null) { nextFire.remove(e.getKey()); continue; }
            ZonedDateTime now = ZonedDateTime.now(def.schedule().zone());
            if (!now.isBefore(e.getValue())) {
                String runId = UUID.randomUUID().toString().substring(0, 8);
                engine.scheduleFire(def.name(), runId, e.getValue().toInstant());
                nextFire.put(def.name(), computeNext(def, now));
            }
        }
    }

    private ZonedDateTime computeNext(JobDefinition def, ZonedDateTime from) {
        Cron cron = PARSER.parse(def.schedule().toCronExpression());
        return ExecutionTime.forCron(cron).nextExecution(from)
                .orElseThrow(() -> new IllegalStateException(
                        "cron has no future executions: " + def.schedule().toCronExpression()));
    }
}
