package dev.d4nilpzz.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Server settings persisted in {@code configuration.json}.
 * <p>
 * Command line flags still win, so an existing {@code --port} invocation keeps working; the
 * file is what makes a deployment reproducible without a wrapper script.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerConfig {

    public String hostname = "0.0.0.0";
    public int port = 8080;

    /** Largest accepted request body, in bytes. Deploys of large shaded jars need headroom. */
    public long maxRequestSize = 150_000_000L;

    /** Response compression for the dashboard and metadata. Artifacts are already compressed. */
    public boolean compression = true;

    public Cors cors = new Cors();
    public Ssl ssl = new Ssl();
    public ForwardedIp forwardedIp = new ForwardedIp();

    /** How often stale snapshot builds are pruned. 0 disables the scheduled run. */
    public int garbageCollectorIntervalMinutes = 60;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cors {
        public boolean enabled = false;
        /** Allowed origins. Empty with {@code enabled} set means any origin. */
        public List<String> allowedOrigins = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ssl {
        public boolean enabled = false;
        public int port = 8443;

        /** Path to a PKCS#12 or JKS keystore, relative to the working directory. */
        public String keyStore = "keystore.p12";
        public String keyStorePassword = "";

        /** Whether plain HTTP keeps listening alongside HTTPS. */
        public boolean keepHttp = true;

        /** Redirect plain HTTP requests to the HTTPS port instead of serving them. */
        public boolean redirectToHttps = false;
    }

    /**
     * Reverse proxy support. Without this every request is attributed to the proxy's address,
     * so logs and rate decisions see one client.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ForwardedIp {
        public boolean enabled = false;
        public String header = "X-Forwarded-For";
    }
}
