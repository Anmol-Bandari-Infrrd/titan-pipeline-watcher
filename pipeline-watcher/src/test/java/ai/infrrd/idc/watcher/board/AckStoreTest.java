package ai.infrrd.idc.watcher.board;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.infrrd.idc.watcher.check.CheckResult.Severity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class AckStoreTest {

    @TempDir
    Path dir;

    private AckStore store() {
        return new AckStore(new ObjectMapper().registerModule(new JavaTimeModule()), dir.resolve("acks.json").toString());
    }

    @Test
    void ackHoldsWhileIssueIsUnchanged() {
        var s = store();
        s.add("k", "anmol", "on it", Duration.ofHours(1), Severity.WARNING, 10);
        assertTrue(s.active("k", Severity.WARNING, 15, Instant.now()).isPresent());
    }

    @Test
    void reopensWhenEscalatedToCritical() {
        var s = store();
        s.add("k", "anmol", "on it", Duration.ofHours(1), Severity.WARNING, 10);
        assertFalse(s.active("k", Severity.CRITICAL, 10, Instant.now()).isPresent());
    }

    @Test
    void reopensWhenCountDoubles() {
        var s = store();
        s.add("k", "anmol", "on it", Duration.ofHours(1), Severity.WARNING, 10);
        assertFalse(s.active("k", Severity.WARNING, 20, Instant.now()).isPresent());
    }

    @Test
    void expires() {
        var s = store();
        s.add("k", "anmol", "on it", Duration.ofHours(1), Severity.WARNING, 10);
        assertFalse(s.active("k", Severity.WARNING, 10, Instant.now().plus(Duration.ofHours(2))).isPresent());
    }

    @Test
    void survivesRestart() {
        store().add("k", "anmol", "on it", Duration.ofHours(1), Severity.WARNING, 10);
        assertTrue(store().active("k", Severity.WARNING, 10, Instant.now()).isPresent());
    }
}
