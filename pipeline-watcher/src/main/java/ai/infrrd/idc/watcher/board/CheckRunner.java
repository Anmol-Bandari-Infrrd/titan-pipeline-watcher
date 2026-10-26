package ai.infrrd.idc.watcher.board;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ai.infrrd.idc.watcher.check.Check;
import ai.infrrd.idc.watcher.env.EnvContext;
import ai.infrrd.idc.watcher.env.EnvRegistry;

import com.mongodb.MongoException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;

import jakarta.annotation.PreDestroy;

/**
 * Runs every (env, check) pair on its own interval in a small thread pool. A slow or unreachable
 * env never delays the others, and the same pair never runs twice at once.
 */
@Component
public class CheckRunner {

    private static final Logger log = LoggerFactory.getLogger(CheckRunner.class);
    /** Checks mostly wait on network I/O; enough threads that one slow env can't starve the rest. */
    private static final int THREADS = 16;

    private final EnvRegistry envs;
    private final List<Check> checks;
    private final StatusBoard board;
    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private final Map<String, Instant> lastStart = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public CheckRunner(EnvRegistry envs, List<Check> checks, StatusBoard board) {
        this.envs = envs;
        this.checks = checks;
        this.board = board;
        var startedAt = Instant.now();
        for (var env : envs.all()) {
            board.register(env.name(), checks.stream().anyMatch(c -> c.appliesTo(env)));
            // Spread first runs over each check's interval instead of firing everything at once on startup.
            for (var check : checks) {
                long jitterMs = ThreadLocalRandom.current().nextLong(Math.max(1, check.interval().toMillis() / 2));
                lastStart.put(env.name() + "/" + check.name(), startedAt.minus(check.interval()).plusMillis(jitterMs));
            }
        }
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 2_000)
    public void tick() {
        var now = Instant.now();
        for (var env : envs.all()) {
            for (var check : checks) {
                var key = env.name() + "/" + check.name();
                var last = lastStart.get(key);
                boolean due = last == null || !last.plus(check.interval()).isAfter(now);
                if (check.appliesTo(env) && due && running.add(key)) {
                    lastStart.put(key, now);
                    pool.submit(() -> runOne(env, check, key));
                }
            }
        }
    }

    private void runOne(EnvContext env, Check check, String key) {
        long started = System.currentTimeMillis();
        try {
            var result = check.run(env, Instant.now());
            board.success(env.name(), check.name(), result, Instant.now());
            log.debug("{} ok in {} ms, {} findings", key, System.currentTimeMillis() - started, result.findings().size());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            board.failure(env.name(), check.name(), shortError(e), Instant.now());
            log.warn("{} failed after {} ms: {}", key, System.currentTimeMillis() - started, e.toString());
        } finally {
            running.remove(key);
        }
    }

    /** Shown on the TV: must be short and must never contain credentials or usernames. */
    static String shortError(Exception e) {
        if (e instanceof MongoSecurityException) {
            return "Mongo login failed";
        }
        if (e instanceof MongoTimeoutException) {
            return "Mongo unreachable (timeout)";
        }
        if (e instanceof MongoException && String.valueOf(e.getMessage()).contains("Connection pool")) {
            return "Mongo connection failed";
        }
        var msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        msg = msg.replaceAll("MongoCredential\\{[^}]*}", "<credential>").replaceAll("://[^@/\\s]+@", "://<credential>@");
        return msg.length() > 160 ? msg.substring(0, 160) + "…" : msg;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
