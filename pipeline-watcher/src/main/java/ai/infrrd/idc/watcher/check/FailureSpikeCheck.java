package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.stereotype.Component;

import ai.infrrd.idc.watcher.check.CheckResult.Finding;
import ai.infrrd.idc.watcher.check.CheckResult.Severity;
import ai.infrrd.idc.watcher.config.WatcherProperties;
import ai.infrrd.idc.watcher.env.EnvContext;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;

/**
 * Failures per customer in the last 30 min vs the same window yesterday.
 * Uses the {status:1, lastModifiedDate:1} index. Baseline is approximate: a doc that failed
 * yesterday and was reprocessed since no longer counts toward yesterday.
 */
@Component
public class FailureSpikeCheck implements Check {

    private static final Duration WINDOW = Duration.ofMinutes(30);

    private final WatcherProperties props;

    public FailureSpikeCheck(WatcherProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "failure-spike";
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(2);
    }

    @Override
    public boolean appliesTo(EnvContext env) {
        return env.db() != null;
    }

    @Override
    public CheckResult run(EnvContext env, Instant now) {
        var current = failuresByCustomer(env.db(), now.minus(WINDOW), now);
        var baseline = failuresByCustomer(env.db(), now.minus(WINDOW).minus(Duration.ofDays(1)), now.minus(Duration.ofDays(1)));
        var t = props.thresholds();

        var findings = new ArrayList<Finding>();
        long total = 0;
        for (var e : current.entrySet()) {
            long count = e.getValue();
            total += count;
            long base = baseline.getOrDefault(e.getKey(), 0L);
            if (count > t.failureMinCount() && count > t.failureSpikeFactor() * (base + 1)) {
                findings.add(new Finding(Severity.CRITICAL, "Failure spike · customer " + DocQueries.shortId(e.getKey()),
                        count, "last 30 min; same window yesterday: " + base, List.of(),
                        GrafanaLinks.of(env, () -> env.grafana().lokiLink("Orchestrator errors (1h)",
                                "{namespace=\"orch\", instance=\"orchestrator\"} |= \"ERROR\"", "now-1h", "now"))));
            }
        }
        return new CheckResult(findings, Map.of("failed30m", (double) total));
    }

    private Map<String, Long> failuresByCustomer(MongoDatabase db, Instant from, Instant to) {
        var result = new HashMap<String, Long>();
        db.getCollection("document").aggregate(List.of(
                Aggregates.match(Filters.and(
                        Filters.eq("status", "PROCESSING_FAILED"),
                        Filters.gte("lastModifiedDate", Date.from(from)),
                        Filters.lt("lastModifiedDate", Date.from(to)))),
                Aggregates.group("$customerId", Accumulators.sum("n", 1))))
                .maxTime(DocQueries.MAX_TIME_SECONDS, TimeUnit.SECONDS)
                .forEach((Document d) -> result.put(String.valueOf(d.get("_id")), ((Number) d.get("n")).longValue()));
        return result;
    }
}
