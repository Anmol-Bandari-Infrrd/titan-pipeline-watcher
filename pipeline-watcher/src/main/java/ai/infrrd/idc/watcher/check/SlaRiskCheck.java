package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;

/** In-pipeline docs past slaRiskFraction of their client due-date window (env-specific due-date key). */
@Component
public class SlaRiskCheck implements Check {

    private final WatcherProperties props;
    private final DocQueries queries;
    private final StuckRule rule;

    public SlaRiskCheck(WatcherProperties props, DocQueries queries) {
        this.props = props;
        this.queries = queries;
        this.rule = new StuckRule(props);
    }

    @Override
    public String name() {
        return "sla-risk";
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(5);
    }

    @Override
    public boolean appliesTo(EnvContext env) {
        var key = env.config().slaDueDateParam();
        return env.db() != null && key != null && !key.isBlank();
    }

    @Override
    public CheckResult run(EnvContext env, Instant now) {
        var db = env.db();
        var dueField = "optionalParams." + env.config().slaDueDateParam();
        var docs = db.getCollection("document")
                .find(Filters.and(
                        queries.inPipelineLeafDocs(db, now.minus(Duration.ofHours(props.lookbackHours())), now),
                        Filters.exists(dueField)))
                .projection(Projections.include("createdDate", dueField))
                .maxTime(DocQueries.MAX_TIME_SECONDS, TimeUnit.SECONDS)
                .into(new ArrayList<>());

        var atRisk = new ArrayList<String>();
        for (var doc : docs) {
            if (rule.slaAtRisk(DocQueries.toInstant(doc.getDate("createdDate")),
                    dueDate(doc, env.config().slaDueDateParam()), now)) {
                atRisk.add(doc.getString("_id"));
            }
        }
        List<Finding> findings = atRisk.isEmpty() ? List.of()
                : List.of(new Finding(Severity.WARNING, "Docs at SLA risk", atRisk.size(),
                        "past " + Math.round(props.slaRiskFraction() * 100) + "% of due-date window, still in pipeline",
                        atRisk.stream().limit(props.sampleSize()).toList(),
                        GrafanaLinks.of(env, () -> env.grafana().lokiLink("Logs of the first at-risk doc",
                                "{" + GrafanaLinks.namespaces(props.logNamespaces()) + "} |= " + GrafanaLinks.literal(atRisk.get(0)),
                                "now-24h", "now"))));
        return new CheckResult(findings, Map.of("slaRisk", (double) atRisk.size()));
    }

    private static Instant dueDate(Document doc, String key) {
        var params = doc.get("optionalParams", Document.class);
        var raw = params == null ? null : params.get(key);
        if (raw instanceof Date d) {
            return d.toInstant();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }
}
