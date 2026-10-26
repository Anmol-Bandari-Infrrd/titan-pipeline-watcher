package ai.infrrd.idc.watcher.env;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.slf4j.LoggerFactory;

import ai.infrrd.idc.watcher.check.CheckResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs PromQL through an env's Grafana (datasource proxy), so we only need the Grafana URL +
 * read-only login that already works today, not network access to each Prometheus.
 */
public class GrafanaClient {

    public record Sample(Map<String, String> labels, double value) {
    }

    public record LogLine(Map<String, String> labels, String line) {
    }

    /** Grafana's own data-proxy limit is 30s; waiting longer is pointless. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /**
     * Max requests in flight to one env's Grafana. Without it, startup (every check at once + the logs
     * check loading 6h of history) queued requests inside Grafana past the timeout (auprod, 2026-10-21).
     */
    private static final int MAX_IN_FLIGHT = 3;
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT, true);
    /**
     * After a rejected login, stop calling this Grafana for a while: retrying while Grafana has the user
     * locked out just renews the lock (seen on usprod 2026-10-21).
     */
    private static final Duration AUTH_COOL_DOWN = Duration.ofMinutes(5);
    /** Renew the session this long before Grafana says it expires. */
    private static final Duration RENEW_BEFORE = Duration.ofSeconds(60);
    private volatile Instant authBlockedUntil = Instant.EPOCH;
    /** Log queries scan far more data than PromQL; give them longer. */
    private static final Duration LOG_TIMEOUT = Duration.ofSeconds(60);

    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).cookieHandler(cookies).build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String token;
    private final String user;
    private final String password;
    private final Object sessionLock = new Object();
    private volatile boolean loggedIn;
    private volatile String datasourceUid;
    private volatile String lokiUid;

    /**
     * Auth, in order of preference:
     *  - {@code token}: a Grafana service-account token (Bearer). Not a login at all.
     *  - otherwise user/password, but as a <b>session</b>: log in once, reuse the session cookie for every
     *    request, renew it before it expires (Grafana rotates sessions ~every 10 min), and log in again only
     *    if renewing fails. Sending user/password on every request would count each one as a login attempt,
     *    which is what got the shared user locked out.
     */
    public GrafanaClient(ObjectMapper mapper, String baseUrl, String token, String user, String password,
            String datasourceUid) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token == null || token.isBlank() ? null : token.trim();
        this.user = user;
        this.password = password;
        this.datasourceUid = datasourceUid == null || datasourceUid.isBlank() ? null : datasourceUid;
    }
    public List<Sample> instantQuery(String promQl) throws IOException, InterruptedException {
        var path = "/api/datasources/proxy/uid/" + datasourceUid() + "/api/v1/query?query="
                + URLEncoder.encode(promQl, StandardCharsets.UTF_8);
        var result = get(path).path("data").path("result");
        var samples = new ArrayList<Sample>();
        for (var series : result) {
            var labels = new HashMap<String, String>();
            series.path("metric").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
            samples.add(new Sample(labels, series.path("value").path(1).asDouble()));
        }
        return samples;
    }

    /** Log lines from the env's Loki (newest first), at most {@code limit}. */
    public List<LogLine> logQuery(String logQl, Instant start, Instant end, int limit)
            throws IOException, InterruptedException {
        var path = "/api/datasources/proxy/uid/" + lokiUid() + "/loki/api/v1/query_range?query="
                + URLEncoder.encode(logQl, StandardCharsets.UTF_8)
                + "&start=" + nanos(start) + "&end=" + nanos(end) + "&limit=" + limit + "&direction=backward";
        var lines = new ArrayList<LogLine>();
        for (var stream : get(path, LOG_TIMEOUT).path("data").path("result")) {
            var labels = new HashMap<String, String>();
            stream.path("stream").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
            for (var value : stream.path("values")) {
                lines.add(new LogLine(labels, value.path(1).asText()));
            }
        }
        return lines;
    }

    /** Grafana link that opens Explore on this env's Prometheus with the query already run. */
    public CheckResult.Link promLink(String label, String promQl, String from, String to)
            throws IOException, InterruptedException {
        return exploreLink(label, datasourceUid(), "prometheus", promQl, from, to);
    }

    /** Grafana link that opens Explore on this env's Loki with the query already run. */
    public CheckResult.Link lokiLink(String label, String logQl, String from, String to)
            throws IOException, InterruptedException {
        return exploreLink(label, lokiUid(), "loki", logQl, from, to);
    }

    /**
     * Grafana Explore URL (format: /explore?schemaVersion=1&panes={"a":{datasource, queries, range}}).
     * {@code from}/{@code to} are Grafana times: "now-1h", "now", or epoch milliseconds as a string.
     */
    private CheckResult.Link exploreLink(String label, String uid, String type, String expr, String from, String to) {
        try {
            var query = Map.of("refId", "A", "expr", expr, "datasource", Map.of("type", type, "uid", uid));
            var pane = Map.of("datasource", uid, "queries", List.of(query), "range", Map.of("from", from, "to", to));
            var panes = mapper.writeValueAsString(Map.of("a", pane));
            return new CheckResult.Link(label, baseUrl + "/explore?schemaVersion=1&orgId=1&panes="
                    + URLEncoder.encode(panes, StandardCharsets.UTF_8));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nanos(Instant t) {
        return t.getEpochSecond() + String.format("%09d", t.getNano());
    }

    private String lokiUid() throws IOException, InterruptedException {
        if (lokiUid == null) {
            for (var ds : get("/api/datasources")) {
                if ("loki".equals(ds.path("type").asText())) {
                    lokiUid = ds.path("uid").asText();
                    break;
                }
            }
            if (lokiUid == null) {
                throw new IOException("No Loki datasource found in " + baseUrl);
            }
        }
        return lokiUid;
    }

    private String datasourceUid() throws IOException, InterruptedException {
        if (datasourceUid == null) {
            for (var ds : get("/api/datasources")) {
                if ("prometheus".equals(ds.path("type").asText())
                        && (datasourceUid == null || ds.path("isDefault").asBoolean())) {
                    datasourceUid = ds.path("uid").asText();
                }
            }
            if (datasourceUid == null) {
                throw new IOException("No Prometheus datasource found in " + baseUrl);
            }
        }
        return datasourceUid;
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        return get(path, TIMEOUT);
    }

    private JsonNode get(String path, Duration timeout) throws IOException, InterruptedException {
        if (Instant.now().isBefore(authBlockedUntil)) {
            throw new IOException("Grafana login rejected (401); pausing calls until "
                    + authBlockedUntil.atZone(ZoneId.systemDefault()).toLocalTime().withNano(0));
        }
        if (token == null) {
            ensureSession();
        }
        var response = send(apiRequest(path, timeout));
        if (response.statusCode() == 401 && token == null) {
            // Session expired or was revoked (e.g. Grafana restarted): renew or log in once, then retry once.
            renewOrLogin();
            response = send(apiRequest(path, timeout));
        }
        if (response.statusCode() == 401) {
            loggedIn = false;
            authBlockedUntil = Instant.now().plus(AUTH_COOL_DOWN);
            throw new IOException("Grafana login rejected (401); pausing calls for 5 min so a lockout can expire");
        }
        if (response.statusCode() != 200) {
            throw new IOException("Grafana " + path.split("\\?")[0] + " returned HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private HttpRequest apiRequest(String path, Duration timeout) {
        var b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout).header("Accept", "application/json").GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b.build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        inFlight.acquire();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } finally {
            inFlight.release();
        }
    }

    /** Logged in, and not about to expire. */
    private void ensureSession() throws IOException, InterruptedException {
        synchronized (sessionLock) {
            if (!loggedIn) {
                login();
            } else {
                var expiry = sessionExpiry();
                if (expiry != null && Instant.now().isAfter(expiry.minus(RENEW_BEFORE))) {
                    renewOrLogin();
                }
            }
        }
    }

    private void renewOrLogin() throws IOException, InterruptedException {
        synchronized (sessionLock) {
            if (!loggedIn || !rotate()) {
                login();
            }
        }
    }

    /** One real login. A rejection starts the cool-down instead of retrying. */
    private void login() throws IOException, InterruptedException {
        var body = mapper.writeValueAsString(Map.of("user", user == null ? "" : user, "password", password == null ? "" : password));
        var response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/login")).timeout(TIMEOUT)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (response.statusCode() != 200) {
            loggedIn = false;
            if (response.statusCode() == 401) {
                authBlockedUntil = Instant.now().plus(AUTH_COOL_DOWN);
            }
            throw new IOException("Grafana login failed (HTTP " + response.statusCode() + ")");
        }
        loggedIn = true;
        LoggerFactory.getLogger(GrafanaClient.class).info("Grafana session opened: {}", baseUrl);
    }

    /** Renews the session without a login (what the Grafana UI does in the background). */
    private boolean rotate() throws IOException, InterruptedException {
        var response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/user/auth-tokens/rotate")).timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        LoggerFactory.getLogger(GrafanaClient.class).info("Grafana session renew {}: {}",
                response.statusCode() == 200 ? "ok" : "failed (" + response.statusCode() + ")", baseUrl);
        return response.statusCode() == 200;
    }

    /** Grafana publishes the session's expiry (epoch seconds) in a readable cookie. */
    private Instant sessionExpiry() {
        for (HttpCookie c : cookies.getCookieStore().get(URI.create(baseUrl))) {
            if ("grafana_session_expiry".equals(c.getName())) {
                try {
                    return Instant.ofEpochSecond(Long.parseLong(c.getValue()));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
