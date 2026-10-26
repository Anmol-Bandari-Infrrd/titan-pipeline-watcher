package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import ai.infrrd.idc.watcher.check.CheckResult.Finding;
import ai.infrrd.idc.watcher.check.CheckResult.Severity;
import ai.infrrd.idc.watcher.config.WatcherProperties;
import ai.infrrd.idc.watcher.env.EnvContext;
import ai.infrrd.idc.watcher.env.GrafanaClient;

/**
 * Pod health in the app namespaces, via the env's Grafana/Prometheus (kube-state-metrics + JVM metrics,
 * present on all 9 prods as of 2026-10-19). Everything is grouped per workload (pod name minus the
 * ReplicaSet hash) so a crash storm across many KEDA pods is one line, not fifty.
 *
 * Deliberately NOT included: "container memory > 90% of limit". On 2026-10-19 it matched ~20 pods
 * across prods (Mongo cache, JVMs sized to the limit) and almost none of them restarted in 7 days.
 */
@Component
public class PodCheck implements Check {

    private final WatcherProperties props;

    public PodCheck(WatcherProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "pods";
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(2);
    }

    @Override
    public boolean appliesTo(EnvContext env) {
        return env.grafana() != null;
    }

    @Override
    public CheckResult run(EnvContext env, Instant now) throws Exception {
        var g = env.grafana();
        var ns = "namespace=~\"" + String.join("|", props.podNamespaces()) + "\"";
        int minRestarts = props.thresholds().podRestarts();

        // In CrashLoopBackOff at every minute of the last 5.
        var crashLoop = g.instantQuery(byWorkload("count",
                "count_over_time((max by (namespace,pod) (kube_pod_container_status_waiting_reason{reason=\"CrashLoopBackOff\","
                        + ns + "}))[5m:1m]) >= 5"));
        // Last kill reason is OOMKilled AND the pod restarted in the last 30 min. The reason alone
        // sticks to a pod for days, so without the restart condition old kills would look new.
        var oom = g.instantQuery(byWorkload("count",
                "(max by (namespace,pod) (kube_pod_container_status_last_terminated_reason{reason=\"OOMKilled\"," + ns
                        + "}) == 1) and on (namespace,pod) (sum by (namespace,pod) (increase(kube_pod_container_status_restarts_total{"
                        + ns + "}[30m])) > 0)"));
        // Restarts for any reason, summed per workload. Caught the swbcprod pdf-tiff-splitter OOM storm
        // (Oct 12-17, ~100 restarts/day) and is quiet otherwise.
        var restarts = g.instantQuery(byWorkload("sum",
                "increase(kube_pod_container_status_restarts_total{" + ns + "}[30m])") + " >= " + minRestarts);
        // Java heap above the threshold for 15 min: early warning before an OutOfMemoryError.
        var heap = g.instantQuery(byWorkload("max",
                "min_over_time((sum by (namespace,pod) (jvm_memory_used_bytes{area=\"heap\"," + ns
                        + "} and on (namespace,pod,id) (jvm_memory_max_bytes{area=\"heap\"} > 0))"
                        + " / sum by (namespace,pod) (jvm_memory_max_bytes{area=\"heap\"," + ns + "} > 0))[15m:1m])")
                + " > " + props.thresholds().heapRatio());
        // Deployment missing replicas for 10 straight minutes (rollout stuck, can't schedule, image pull...).
        var unavailable = g.instantQuery("min_over_time((max by (namespace,deployment) (kube_deployment_status_replicas_unavailable{"
                + ns + "}))[10m:1m]) > 0");

        var findings = new ArrayList<Finding>();
        var flagged = new HashSet<String>();
        crashLoop.forEach(s -> {
            flagged.add(workload(s));
            findings.add(new Finding(Severity.CRITICAL, "Crash loop · " + workload(s), Math.round(s.value()),
                    "pods in CrashLoopBackOff for 5 min (" + s.labels().get("namespace") + ")", List.of(),
                    podLinks(env, s, restartsExpr(s))));
        });
        oom.forEach(s -> {
            flagged.add(workload(s));
            findings.add(new Finding(Severity.CRITICAL, "OOMKilled · " + workload(s), Math.round(s.value()),
                    "pods killed for memory in the last 30 min (" + s.labels().get("namespace") + ")", List.of(),
                    podLinks(env, s, restartsExpr(s))));
        });
        double totalRestarts = 0;
        for (var s : restarts) {
            totalRestarts += s.value();
            if (!flagged.contains(workload(s))) { // already shown as crash loop / OOM
                findings.add(new Finding(Severity.WARNING, "Restarting · " + workload(s), Math.round(s.value()),
                        "restarts in the last 30 min (" + s.labels().get("namespace") + ")", List.of(),
                        podLinks(env, s, restartsExpr(s))));
            }
        }
        heap.forEach(s -> findings.add(new Finding(Severity.WARNING, "Heap high · " + workload(s),
                Math.round(s.value() * 100), "% Java heap used for 15 min (" + s.labels().get("namespace") + ")", List.of(),
                podLinks(env, s, "sum by (pod) (jvm_memory_used_bytes{area=\"heap\"," + podSelector(s)
                        + "}) / sum by (pod) (jvm_memory_max_bytes{area=\"heap\"," + podSelector(s) + "} > 0)"))));
        unavailable.forEach(s -> findings.add(new Finding(Severity.WARNING, "Pods unavailable · " + s.labels().get("deployment"),
                Math.round(s.value()), "replicas missing for 10 min (" + s.labels().get("namespace") + ")", List.of(),
                GrafanaLinks.of(env, () -> env.grafana().promLink("Missing replicas (6h)",
                        "kube_deployment_status_replicas_unavailable{namespace=" + GrafanaLinks.literal(s.labels().get("namespace"))
                                + ",deployment=" + GrafanaLinks.literal(s.labels().get("deployment")) + "}", "now-6h", "now")))));

        return new CheckResult(findings, Map.of("podRestarts30m", totalRestarts));
    }

