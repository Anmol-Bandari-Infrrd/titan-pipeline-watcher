package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import ai.infrrd.idc.watcher.check.CheckResult.Finding;
import ai.infrrd.idc.watcher.check.CheckResult.Severity;
import ai.infrrd.idc.watcher.config.WatcherProperties;
import ai.infrrd.idc.watcher.env.EnvContext;
import ai.infrrd.idc.watcher.env.GrafanaClient;

/**
 * RabbitMQ health via the env's Grafana/Prometheus. The one infra signal that is shown as a
 * problem, because a dead or stalled consumer directly causes stuck documents.
 * Both queries verified on usprod 2026-10-19 (quiet; backlog rule 0 hits in a 7-day backtest).
 */
@Component
public class QueueCheck implements Check {

    private final WatcherProperties props;

    public QueueCheck(WatcherProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "queues";
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(1);
    }

    @Override
    public boolean appliesTo(EnvContext env) {
        return env.grafana() != null;
    }

    @Override
    public CheckResult run(EnvContext env, Instant now) throws Exception {
        var ignored = env.config().ignoredQueues();
        var sel = ignored == null || ignored.isBlank() ? "" : "{queue!~\"" + ignored + "\"}";
        var ready = "sum by (queue) (rabbitmq_detailed_queue_messages_ready" + sel + ")";
        var consumers = "sum by (queue) (rabbitmq_detailed_queue_consumers" + sel + ")";

        // Messages waiting with zero consumers at every minute of the last 10, on a queue that DID
        // have a consumer in the last 7 days. The guard drops abandoned queues holding one stale
        // message (seen 2026-10-19 on caprod/euprod/rkt2prod) without hand-kept ignore lists.
        // It must be long: a 24h guard hid snprod ml-crop-classification, dead for over a day.
        var noConsumer = env.grafana().instantQuery(
                ready + " and on (queue) (count_over_time(((" + ready + " > 0) and on (queue) (" + consumers
                        + " == 0))[10m:1m]) >= 10)"
                        + " and on (queue) (max_over_time(" + consumers + "[7d:1h]) > 0)");
        // Never below the backlog threshold in 30 min and still growing: covers the "healthy pod,
        // stalled consumer" case (IDCST-18908) where consumers > 0 but nothing is delivered.
        int backlog = props.thresholds().queueBacklog();
        var notDraining = env.grafana().instantQuery(
                "(min_over_time(" + ready + "[30m:1m]) > " + backlog + ") and on (queue) (delta(" + ready + "[30m:1m]) > 0)");
        var current = env.grafana().instantQuery(ready);

        var findings = new ArrayList<Finding>();
        noConsumer.forEach(s -> findings.add(new Finding(Severity.CRITICAL, "No consumers · " + s.labels().get("queue"),
                Math.round(s.value()), "messages waiting, 0 consumers for 10 min", List.of(), queueLinks(env, s.labels().get("queue")))));
        notDraining.forEach(s -> findings.add(new Finding(Severity.WARNING, "Queue not draining · " + s.labels().get("queue"),
                Math.round(s.value()), "backlog > " + backlog + " for 30 min and growing", List.of(),
                queueLinks(env, s.labels().get("queue")))));
        double totalReady = current.stream().mapToDouble(GrafanaClient.Sample::value).sum();
        return new CheckResult(findings, Map.of("queueReady", totalReady));
    }

    private static List<ai.infrrd.idc.watcher.check.CheckResult.Link> queueLinks(EnvContext env, String queue) {
        var q = "queue=" + GrafanaLinks.literal(queue);
        // Different metric names keep both series visible in one panel.
        return GrafanaLinks.of(env, () -> env.grafana().promLink("Queue messages & consumers (6h)",
                "rabbitmq_detailed_queue_messages_ready{" + q + "} or rabbitmq_detailed_queue_consumers{" + q + "}",
                "now-6h", "now"));
    }
}
