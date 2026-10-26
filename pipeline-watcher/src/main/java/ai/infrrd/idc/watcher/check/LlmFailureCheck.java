package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.stereotype.Component;

import ai.infrrd.idc.watcher.check.CheckResult.Finding;
import ai.infrrd.idc.watcher.check.CheckResult.Severity;
import ai.infrrd.idc.watcher.config.WatcherProperties;
import ai.infrrd.idc.watcher.env.EnvContext;

import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;

/** LLM failure rate per model over the last 10 min, from llm_audit (createdDate index). */
@Component
public class LlmFailureCheck implements Check {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final WatcherProperties props;

    public LlmFailureCheck(WatcherProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "llm-failures";
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
        var t = props.thresholds();
        var findings = new ArrayList<Finding>();
        long[] totals = new long[2];
        env.db().getCollection("llm_audit").aggregate(List.of(
                Aggregates.match(Filters.gte("createdDate", Date.from(now.minus(WINDOW)))),
                Aggregates.group("$model",
                        Accumulators.sum("total", 1),
                        Accumulators.sum("failed", new Document("$cond", List.of(new Document("$eq", List.of("$isFailed", true)), 1, 0))))))
                .maxTime(DocQueries.MAX_TIME_SECONDS, TimeUnit.SECONDS)
                .forEach((Document d) -> {
                    long total = ((Number) d.get("total")).longValue();
                    long failed = ((Number) d.get("failed")).longValue();
                    totals[0] += total;
                    totals[1] += failed;
                    if (total >= t.llmMinCalls() && (double) failed / total > t.llmFailureRate()) {
                        var model = String.valueOf(d.get("_id"));
                        findings.add(new Finding(Severity.CRITICAL, "LLM failing · " + model, failed,
                                Math.round(100.0 * failed / total) + "% of " + total + " calls in 10 min", List.of(),
                                GrafanaLinks.of(env, () -> env.grafana().lokiLink("llm-predictor errors (1h)",
                                        "{instance=\"llm-predictor\"} |= \"ERROR\"", "now-1h", "now"))));
                    }
                });
        double rate = totals[0] == 0 ? 0 : (double) totals[1] / totals[0];
        return new CheckResult(findings, Map.of("llmCalls10m", (double) totals[0], "llmFailRate", rate));
    }
}
