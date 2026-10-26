package ai.infrrd.idc.watcher.check;

import java.time.Duration;
import java.time.Instant;

import ai.infrrd.idc.watcher.env.EnvContext;

/** One health check, run independently per environment on its own interval. */
public interface Check {

    String name();

    Duration interval();

    /** False when the env lacks what this check needs (e.g. no Grafana configured). */
    boolean appliesTo(EnvContext env);

    CheckResult run(EnvContext env, Instant now) throws Exception;
}
