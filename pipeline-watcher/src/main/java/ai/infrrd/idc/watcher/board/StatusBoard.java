package ai.infrrd.idc.watcher.board;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.infrrd.idc.watcher.check.CheckResult;
import ai.infrrd.idc.watcher.check.CheckResult.Finding;
import ai.infrrd.idc.watcher.check.CheckResult.Severity;

/**
 * Latest result of every check in every env, plus 24h of trend values (in memory: refilled within one
 * check interval after a restart), when each issue was first/last seen (saved to a file), and the
 * issues resolved in the last 24h.
 */
@Component
public class StatusBoard {

    public enum EnvState { OK, WARNING, CRITICAL, UNREACHABLE, STARTING, NOT_CONFIGURED }

    public record CheckState(String check, Instant lastRun, Instant lastSuccess, String error, CheckResult result) {
    }

    /**
     * One issue on the board. {@code key} identifies it across runs (env|check|title) and is what acks use.
     * {@code ack} is set only while an ack is in force; {@code reopened} means it was acked but got worse.
     */
    public record EnvFinding(String env, String check, Finding finding, String key, Instant firstSeen,
            Instant lastSeen, AckStore.Ack ack, boolean reopened) {
    }

    public record Resolved(String env, String check, String title, Severity severity, Instant firstSeen,
            Instant resolvedAt) {
    }

    /** Public so it can be saved/loaded as JSON; not part of the API. */
    public record Seen(Instant first, Instant last, Severity severity, String title, String check) {
    }

    public record Point(long t, double v) {
    }

    public record CheckView(String check, boolean ok, Instant lastSuccess, String error) {
    }

    public record EnvView(String env, EnvState state, List<EnvFinding> findings, List<CheckView> checks,
            Map<String, List<Point>> trends) {
    }

    private static final Duration HISTORY = Duration.ofHours(24);
    private static final int MAX_RESOLVED = 200;

    private final Map<String, Map<String, CheckState>> states = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Deque<Point>>> history = new ConcurrentHashMap<>();
    private final Map<String, Boolean> configured = new ConcurrentHashMap<>();
    /** key -> when the issue was first / last seen while active. */
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();
    private final Deque<Resolved> resolved = new ArrayDeque<>();
    private final AckStore acks;
    private final ObjectMapper mapper;
    private final Path seenFile;

