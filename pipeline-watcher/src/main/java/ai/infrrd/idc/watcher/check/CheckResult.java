package ai.infrrd.idc.watcher.check;

import java.util.List;
import java.util.Map;

/**
 * Outcome of one check run.
 *
 * @param findings problems found right now (empty = healthy)
 * @param values   numbers worth trending on the TV even when healthy (e.g. "stuck" = 0)
 */
public record CheckResult(List<Finding> findings, Map<String, Double> values) {

    /** INFO never affects an env's colour; it feeds the "Known errors" panel only. */
    public enum Severity { CRITICAL, WARNING, INFO }

    /** A labelled link, e.g. ("Logs in Grafana", "https://grafana…/explore?…"). */
    public record Link(String label, String url) {
    }

    /**
     * @param title     short, TV-readable: what + where, e.g. "Docs stuck at GENIE"
     * @param sampleIds a few document IDs for whoever investigates (never shown on the TV)
     * @param links     Grafana links that open the data behind this finding
     */
    public record Finding(Severity severity, String title, long count, String detail, List<String> sampleIds,
            List<Link> links) {

        public Finding(Severity severity, String title, long count, String detail, List<String> sampleIds) {
            this(severity, title, count, detail, sampleIds, List.of());
        }

        public Finding withLinks(List<Link> extra) {
            return new Finding(severity, title, count, detail, sampleIds, extra);
        }
    }

    public static CheckResult healthy(Map<String, Double> values) {
        return new CheckResult(List.of(), values);
    }
}
