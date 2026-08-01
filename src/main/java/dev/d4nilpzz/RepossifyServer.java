package dev.d4nilpzz;

import dev.d4nilpzz.auth.AuthService;
import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.config.ConfigService;
import dev.d4nilpzz.config.ServerConfig;
import dev.d4nilpzz.console.CommandConsole;
import dev.d4nilpzz.console.ConsoleBridge;
import dev.d4nilpzz.controllers.*;
import dev.d4nilpzz.http.ErrorResponse;
import dev.d4nilpzz.http.HttpFiles;
import dev.d4nilpzz.repos.*;
import io.javalin.Javalin;
import io.javalin.compression.CompressionStrategy;
import io.javalin.compression.Gzip;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Builds and owns a running Repossify instance.
 * <p>
 * Kept separate from {@link Repossify} so the whole server can be started against a
 * temporary working directory from a test, which is the only way to verify the Maven
 * endpoint end to end.
 */
public class RepossifyServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepossifyServer.class);

    private final Path workingDirectory;
    private final ServerConfig config;

    private final TokenService tokenService;
    private final StatisticsService statisticsService;
    private final AuthService authService;
    private final RepositoryService repositoryService;
    private final MetadataService metadataService;
    private final ProxyService proxyService;
    private final GarbageCollector garbageCollector;
    private final CommandConsole console;
    private final MetricsController metricsController;

    private final Javalin app;
    private ScheduledExecutorService maintenance;

    public RepossifyServer(Path workingDirectory, ServerConfig config, ConfigService configService)
            throws Exception {
        this.workingDirectory = workingDirectory;
        this.config = config;

        String jdbcUrl = "jdbc:sqlite:" + workingDirectory.resolve("repossify.db").toAbsolutePath();
        this.tokenService = new TokenService(jdbcUrl);
        this.statisticsService = new StatisticsService(jdbcUrl);

        this.authService = new AuthService(tokenService);
        this.repositoryService = new RepositoryService(workingDirectory);
        this.metadataService = new MetadataService();
        this.proxyService = new ProxyService(repositoryService);
        this.garbageCollector = new GarbageCollector(repositoryService, metadataService);

        try {
            repositoryService.syncDirectories(Set.of());
        } catch (Exception e) {
            LOGGER.warn("Cannot create repository directories: {}", e.getMessage());
        }

        this.console = new CommandConsole(tokenService, repositoryService, metadataService,
                garbageCollector, statisticsService);
        this.metricsController = new MetricsController(authService);

        this.app = buildApp(configService);
    }

    private Javalin buildApp(ConfigService configService) {
        Javalin javalin = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = config.maxRequestSize;

            if (config.compression) {
                CompressionStrategy compression = new CompressionStrategy(null, new Gzip(6));
                // Artifacts are already compressed archives; gzipping them wastes CPU and
                // invalidates the Content-Length the Maven endpoint declares.
                compression.setExcludedMimeTypes(
                        Stream.concat(compression.getExcludedMimeTypes().stream(),
                                        HttpFiles.INCOMPRESSIBLE_TYPES.stream())
                                .distinct()
                                .toList());
                cfg.http.customCompression(compression);
            } else {
                cfg.http.disableCompression();
            }

            cfg.staticFiles.add("/static");
            cfg.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/content";
                staticFiles.directory = workingDirectory.resolve("content").toString();
                staticFiles.location = Location.EXTERNAL;
            });

            if (config.cors.enabled) {
                cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                    if (config.cors.allowedOrigins.isEmpty()) {
                        rule.anyHost();
                    } else {
                        config.cors.allowedOrigins.forEach(rule::allowHost);
                    }
                }));
            }

            if (config.forwardedIp.enabled) {
                // Behind a reverse proxy every request otherwise appears to come from the
                // proxy, which makes the access log useless.
                cfg.contextResolver.ip = ctx -> {
                    String forwarded = ctx.header(config.forwardedIp.header);
                    if (forwarded == null || forwarded.isBlank()) return ctx.req().getRemoteAddr();
                    int comma = forwarded.indexOf(',');
                    return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
                };
            }

            if (config.ssl.enabled) configureSsl(cfg);
        });

        registerErrorHandlers(javalin);

        new PageController(authService, repositoryService).registerRoutes(javalin);
        new AuthController(authService).registerRoutes(javalin);
        new ConfigController(authService, repositoryService, configService, proxyService, statisticsService)
                .registerRoutes(javalin);
        new TokenController(authService, tokenService).registerRoutes(javalin);
        new BadgeController(repositoryService, metadataService).registerRoutes(javalin);
        new SearchController(authService, repositoryService, metadataService).registerRoutes(javalin);
        new StatisticsController(authService, statisticsService, repositoryService).registerRoutes(javalin);
        new FileApiController(authService, repositoryService, metadataService, garbageCollector)
                .registerRoutes(javalin);
        new MavenController(authService, repositoryService, metadataService, proxyService,
                statisticsService, garbageCollector).registerRoutes(javalin);
        new ClientConsoleController(authService, new ConsoleBridge(console)).registerRoutes(javalin);
        metricsController.registerRoutes(javalin);

        return javalin;
    }

    /**
     * Adds an HTTPS connector alongside the plain one. Configured through Jetty directly
     * rather than pulling in an extra plugin, since Jetty is already on the classpath.
     */
    private void configureSsl(io.javalin.config.JavalinConfig cfg) {
        Path keyStore = workingDirectory.resolve(config.ssl.keyStore).toAbsolutePath().normalize();

        if (Files.notExists(keyStore)) {
            LOGGER.error("SSL is enabled but the keystore {} does not exist; HTTPS is disabled", keyStore);
            return;
        }

        cfg.jetty.addConnector((server, httpConfiguration) -> {
            SslContextFactory.Server sslFactory = new SslContextFactory.Server();
            sslFactory.setKeyStorePath(keyStore.toString());
            sslFactory.setKeyStorePassword(config.ssl.keyStorePassword);

            HttpConfiguration httpsConfiguration = new HttpConfiguration(httpConfiguration);
            httpsConfiguration.addCustomizer(new SecureRequestCustomizer(false));

            ServerConnector connector = new ServerConnector(server,
                    new SslConnectionFactory(sslFactory, "http/1.1"),
                    new HttpConnectionFactory(httpsConfiguration));
            connector.setPort(config.ssl.port);
            if (config.hostname != null && !config.hostname.isBlank()) {
                connector.setHost(config.hostname);
            }
            return connector;
        });

        if (config.ssl.redirectToHttps) cfg.bundledPlugins.enableSslRedirects();
    }

    /** Uniform JSON error bodies instead of Javalin's mix of plain text and JSON. */
    private static void registerErrorHandlers(Javalin app) {
        app.exception(HttpResponseException.class, (exception, ctx) -> {
            ctx.status(exception.getStatus());
            ctx.json(ErrorResponse.of(exception.getStatus(), exception.getMessage()));
        });

        app.exception(IllegalArgumentException.class, (exception, ctx) -> {
            ctx.status(400);
            ctx.json(ErrorResponse.of(400, exception.getMessage()));
        });

        app.exception(Exception.class, (exception, ctx) -> {
            LOGGER.error("Unhandled error on {} {}", ctx.method(), ctx.path(), exception);
            ctx.status(500);
            ctx.json(ErrorResponse.of(500, "Internal server error"));
        });
    }

    /** Starts listening. Pass port 0 to let the OS pick a free port. */
    public RepossifyServer start() {
        app.start(config.hostname, config.port);
        this.maintenance = startMaintenance();
        return this;
    }

    private ScheduledExecutorService startMaintenance() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "repossify-maintenance");
            thread.setDaemon(true);
            return thread;
        });

        int minutes = config.garbageCollectorIntervalMinutes;
        if (minutes <= 0) return scheduler;

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                int deleted = garbageCollector.pruneAll();
                if (deleted > 0) LOGGER.info("Maintenance pruned {} stale snapshot files", deleted);
            } catch (Exception e) {
                LOGGER.warn("Maintenance run failed: {}", e.getMessage());
            }
        }, minutes, minutes, TimeUnit.MINUTES);

        return scheduler;
    }

    public void stop() {
        if (maintenance != null) maintenance.shutdownNow();
        metricsController.stop();
        app.stop();
    }

    public int port() {
        return app.port();
    }

    public Javalin app() {
        return app;
    }

    public TokenService tokenService() {
        return tokenService;
    }

    public RepositoryService repositoryService() {
        return repositoryService;
    }

    public CommandConsole console() {
        return console;
    }

    public StatisticsService statisticsService() {
        return statisticsService;
    }
}
