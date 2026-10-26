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

/**
 * Same pipeline module failing across many documents in the last 15 min (audit createdDate index).
 * Usually a dependency or deploy problem rather than bad input.
 */
@Component
public class ModuleFailureCheck implements Check {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final WatcherProperties props;

    public ModuleFailureCheck(WatcherProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "module-failures";
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
        var findings = new ArrayList<Finding>();
        long[] total = new long[1];
        env.db().getCollection("audit").aggregate(List.of(
                Aggregates.match(Filters.and(
                        Filters.gte("createdDate", Date.from(now.minus(WINDOW))),
                        Filters.eq("isFailed", true),
                        Filters.exists("moduleName"),
                        // Training rows (e.g. PageClassificationTrainer) carry no documentId and are
                        // not client-facing: on 2026-10-19 they were ~180 failures/15 min on auprod + euprod.
                        Filters.ne("documentId", null))),
                Aggregates.group("$moduleName",
                        Accumulators.sum("n", 1),
                        Accumulators.addToSet("docs", "$documentId"))))
                .maxTime(DocQueries.MAX_TIME_SECONDS, TimeUnit.SECONDS)
                .forEach((Document d) -> {
                    long n = ((Number) d.get("n")).longValue();
                    total[0] += n;
                    if (n > props.thresholds().moduleFailures()) {
                        List<String> docs = d.getList("docs", String.class);
                        var module = String.valueOf(d.get("_id"));
                        findings.add(new Finding(Severity.WARNING, "Module failing · " + module, n,
                                n + " failed audit rows across " + docs.size() + " docs in 15 min",
                                docs.stream().limit(props.sampleSize()).toList(),
                                GrafanaLinks.of(env, () -> env.grafana().lokiLink("Errors mentioning " + module + " (1h)",
                                        "{" + GrafanaLinks.namespaces(props.logNamespaces()) + "} |= \"ERROR\" |= "
                                                + GrafanaLinks.literal(module), "now-1h", "now"))));
                    }
                });
        return new CheckResult(findings, Map.of("moduleFailures15m", (double) total[0]));
    }
}
