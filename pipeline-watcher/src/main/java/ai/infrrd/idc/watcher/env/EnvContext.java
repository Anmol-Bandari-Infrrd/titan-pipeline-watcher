package ai.infrrd.idc.watcher.env;

import ai.infrrd.idc.watcher.config.WatcherProperties.EnvConfig;

import com.mongodb.client.MongoDatabase;

/** Everything a check needs to talk to one environment. Either connection may be null if not configured. */
public record EnvContext(EnvConfig config, MongoDatabase db, GrafanaClient grafana) {

    public String name() {
        return config.name();
    }
}
