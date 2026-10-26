package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bson.conversions.Bson;
import org.springframework.stereotype.Component;

import ai.infrrd.idc.watcher.config.WatcherProperties;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;

/** Shared query pieces for the document-based checks. */
@Component
public class DocQueries {

    static final long MAX_TIME_SECONDS = 30;
    private static final Duration CLASSIFIER_CACHE_TTL = Duration.ofMinutes(30);

    private record CachedIds(List<String> ids, Instant loadedAt) {
    }

    private final WatcherProperties props;
    private final Map<MongoDatabase, CachedIds> classifierCache = new ConcurrentHashMap<>();

    public DocQueries(WatcherProperties props) {
        this.props = props;
    }

    /**
     * Leaf documents genuinely in the machine pipeline: active, IN_PROGRESS, recently touched,
     * not a parent, not waiting for review, not under a page_classification model.
     * Backed by the {status:1, lastModifiedDate:1} index on document.
     */
    public Bson inPipelineLeafDocs(MongoDatabase db, Instant touchedFrom, Instant touchedTo) {
        return Filters.and(
                Filters.eq("status", "IN_PROGRESS"),
                Filters.gte("lastModifiedDate", Date.from(touchedFrom)),
                Filters.lt("lastModifiedDate", Date.from(touchedTo)),
                Filters.eq("isActive", true),
                Filters.ne("isParent", true),
                Filters.nin("subStatus", props.reviewSubStatuses()),
                Filters.nin("userModelId", classifierModelIds(db)));
    }

    private List<String> classifierModelIds(MongoDatabase db) {
        var cached = classifierCache.get(db);
        if (cached != null && cached.loadedAt().plus(CLASSIFIER_CACHE_TTL).isAfter(Instant.now())) {
            return cached.ids();
        }
        var ids = db.getCollection("user_model")
                .find(Filters.in("modelType", props.classifierModelTypes()))
                .projection(Projections.include("_id"))
                .maxTime(MAX_TIME_SECONDS, TimeUnit.SECONDS)
                .map(d -> String.valueOf(d.get("_id")))
                .into(new ArrayList<>());
        classifierCache.put(db, new CachedIds(ids, Instant.now()));
        return ids;
    }

    static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    static String shortId(String id) {
        return id == null ? "unknown" : id.length() > 8 ? id.substring(0, 8) : id;
    }
}
