package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import ai.infrrd.idc.watcher.check.CheckResult.Finding;
import ai.infrrd.idc.watcher.check.CheckResult.Severity;
import ai.infrrd.idc.watcher.constants.ErrorCatalog;
import ai.infrrd.idc.watcher.constants.ErrorCatalog.KnownError;
import ai.infrrd.idc.watcher.constants.ErrorCatalog.Level;
import ai.infrrd.idc.watcher.config.WatcherProperties;
import ai.infrrd.idc.watcher.env.EnvContext;
import ai.infrrd.idc.watcher.env.GrafanaClient.LogLine;

/**
 * ERROR log lines from each env's Loki, last 15 min, app namespaces only.
 *
 *  - Lines matching {@link ErrorCatalog} are counted per entry: CRITICAL/WARNING fire above their
 *    minCount, INFO only appear in the "Known errors" panel, IGNORE never leave Loki.
 *  - Lines matching nothing are grouped by fingerprint (ids/numbers/JSON stripped). A fingerprint not
 *    seen in the last 7 days that shows up {@code newErrorMinCount}+ times is a "New error": the usual
 *    shape of a bug introduced by a deploy.
 *
 * Seen fingerprints live in memory; on the first run per env the last few hours are loaded first, so
 * a restart doesn't make every chronic error look new.
 */
@Component
public class LogErrorCheck implements Check {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration SEED_WINDOW = Duration.ofHours(6);
    private static final Duration SEED_CHUNK = Duration.ofMinutes(30);
    private static final Pattern ERROR_WORD = Pattern.compile("\\bERROR\\b");
    private static final Duration REMEMBER = Duration.ofDays(7);
    private static final int MAX_LINES = 5000;

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern PY_DOC = Pattern.compile("^\\[[^\\]]*\\] \\[ERROR\\] \\[[^\\]]*\\] \\[[^\\]]*\\] \\[[^\\]]*\\] \\[([^\\]]*)\\]:");
    private static final Pattern JAVA_DOC = Pattern.compile("docId:\\s*(" + UUID + "(?:_document-\\d+)?(?:_split-\\d+)*)");
    private static final Pattern SUFFIXED_DOC = Pattern.compile(UUID + "_document-\\d+(?:_split-\\d+)*");
    private static final Pattern NAMED_DOC = Pattern.compile("(?i)(?:document(?:Id)?|docId)\\W{0,3}(" + UUID + "(?:_document-\\d+)?(?:_split-\\d+)*)");
    private static final Pattern UUID_DOC = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(_document-\\d+)?(_split-\\d+)*");
    /** Python: "[ts] [ERROR] [thread] [file] [func:line] [doc]: message" */
    private static final Pattern PY = Pattern.compile("^\\[[^\\]]*\\] \\[ERROR\\] \\[[^\\]]*\\] \\[([^\\]]*)\\] \\[([^\\]:]*)[^\\]]*\\] \\[[^\\]]*\\]:\\s?(.*)$", Pattern.DOTALL);
    /** Java/logback: "date time ERROR pid --- [...] [thread] logger : message" */
    private static final Pattern JAVA = Pattern.compile("^\\S+ \\S+\\s+ERROR .*?\\]\\s+(\\S+)\\s*:\\s?(.*)$", Pattern.DOTALL);

    private final WatcherProperties props;
    /** env -> fingerprint -> last seen. */
    private final Map<String, Map<String, Instant>> seen = new ConcurrentHashMap<>();

    public LogErrorCheck(WatcherProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "logs";
    }

    @Override
    public Duration interval() {
        return Duration.ofMinutes(5);
    }

    @Override
    public boolean appliesTo(EnvContext env) {
        return env.grafana() != null;
    }

    @Override
    public CheckResult run(EnvContext env, Instant now) throws Exception {
        var envSeen = seen.get(env.name());
        if (envSeen == null) {
            envSeen = seed(env, now);
            seen.put(env.name(), envSeen);
        }

        var lines = fetch(env, now.minus(WINDOW), now);
        Map<KnownError, List<LogLine>> known = new LinkedHashMap<>();
        Map<String, List<LogLine>> unknown = new HashMap<>();
        for (var line : lines) {
            var k = match(line);
            if (k != null) {
                known.computeIfAbsent(k, x -> new ArrayList<>()).add(line);
            } else {
                unknown.computeIfAbsent(fingerprint(line), x -> new ArrayList<>()).add(line);
            }
        }

        var findings = new ArrayList<Finding>();
        boolean capped = lines.size() >= MAX_LINES;
        known.forEach((k, hits) -> {
            var severity = switch (k.level()) {
                case CRITICAL -> hits.size() >= k.minCount() ? Severity.CRITICAL : Severity.INFO;
                case WARNING -> hits.size() >= k.minCount() ? Severity.WARNING : Severity.INFO;
                default -> Severity.INFO;
            };
            findings.add(new Finding(severity, k.title(), hits.size(),
                    services(hits) + " · " + k.meaning() + (capped ? " (sampled)" : ""), docIds(hits),
                    GrafanaLinks.of(env, () -> env.grafana().lokiLink("These log lines in Grafana (1h)",
                            selector(hits) + " |= \"ERROR\" |~ " + GrafanaLinks.regex(k.match().pattern()), "now-1h", "now"))));
        });

        int minNew = props.thresholds().newErrorMinCount();
        for (var entry : unknown.entrySet()) {
            var fp = entry.getKey();
            var hits = entry.getValue();
            if (!envSeen.containsKey(fp) && hits.size() >= minNew) {
                var snippet = searchSnippet(message(hits.get(0).line()));
                findings.add(new Finding(Severity.WARNING, "New error · " + services(hits), hits.size(),
                        shorten(message(hits.get(0).line()), 140), docIds(hits),
                        GrafanaLinks.of(env, () -> env.grafana().lokiLink("These log lines in Grafana (1h)",
                                selector(hits) + " |= \"ERROR\"" + (snippet == null ? "" : " |= " + GrafanaLinks.literal(snippet)),
                                "now-1h", "now"))));
            }
            envSeen.put(fp, now);
        }
        envSeen.values().removeIf(t -> t.isBefore(now.minus(REMEMBER)));

        return new CheckResult(findings, Map.of("errorLines15m", (double) lines.size()));
    }

