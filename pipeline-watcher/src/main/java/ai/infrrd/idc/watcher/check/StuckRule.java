package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import ai.infrrd.idc.watcher.config.WatcherProperties;

/**
 * Pure decision logic, kept free of Mongo so it is unit-testable.
 *
 * Rules established from live data (usprod/swbcprod, 2026-10-16):
 *  - IN_PROGRESS alone means nothing: split children wait at IN_PROGRESS/"Page Completed" while
 *    their parent is in split review, and parents stay IN_PROGRESS until the loan closes.
 *  - Docs under page_classification models are intermediate records (empty docType by design).
 *  - Split children may have zero audit rows before review, so lastModifiedDate is the fallback.
 * Review and classifier exclusions are applied in the query; this class decides staleness.
 */
public final class StuckRule {

    public record Activity(String moduleName, Instant at) {
    }

    public record Verdict(boolean stuck, String stage, long idleMinutes) {
    }

    private final WatcherProperties props;

    public StuckRule(WatcherProperties props) {
        this.props = props;
    }

    public Verdict evaluate(Instant lastModified, Optional<Activity> lastAudit, Instant now) {
        Instant lastSeen = lastModified;
        String stage = null;
        if (lastAudit.isPresent()) {
            stage = lastAudit.get().moduleName();
            if (lastSeen == null || lastAudit.get().at().isAfter(lastSeen)) {
                lastSeen = lastAudit.get().at();
            }
        }
        if (lastSeen == null) {
            return new Verdict(false, label(stage), 0);
        }
        long idle = Duration.between(lastSeen, now).toMinutes();
        return new Verdict(idle >= props.stuckMinutesFor(stage), label(stage), idle);
    }

    public boolean slaAtRisk(Instant created, Instant due, Instant now) {
        if (created == null || due == null || !due.isAfter(created)) {
            return false;
        }
        long window = Duration.between(created, due).toMillis();
        return Duration.between(created, now).toMillis() >= (long) (window * props.slaRiskFraction());
    }

    private static String label(String stage) {
        return stage == null ? "no_audit" : stage;
    }
}
