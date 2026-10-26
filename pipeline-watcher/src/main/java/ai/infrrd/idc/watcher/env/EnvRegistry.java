package ai.infrrd.idc.watcher.env;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.infrrd.idc.watcher.config.WatcherProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import jakarta.annotation.PreDestroy;

/**
 * Builds one small, read-only Mongo client and one Grafana client per enabled environment.
 * A missing URI just leaves that side null; the env then shows as "not configured" on the board.
 */
@Component
public class EnvRegistry {

    private static final Logger log = LoggerFactory.getLogger(EnvRegistry.class);

    private final List<EnvContext> envs = new ArrayList<>();
    private final List<MongoClient> clients = new ArrayList<>();

    public EnvRegistry(WatcherProperties props, ObjectMapper mapper) {
        for (var cfg : props.envs()) {
            if (!cfg.enabled()) {
                continue;
            }
            var db = isBlank(cfg.mongoUri()) ? null : connect(cfg.name(), cfg.mongoUri());
            var grafana = isBlank(cfg.grafanaUrl()) ? null
                    : new GrafanaClient(mapper, cfg.grafanaUrl(), cfg.grafanaToken(), cfg.grafanaUser(),
                            cfg.grafanaPassword(), cfg.promDatasourceUid());
            envs.add(new EnvContext(cfg, db, grafana));
            log.info("Env {}: mongo={}, grafana={}", cfg.name(), db != null, grafana != null);
        }
    }

    public List<EnvContext> all() {
        return envs;
    }

    private com.mongodb.client.MongoDatabase connect(String env, String uri) {
        var cs = new ConnectionString(uri);
        var settings = MongoClientSettings.builder()
                .applyConnectionString(cs)
                .applicationName("pipeline-watcher")
                // Never touch the primary.
                .readPreference(ReadPreference.secondaryPreferred())
                .applyToClusterSettings(b -> b.serverSelectionTimeout(15, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS))
                .applyToConnectionPoolSettings(b -> b.maxSize(5))
                .build();
        var client = MongoClients.create(settings);
        clients.add(client);
        var dbName = cs.getDatabase() == null ? "titanDB" : cs.getDatabase();
        log.info("Env {}: Mongo database {}", env, dbName);
        return client.getDatabase(dbName);
    }

    @PreDestroy
    void close() {
        clients.forEach(MongoClient::close);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
