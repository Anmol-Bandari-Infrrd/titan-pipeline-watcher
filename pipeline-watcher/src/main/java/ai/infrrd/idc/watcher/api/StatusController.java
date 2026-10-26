package ai.infrrd.idc.watcher.api;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ai.infrrd.idc.watcher.board.AckStore;
import ai.infrrd.idc.watcher.board.StatusBoard;
import ai.infrrd.idc.watcher.board.StatusBoard.EnvView;

/** Feeds the TV and console pages; also usable by humans/Claude to pull sample document IDs. */
@RestController
public class StatusController {

    /** {@code startedAt} lets the page offer "only issues that appeared since the service started". */
    public record Status(Instant generatedAt, Instant startedAt, List<EnvView> envs) {
    }

    private static final Instant STARTED_AT = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());

    public record AckRequest(String key, double hours, String reason, String by) {
    }

    private final StatusBoard board;
    private final AckStore acks;

    public StatusController(StatusBoard board, AckStore acks) {
        this.board = board;
        this.acks = acks;
    }

    @GetMapping("/api/status")
    public Status status() {
        return new Status(Instant.now(), STARTED_AT, board.snapshot());
    }

    @GetMapping("/api/envs/{env}")
    public ResponseEntity<EnvView> env(@PathVariable String env) {
        return board.snapshot().stream()
                .filter(v -> v.env().equals(env))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/resolved")
    public List<StatusBoard.Resolved> resolved() {
        return board.resolved();
    }

    /** Acknowledge an active issue for some hours (max 7 days). Needs a name and a reason. */
    @PostMapping("/api/acks")
    public ResponseEntity<?> ack(@RequestBody AckRequest req) {
        if (req.key() == null || blank(req.by()) || blank(req.reason()) || req.hours() <= 0) {
            return ResponseEntity.badRequest().body("key, by, reason and hours > 0 are required");
        }
        return board.find(req.key())
                .<ResponseEntity<?>>map(f -> ResponseEntity.ok(acks.add(req.key(), req.by().trim(), req.reason().trim(),
                        Duration.ofMinutes(Math.round(req.hours() * 60)), f.finding().severity(), f.finding().count())))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/acks")
    public ResponseEntity<Void> unack(@RequestParam String key) {
        acks.remove(key);
        return ResponseEntity.noContent().build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
