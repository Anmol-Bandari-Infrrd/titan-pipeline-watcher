package ai.infrrd.idc.watcher.board;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ai.infrrd.idc.watcher.check.CheckResult.Severity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Acknowledged issues ("we know, someone is on it"). An ack hides an issue from the TV's firing list
 * but never for good: it always expires, and it is ignored (the issue re-opens) when the issue gets
 * worse — turns CRITICAL after being acked as WARNING, or its count doubles.
 *
 * Saved to a small JSON file so a restart doesn't bring every acked issue back.
 */
@Component
public class AckStore {

    public record Ack(String key, String by, String reason, Instant at, Instant until,
            Severity severityAtAck, long countAtAck) {
    }

    private static final Logger log = LoggerFactory.getLogger(AckStore.class);
    private static final Duration MAX_ACK = Duration.ofDays(7);

    private final ObjectMapper mapper;
    private final Path file;
    private final Map<String, Ack> acks = new ConcurrentHashMap<>();

    public AckStore(ObjectMapper mapper, @Value("${watcher.ack-file:./pipeline-watcher-acks.json}") String file) {
        this.mapper = mapper;
        this.file = Path.of(file);
        load();
    }

    public Ack add(String key, String by, String reason, Duration duration, Severity severity, long count) {
        var d = duration.compareTo(MAX_ACK) > 0 ? MAX_ACK : duration;
        var now = Instant.now();
        var ack = new Ack(key, by, reason, now, now.plus(d), severity, count);
        acks.put(key, ack);
        save();
        return ack;
    }

    public void remove(String key) {
        if (acks.remove(key) != null) {
            save();
        }
    }

    /** The ack still in force for this issue, or empty if none, expired, or the issue got worse. */
    public Optional<Ack> active(String key, Severity severity, long count, Instant now) {
        var ack = acks.get(key);
        if (ack == null) {
            return Optional.empty();
        }
        if (!ack.until().isAfter(now)) {
            remove(key);
            return Optional.empty();
        }
        boolean escalated = severity == Severity.CRITICAL && ack.severityAtAck() != Severity.CRITICAL;
        boolean doubled = ack.countAtAck() > 0 && count >= 2 * ack.countAtAck();
        return escalated || doubled ? Optional.empty() : Optional.of(ack);
    }

    public boolean has(String key) {
        return acks.containsKey(key);
    }

    public List<Ack> all() {
        return List.copyOf(acks.values());
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<Ack> saved = mapper.readValue(file.toFile(), new TypeReference<List<Ack>>() { });
            saved.forEach(a -> acks.put(a.key(), a));
            log.info("Loaded {} acks from {}", saved.size(), file.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not read acks from {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), all());
        } catch (IOException e) {
            log.warn("Could not save acks to {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }
}
