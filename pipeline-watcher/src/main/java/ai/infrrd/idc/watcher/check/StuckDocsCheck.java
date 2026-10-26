package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.springframework.stereotype.Component;

import ai.infrrd.idc.watcher.check.CheckResult.Finding;
import ai.infrrd.idc.watcher.check.CheckResult.Severity;
import ai.infrrd.idc.watcher.config.WatcherProperties;
import ai.infrrd.idc.watcher.env.EnvContext;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

/**
 * Leaf documents waiting on the machine pipeline with no activity past their stage limit.
 *
 * Only docs idle at least the smallest stage threshold are fetched, oldest first, so the
 * candidate set stays small. Each candidate's last audit row tells us its stage and whether it
 * actually moved more recently than lastModifiedDate.
 */
@Component
public class StuckDocsCheck implements Check {

    /** Safety caps per env per run. Hitting them is reported, never silently ignored. */
    private static final int MAX_CANDIDATES = 2000;
    private static final int MAX_AUDIT_LOOKUPS = 500;

    private final WatcherProperties props;
    private final DocQueries queries;
    private final StuckRule rule;

    public StuckDocsCheck(WatcherProperties props, DocQueries queries) {
        this.props = props;
        this.queries = queries;
        this.rule = new StuckRule(props);
    }

    private record Stuck(String id, String stage, long idleMinutes) {
    }

    @Override
    public String name() {
        return "stuck-docs";
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(5);
    }

    @Override
    public boolean appliesTo(EnvContext env) {
        return env.db() != null;
    }

    @Override
    public CheckResult run(EnvContext env, Instant now) {
        var db = env.db();
        var from = now.minus(Duration.ofHours(props.lookbackHours()));
        var to = now.minus(Duration.ofMinutes(props.minStuckMinutes()));

        var candidates = db.getCollection("document")
                .find(queries.inPipelineLeafDocs(db, from, to))
                .projection(Projections.include("parentDocumentId", "lastModifiedDate"))
                .sort(Sorts.ascending("lastModifiedDate"))
                .limit(MAX_CANDIDATES)
                .maxTime(DocQueries.MAX_TIME_SECONDS, TimeUnit.SECONDS)
                .into(new ArrayList<>());

        var stuck = new ArrayList<Stuck>();
        var parentOf = new HashMap<String, String>();
        int lookups = 0;
        for (var doc : candidates) {
            if (lookups >= MAX_AUDIT_LOOKUPS) {
                break; // Unresolved candidates are not counted: better a partial count than a false one.
            }
            lookups++;
            var id = doc.getString("_id");
            var verdict = rule.evaluate(DocQueries.toInstant(doc.getDate("lastModifiedDate")), latestAudit(db, id), now);
            if (verdict.stuck()) {
                stuck.add(new Stuck(id, verdict.stage(), verdict.idleMinutes()));
                var parent = doc.getString("parentDocumentId");
                if (parent != null) {
                    parentOf.put(id, parent);
                }
            }
        }
        var inReview = parentsInReview(db, parentOf.values());
        stuck.removeIf(s -> inReview.contains(parentOf.get(s.id())));

        boolean partial = candidates.size() >= MAX_CANDIDATES || lookups < candidates.size();
        return new CheckResult(findings(env, stuck, partial, now), Map.of("stuck", (double) stuck.size()));
    }

    private List<Finding> findings(EnvContext env, List<Stuck> stuck, boolean partial, Instant now) {
        Map<String, List<Stuck>> byStage = new TreeMap<>();
        stuck.forEach(s -> byStage.computeIfAbsent(s.stage(), k -> new ArrayList<>()).add(s));
        var findings = new ArrayList<Finding>();
        byStage.forEach((stage, docs) -> {
            if (docs.size() > props.thresholds().stuckMinDocs()) {
                docs.sort(Comparator.comparingLong(Stuck::idleMinutes).reversed());
                var oldest = docs.get(0);
                var range = GrafanaLinks.around(now.minus(Duration.ofMinutes(oldest.idleMinutes())), now);
                findings.add(new Finding(Severity.CRITICAL, "Docs stuck at " + stage, docs.size(),
                        "oldest idle " + oldest.idleMinutes() + " min" + (partial ? " (partial scan)" : ""),
                        docs.stream().limit(props.sampleSize()).map(Stuck::id).toList(),
                        GrafanaLinks.of(env, () -> env.grafana().lokiLink("Logs of the oldest stuck doc",
                                "{" + GrafanaLinks.namespaces(props.logNamespaces()) + "} |= " + GrafanaLinks.literal(oldest.id()),
                                range[0], range[1]))));
            }
        });
        return findings;
    }

    /** audit has a documentId index; sorting one doc's rows in memory is cheap. */
    private Optional<StuckRule.Activity> latestAudit(MongoDatabase db, String documentId) {
        var row = db.getCollection("audit")
                .find(Filters.and(Filters.eq("documentId", documentId), Filters.exists("moduleName")))
                .projection(Projections.include("moduleName", "createdDate"))
                .sort(Sorts.descending("createdDate"))
                .limit(1)
                .maxTime(DocQueries.MAX_TIME_SECONDS, TimeUnit.SECONDS)
                .first();
        if (row == null || row.getDate("createdDate") == null) {
            return Optional.empty();
        }
        return Optional.of(new StuckRule.Activity(row.getString("moduleName"), row.getDate("createdDate").toInstant()));
    }

    /** A child of a parent in a review state is waiting for a human, not stuck. */
    private HashSet<String> parentsInReview(MongoDatabase db, java.util.Collection<String> parentIds) {
        var result = new HashSet<String>();
        if (parentIds.isEmpty()) {
            return result;
        }
        db.getCollection("document")
                .find(Filters.and(Filters.in("_id", new HashSet<>(parentIds)),
                        Filters.in("subStatus", props.reviewSubStatuses())))
                .projection(Projections.include("_id"))
                .maxTime(DocQueries.MAX_TIME_SECONDS, TimeUnit.SECONDS)
                .forEach((Document d) -> result.add(d.getString("_id")));
        return result;
    }
}
