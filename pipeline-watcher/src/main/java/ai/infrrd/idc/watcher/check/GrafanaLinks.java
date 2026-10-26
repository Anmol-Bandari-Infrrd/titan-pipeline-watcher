package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.infrrd.idc.watcher.check.CheckResult.Link;
import ai.infrrd.idc.watcher.env.EnvContext;

/**
 * Builds "open in Grafana" links for findings. A link is a convenience: if it can't be built
 * (no Grafana for the env, datasource lookup failed) the finding simply has no link.
 */
final class GrafanaLinks {

    private static final Logger log = LoggerFactory.getLogger(GrafanaLinks.class);

    interface LinkMaker {
        Link make() throws Exception;
    }

    private GrafanaLinks() {
    }

    /** Collects the links that could be built; skips (and logs) the ones that couldn't. */
    static List<Link> of(EnvContext env, LinkMaker... makers) {
        var links = new ArrayList<Link>();
        if (env.grafana() == null) {
            return links;
        }
        for (var m : makers) {
            try {
                links.add(m.make());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.debug("{}: could not build Grafana link: {}", env.name(), e.toString());
            }
        }
        return links;
    }

    /** LogQL stream selector for the given namespaces. */
    static String namespaces(List<String> ns) {
        return "namespace=~\"" + String.join("|", ns) + "\"";
    }

    /** A LogQL string literal ("...") with quotes and backslashes escaped. */
    static String literal(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A LogQL regex filter value: raw backtick string when possible (no escaping surprises). */
    static String regex(String re) {
        return re.contains("`") ? literal(re) : "`" + re + "`";
    }

    /** Grafana time for an instant: epoch millis as a string. */
    static String at(Instant t) {
        return String.valueOf(t.toEpochMilli());
    }

    /** Range around a moment: 30 min before to 3 h after (capped at now). */
    static String[] around(Instant moment, Instant now) {
        var from = moment.minus(Duration.ofMinutes(30));
        var to = moment.plus(Duration.ofHours(3));
        return new String[] { at(from), to.isAfter(now) ? "now" : at(to) };
    }
}