    /** Pods of this workload: "<workload>-<hash>-<id>" for deployments, or exactly "<workload>" for StatefulSets. */
    private static String podSelector(GrafanaClient.Sample s) {
        return "namespace=" + GrafanaLinks.literal(s.labels().get("namespace"))
                + ",pod=~" + GrafanaLinks.literal(workload(s).replace(".", "\\.") + "(-.*)?");
    }

    private static String restartsExpr(GrafanaClient.Sample s) {
        return "sum by (pod) (increase(kube_pod_container_status_restarts_total{" + podSelector(s) + "}[30m]))";
    }

    /** A metrics link (what triggered the finding) + the workload's logs. */
    private static List<CheckResult.Link> podLinks(EnvContext env, GrafanaClient.Sample s, String promQl) {
        return GrafanaLinks.of(env,
                () -> env.grafana().promLink("Metrics in Grafana (6h)", promQl, "now-6h", "now"),
                () -> env.grafana().lokiLink("Logs of " + workload(s) + " (1h)", "{" + podSelector(s) + "}", "now-1h", "now"));
    }

    /**
     * Wraps a per-pod expression so it is aggregated per workload: pod name minus the ReplicaSet hash,
     * or the pod name itself when there is no hash (StatefulSets such as mng-mongodb-0).
     */
    private static String byWorkload(String agg, String perPodExpr) {
        return agg + " by (namespace, workload) (label_replace(label_replace(" + perPodExpr
                + ", \"workload\", \"$1\", \"pod\", \"(.*)\"), \"workload\", \"$1\", \"pod\", \"(.*)-[a-z0-9]{8,10}-[a-z0-9]{5}\"))";
    }

    private static String workload(GrafanaClient.Sample s) {
        var w = s.labels().get("workload");
        return w == null || w.isBlank() ? s.labels().getOrDefault("pod", "unknown") : w;
    }
}