    /**
     * Fingerprints seen in the last {@link #SEED_WINDOW}, loaded in 30-min chunks: one 6h query hits
     * Grafana's 30s proxy timeout (HTTP 502) on busy envs. A failed chunk is skipped, not fatal.
     */
    private Map<String, Instant> seed(EnvContext env, Instant now) {
        Map<String, Instant> envSeen = new ConcurrentHashMap<>();
        for (var to = now.minus(WINDOW); to.isAfter(now.minus(SEED_WINDOW)); to = to.minus(SEED_CHUNK)) {
            try {
                for (var line : fetch(env, to.minus(SEED_CHUNK), to)) {
                    if (match(line) == null) {
                        envSeen.put(fingerprint(line), now);
                    }
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return envSeen;
    }

    private List<LogLine> fetch(EnvContext env, Instant from, Instant to) throws Exception {
        // Plain "ERROR" filter is far cheaper for Loki than a regex; the word-boundary check is done here.
        var query = new StringBuilder("{namespace=~\"")
                .append(String.join("|", props.logNamespaces()))
                .append("\"} |= \"ERROR\"");
        for (var k : ErrorCatalog.ALL) {
            if (k.level() == Level.IGNORE) {
                // IGNORE patterns are Pattern.quote()d literals; unwrap \Q...\E for LogQL.
                var literal = k.match().pattern().replace("\\Q", "").replace("\\E", "");
                query.append(" != \"").append(literal.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return env.grafana().logQuery(query.toString(), from, to, MAX_LINES).stream()
                .filter(l -> ERROR_WORD.matcher(l.line()).find())
                .toList();
    }

    private static KnownError match(LogLine line) {
        var svc = line.labels().get("instance");
        for (var k : ErrorCatalog.ALL) {
            if (k.level() != Level.IGNORE && (k.service() == null || k.service().equals(svc))
                    && k.match().matcher(line.line()).find()) {
                return k;
            }
        }
        return null;
    }

    /** Same error, different ids/numbers/payloads -> same fingerprint. Service is part of it. */
    static String fingerprint(LogLine line) {
        var s = message(line.line());
        s = s.replaceAll("(?s)\\{.*\\}", "{…}");
        s = UUID_DOC.matcher(s).replaceAll("<id>");
        s = s.replaceAll("\\b[0-9a-f]{24}\\b", "<oid>");
        s = s.replaceAll("\\b\\d+(\\.\\d+)?\\b", "<n>");
        s = s.replaceAll("\\s+", " ").trim();
        return line.labels().getOrDefault("instance", "?") + "|" + shorten(s, 200);
    }

    /** The message part of a log line, without timestamp/thread/logger prefix. */
    static String message(String line) {
        var py = PY.matcher(line);
        if (py.matches()) {
            return py.group(3);
        }
        var java = JAVA.matcher(line);
        if (java.matches()) {
            return java.group(2);
        }
        return line;
    }

    private static String services(List<LogLine> hits) {
        return hits.stream().map(l -> l.labels().getOrDefault("instance", "?")).distinct().sorted()
                .limit(3).collect(Collectors.joining(", "));
    }

    /**
     * Document IDs mentioned in the lines. Only IDs that are clearly documents count; a bare UUID can be
     * anything (on 2026-10-26 a model training ID showed up as a "doc ID"). Accepted:
     *  - the doc field of the Python log prefix: "[ts] [ERROR] [thread] [file] [func:line] [<docId>]:"
     *  - Java's "docId:<id>"
     *  - IDs ending in "_document-N" (optionally "_split-N")
     *  - a UUID right after the word "document", "documentId" or "docId"
     */
    List<String> docIds(List<LogLine> hits) {
        var ids = new java.util.LinkedHashSet<String>();
        for (var l : hits) {
            var line = l.line();
            var py = PY_DOC.matcher(line);
            if (py.find() && UUID_DOC.matcher(py.group(1)).matches()) {
                ids.add(py.group(1));
            }
            for (var m = JAVA_DOC.matcher(line); m.find(); ) {
                ids.add(m.group(1));
            }
            for (var m = SUFFIXED_DOC.matcher(line); m.find(); ) {
                ids.add(m.group());
            }
            for (var m = NAMED_DOC.matcher(line); m.find(); ) {
                ids.add(m.group(1));
            }
            if (ids.size() >= props.sampleSize()) {
                break;
            }
        }
        return ids.stream().limit(props.sampleSize()).toList();
    }

    /** Stream selector for the services in these lines. */
    private String selector(List<LogLine> hits) {
        var svcs = hits.stream().map(l -> l.labels().get("instance")).filter(java.util.Objects::nonNull)
                .distinct().sorted().limit(5).toList();
        return "{" + GrafanaLinks.namespaces(props.logNamespaces())
                + (svcs.isEmpty() ? "" : ", instance=~" + GrafanaLinks.literal(String.join("|", svcs))) + "}";
    }

    /** A fixed piece of the message to search for: the text before the first digit, if it's long enough. */
    static String searchSnippet(String message) {
        var head = message.split("[0-9{\\[]", 2)[0].trim();
        if (head.length() > 80) {
            head = head.substring(0, 80);
        }
        return head.length() >= 12 ? head : null;
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
