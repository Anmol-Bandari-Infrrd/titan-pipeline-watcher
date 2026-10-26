package ai.infrrd.idc.watcher.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ai.infrrd.idc.watcher.check.StuckRule.Activity;
import ai.infrrd.idc.watcher.config.WatcherProperties;
import ai.infrrd.idc.watcher.config.WatcherProperties.Thresholds;

class StuckRuleTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    private static final WatcherProperties PROPS = new WatcherProperties(List.of(), 72, List.of(),
            List.of("page_classification"), 30, Map.of("GENIE", 45, "OCROutputConsumer", 20), 0.7, 20,
            List.of("core"), List.of("core"), new Thresholds(5, 5, 3, 0.2, 20, 10, 50, 3, 0.9, 10));

    private final StuckRule rule = new StuckRule(PROPS);

    @Test
    void recentAuditActivityIsNotStuck() {
        var verdict = rule.evaluate(NOW.minus(Duration.ofHours(5)),
                Optional.of(new Activity("KVP", NOW.minus(Duration.ofMinutes(10)))), NOW);
        assertFalse(verdict.stuck());
        assertEquals("KVP", verdict.stage());
    }

    @Test
    void usesPerStageThreshold() {
        var activity = Optional.of(new Activity("GENIE", NOW.minus(Duration.ofMinutes(40))));
        assertFalse(rule.evaluate(null, activity, NOW).stuck(), "GENIE allows 45 min");

        var other = Optional.of(new Activity("KVP", NOW.minus(Duration.ofMinutes(40))));
        assertTrue(rule.evaluate(null, other, NOW).stuck(), "default is 30 min");
    }

    @Test
    void fallsBackToLastModifiedWhenNoAudit() {
        var verdict = rule.evaluate(NOW.minus(Duration.ofMinutes(31)), Optional.empty(), NOW);
        assertTrue(verdict.stuck());
        assertEquals("no_audit", verdict.stage());
        assertEquals(31, verdict.idleMinutes());
    }

    @Test
    void slaRiskAtSeventyPercentOfWindow() {
        var created = NOW.minus(Duration.ofHours(7));
        assertTrue(rule.slaAtRisk(created, created.plus(Duration.ofHours(10)), NOW));
        assertFalse(rule.slaAtRisk(created, created.plus(Duration.ofHours(20)), NOW));
        assertFalse(rule.slaAtRisk(created, null, NOW));
    }

    @Test
    void minStuckMinutesIsSmallestStageThreshold() {
        assertEquals(20, PROPS.minStuckMinutes());
    }
}
