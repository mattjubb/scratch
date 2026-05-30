package com.example.randomnumbers;

import com.compute.model.SubtaskRequest;
import com.compute.model.SubtaskResult;
import com.compute.temporal.activity.SubtaskActivities;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subtask activity that generates random doubles and returns summary statistics.
 *
 * <p>Recognised {@code args} keys:
 * <table>
 *   <tr><th>Key</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>count</td><td>10000</td><td>How many random doubles to generate per subtask</td></tr>
 *   <tr><td>seed</td><td>-1</td><td>RNG seed; -1 = use ThreadLocalRandom (non-deterministic)</td></tr>
 *   <tr><td>min</td><td>0.0</td><td>Lower bound of the uniform distribution</td></tr>
 *   <tr><td>max</td><td>1.0</td><td>Upper bound of the uniform distribution</td></tr>
 * </table>
 *
 * <p>Each subtask generates {@code count} doubles uniformly in {@code [min, max)}
 * and returns:
 * <ul>
 *   <li>{@code count} — number of samples generated</li>
 *   <li>{@code min} / {@code max} — observed extremes</li>
 *   <li>{@code mean} — arithmetic mean</li>
 *   <li>{@code stddev} — population standard deviation</li>
 *   <li>{@code sum} — total sum (useful for aggregation in downstream jobs)</li>
 *   <li>{@code subtaskId} — echoed back for correlation</li>
 * </ul>
 *
 * <p>Registered via SPI: {@code META-INF/services/com.compute.temporal.activity.SubtaskActivities}.
 */
public final class RandomNumberActivities implements SubtaskActivities {

    private static final Logger log = LoggerFactory.getLogger(RandomNumberActivities.class);

    @Override
    public SubtaskResult execute(SubtaskRequest request) {
        long count  = longArg(request, "count", 10_000);
        long seed   = longArg(request, "seed", -1);
        double lo   = doubleArg(request, "min", 0.0);
        double hi   = doubleArg(request, "max", 1.0);

        if (hi <= lo) throw new IllegalArgumentException("max must be > min");
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");

        log.debug("subtask {} — generating {} random doubles in [{}, {}), seed={}",
                request.subtaskId(), count, lo, hi, seed);

        Random rng = seed >= 0
                ? new Random(seed ^ subtaskHash(request.subtaskId()))  // per-subtask seed
                : ThreadLocalRandom.current();

        double range = hi - lo;

        // Single-pass Welford-style accumulation for numerically stable mean + variance.
        long n = 0;
        double runningMean = 0.0;
        double runningM2   = 0.0;
        double observedMin = Double.MAX_VALUE;
        double observedMax = -Double.MAX_VALUE;
        double sum         = 0.0;

        for (long i = 0; i < count; i++) {
            double v = lo + rng.nextDouble() * range;

            if (v < observedMin) observedMin = v;
            if (v > observedMax) observedMax = v;
            sum += v;

            n++;
            double delta  = v - runningMean;
            runningMean  += delta / n;
            double delta2 = v - runningMean;
            runningM2    += delta * delta2;
        }

        double variance = n > 1 ? runningM2 / n : 0.0;
        double stddev   = Math.sqrt(variance);

        log.info("subtask {} done — count={} mean={:.4f} stddev={:.4f} min={:.4f} max={:.4f}",
                request.subtaskId(), count, runningMean, stddev, observedMin, observedMax);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subtaskId", request.subtaskId());
        out.put("count",  count);
        out.put("sum",    sum);
        out.put("min",    observedMin);
        out.put("max",    observedMax);
        out.put("mean",   runningMean);
        out.put("stddev", stddev);
        return SubtaskResult.ok(request.subtaskId(), out);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static long longArg(SubtaskRequest r, String key, long def) {
        Object v = r.args().get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static double doubleArg(SubtaskRequest r, String key, double def) {
        Object v = r.args().get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    /** Stable 64-bit hash of the subtask ID — ensures reproducible seeding per subtask. */
    private static long subtaskHash(String id) {
        long h = 0xcbf29ce484222325L;   // FNV-1a 64-bit
        for (int i = 0; i < id.length(); i++) {
            h ^= id.charAt(i);
            h *= 0x00000100000001B3L;
        }
        return h;
    }
}