    /**
     * First-seen times are saved to a file (every minute) so a restart doesn't make a 5-day-old
     * issue look brand new on the board.
     */
    public StatusBoard(AckStore acks, ObjectMapper mapper,
            @Value("${watcher.state-file:./pipeline-watcher-state.json}") String seenFile) {
        this.acks = acks;
        this.mapper = mapper;
        this.seenFile = Path.of(seenFile);
        try {
            if (Files.exists(this.seenFile)) {
                Map<String, Seen> saved = mapper.readValue(this.seenFile.toFile(), new TypeReference<Map<String, Seen>>() { });
                var cutoff = Instant.now().minus(HISTORY);
                saved.forEach((k, v) -> {
                    if (v.last().isAfter(cutoff)) {
                        seen.put(k, v);
                    }
                });
            }
        } catch (IOException e) {
            LoggerFactory.getLogger(StatusBoard.class).warn("Could not read {}: {}", seenFile, e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void saveSeen() {
        try {
            mapper.writeValue(seenFile.toFile(), Map.copyOf(seen));
        } catch (IOException e) {
            LoggerFactory.getLogger(StatusBoard.class).warn("Could not save {}: {}", seenFile, e.getMessage());
        }
    }

    public static String key(String env, String check, String title) {
        return env + "|" + check + "|" + title;
    }

    /** Called once per env at startup so unconfigured envs still get a (grey) tile. */
    public void register(String env, boolean hasAnyCheck) {
        configured.put(env, hasAnyCheck);
        states.computeIfAbsent(env, k -> new ConcurrentHashMap<>());
    }

    public void success(String env, String check, CheckResult result, Instant now) {
        states.get(env).put(check, new CheckState(check, now, now, null, result));
        var active = new java.util.HashSet<String>();
        for (var f : result.findings()) {
            // INFO (recurring known errors) is tracked too, so the page can tell which appeared since start.
            var k = key(env, check, f.title());
            active.add(k);
            seen.merge(k, new Seen(now, now, f.severity(), f.title(), check),
                    (old, neu) -> new Seen(old.first(), now, neu.severity(), neu.title(), check));
        }
        resolveMissing(env, check, active, now);
        resolveMissing(env, FAILING, java.util.Set.of(), now, check);
        var envHistory = history.computeIfAbsent(env, k -> new ConcurrentHashMap<>());
        result.values().forEach((name, value) -> {
            var points = envHistory.computeIfAbsent(name, k -> new ArrayDeque<>());
            synchronized (points) {
                points.addLast(new Point(now.toEpochMilli(), value));
                long cutoff = now.minus(HISTORY).toEpochMilli();
                while (!points.isEmpty() && points.peekFirst().t() < cutoff) {
                    points.removeFirst();
                }
            }
        });
    }

    public void failure(String env, String check, String error, Instant now) {
        seen.merge(key(env, FAILING, check), new Seen(now, now, Severity.WARNING, "Check failing · " + check, FAILING),
                (old, neu) -> new Seen(old.first(), now, old.severity(), old.title(), FAILING));
        var previous = states.get(env).get(check);
        states.get(env).put(check, new CheckState(check, now, previous == null ? null : previous.lastSuccess(), error,
                previous == null ? null : previous.result()));
    }

    private static final String FAILING = "check-failing";

    /** Issues of this env/check that were active before but aren't in the latest result are resolved. */
    private void resolveMissing(String env, String check, java.util.Set<String> stillActive, Instant now) {
        resolveMissing(env, check, stillActive, now, null);
    }

    private void resolveMissing(String env, String check, java.util.Set<String> stillActive, Instant now, String only) {
        var prefix = env + "|" + check + "|";
        for (var k : List.copyOf(seen.keySet())) {
            boolean match = only == null ? k.startsWith(prefix) && !stillActive.contains(k) : k.equals(prefix + only);
            if (match) {
                var s = seen.remove(k);
                if (s != null && s.severity() != Severity.INFO) { // recurring errors coming and going isn't news
                    synchronized (resolved) {
                        resolved.addFirst(new Resolved(env, s.check(), s.title(), s.severity(), s.first(), now));
                        while (resolved.size() > MAX_RESOLVED
                                || resolved.peekLast().resolvedAt().isBefore(now.minus(HISTORY))) {
                            resolved.removeLast();
                        }
                    }
                }
            }
        }
    }

    public List<Resolved> resolved() {
        synchronized (resolved) {
            return List.copyOf(resolved);
        }
    }

    /** Severity and count of an active issue, for recording an ack against it. */
    public java.util.Optional<EnvFinding> find(String key) {
        return snapshot().stream().flatMap(v -> v.findings().stream()).filter(f -> f.key().equals(key)).findFirst();
    }

    public List<EnvView> snapshot() {
        var views = new ArrayList<EnvView>();
        for (var env : configured.keySet().stream().sorted().toList()) {
            views.add(view(env));
        }
        return views;
    }

    private EnvView view(String env) {
        var checks = states.getOrDefault(env, Map.of());
        var findings = new ArrayList<EnvFinding>();
        var checkViews = new ArrayList<CheckView>();
        var failingChecks = new ArrayList<CheckState>();
        for (var s : checks.values()) {
            boolean ok = s.error() == null;
            checkViews.add(new CheckView(s.check(), ok, s.lastSuccess(), s.error()));
            if (ok) {
                s.result().findings().forEach(f -> findings.add(issue(env, s.check(), f)));
            } else {
                failingChecks.add(s);
            }
        }
        int failing = failingChecks.size();
        if (failing > 0) {
            // A broken check is itself a problem (we're blind on that signal). One row per env, not per check.
            var names = failingChecks.stream().map(CheckState::check).sorted().toList();
            var title = failing == 1 ? "Check failing · " + names.get(0) : failing + " checks failing";
            var finding = new Finding(Severity.WARNING, title, 0,
                    failingChecks.get(0).error() + (failing > 1 ? " (" + String.join(", ", names) + ")" : ""), List.of());
            var since = names.stream().map(n -> seen.get(key(env, FAILING, n))).filter(java.util.Objects::nonNull)
                    .map(Seen::first).min(Comparator.naturalOrder()).orElse(null);
            var k = key(env, FAILING, String.join(",", names));
            var ack = acks.active(k, finding.severity(), 0, Instant.now()).orElse(null);
            findings.add(new EnvFinding(env, FAILING, finding, k, since, Instant.now(), ack, ack == null && acks.has(k)));
        }
        findings.sort(Comparator.comparing((EnvFinding f) -> f.finding().severity()));
        checkViews.sort(Comparator.comparing(CheckView::check));

        EnvState state;
        if (!configured.getOrDefault(env, false)) {
            state = EnvState.NOT_CONFIGURED;
        } else if (checks.isEmpty()) {
            state = EnvState.STARTING;
        } else if (failing == checks.size()) {
            state = EnvState.UNREACHABLE;
        } else if (findings.stream().anyMatch(f -> f.ack() == null && f.finding().severity() == Severity.CRITICAL)) {
            state = EnvState.CRITICAL;
        } else if (findings.stream().anyMatch(f -> f.ack() == null && f.finding().severity() == Severity.WARNING)) {
            state = EnvState.WARNING;
        } else {
            state = EnvState.OK;
        }
        return new EnvView(env, state, findings, checkViews, trends(env));
    }

    private EnvFinding issue(String env, String check, Finding f) {
        var k = key(env, check, f.title());
        var s = seen.get(k);
        var ack = f.severity() == Severity.INFO ? null : acks.active(k, f.severity(), f.count(), Instant.now()).orElse(null);
        return new EnvFinding(env, check, f, k, s == null ? null : s.first(), s == null ? null : s.last(), ack,
                ack == null && acks.has(k));
    }

    private Map<String, List<Point>> trends(String env) {
        var result = new LinkedHashMap<String, List<Point>>();
        history.getOrDefault(env, Map.of()).forEach((name, points) -> {
            synchronized (points) {
                result.put(name, List.copyOf(points));
            }
        });
        return result;
    }
}
