package ai.infrrd.idc.watcher.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "watcher")
public record WatcherProperties(
        List<EnvConfig> envs,
        int lookbackHours,
        List<String> reviewSubStatuses,
        List<String> classifierModelTypes,
        int defaultStuckMinutes,
        Map<String, Integer> stageStuckMinutes,
        double slaRiskFraction,
        int sampleSize,
        /* Kubernetes namespaces whose pods are watched by the pods check (app namespaces only). */
        List<String> podNamespaces,
        /* Kubernetes namespaces whose ERROR logs are read by the logs check. */
        List<String> logNamespaces,
        Thresholds thresholds) {

    /**
     * One monitored environment. Credentials come from env vars (same names the MCP servers use),
     * never from this file.
     */
    public record EnvConfig(
            String name,
            boolean enabled,
            String mongoUri,
            String grafanaUrl,
            /* Service-account token (preferred over user/password: can't trigger Grafana's login lockout). */
            String grafanaToken,
            String grafanaUser,
            String grafanaPassword,
            /* Optional: auto-discovered (default Prometheus datasource) when blank. */
            String promDatasourceUid,
            /* optionalParams key holding the client due date; blank = no SLA check for this env. */
            String slaDueDateParam,
            /* Regex of abandoned queues to ignore; blank = none. */
            String ignoredQueues) {
    }

    public record Thresholds(
            int stuckMinDocs,
            int failureMinCount,
            double failureSpikeFactor,
            double llmFailureRate,
            int llmMinCalls,
            int moduleFailures,
            int queueBacklog,
            int podRestarts,
            double heapRatio,
            int newErrorMinCount) {
    }

    public int stuckMinutesFor(String stage) {
        if (stage == null || stageStuckMinutes == null) {
            return defaultStuckMinutes;
        }
        return stageStuckMinutes.getOrDefault(stage, defaultStuckMinutes);
    }

    /** Smallest stuck threshold across all stages: docs idle less than this can't be stuck. */
    public int minStuckMinutes() {
        int min = defaultStuckMinutes;
        if (stageStuckMinutes != null) {
            for (int v : stageStuckMinutes.values()) {
                min = Math.min(min, v);
            }
        }
        return min;
    }
}
