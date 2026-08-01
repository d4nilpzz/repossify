package dev.d4nilpzz;

import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.config.ConfigService;
import dev.d4nilpzz.config.ServerConfig;
import dev.d4nilpzz.params.ParamParser;
import dev.d4nilpzz.params.RepossifyHelp;
import dev.d4nilpzz.utils.RepossifyBanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point: parses the command line, prepares the working directory and hands over to
 * {@link RepossifyServer}.
 */
public class Repossify {

    public static final String VERSION = "1.1.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(Repossify.class);
    private static final String[] AUTHORS = {"d4nilpzz"};

    public static Path WORKING_DIR = Paths.get("./").toAbsolutePath().normalize();

    /** Retained for compatibility; the effective limit lives in {@link ServerConfig}. */
    public static Long MAX_REQUEST_SIZE = 150_000_000L;

    public static void main(String[] args) {
        RepossifyArgs parsed = new RepossifyArgs();
        ParamParser.parse(args, parsed);

        if (parsed.version) {
            System.out.println("Repossify " + VERSION);
            return;
        }
        if (parsed.help) {
            RepossifyHelp.print(RepossifyArgs.class);
            return;
        }

        RepossifyBanner.print(VERSION, AUTHORS);

        if (parsed.workingDirectory != null) {
            WORKING_DIR = Paths.get(parsed.workingDirectory).toAbsolutePath().normalize();
        }
        LOGGER.info("Working directory: {}", WORKING_DIR);

        boolean missingLayout = Files.notExists(WORKING_DIR.resolve("repossify.db"))
                || Files.notExists(WORKING_DIR.resolve("page.json"));

        if (parsed.init || missingLayout) {
            if (missingLayout && !parsed.init) LOGGER.info("Missing files detected, running init...");
            RepossifyInit.init(WORKING_DIR);
            if (parsed.init) {
                LOGGER.info("Initialization finished.");
                return;
            }
        }

        try {
            start(parsed);
        } catch (Exception e) {
            LOGGER.error("Repossify failed to start: {}", e.getMessage(), e);
        }
    }

    private static void start(RepossifyArgs args) throws Exception {
        ConfigService configService = new ConfigService(WORKING_DIR);
        ServerConfig config = configService.withOverrides(args);
        MAX_REQUEST_SIZE = config.maxRequestSize;

        RepossifyServer server = new RepossifyServer(WORKING_DIR, config, configService);
        announceBootstrapToken(server.tokenService());
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "repossify-shutdown"));

        Thread consoleThread = new Thread(server.console(), "console");
        consoleThread.setDaemon(true);
        consoleThread.start();

        LOGGER.info("Repossify {} running on http://{}:{}", VERSION, config.hostname, server.port());
        if (config.ssl.enabled) LOGGER.info("HTTPS listening on port {}", config.ssl.port);
    }

    private static void announceBootstrapToken(TokenService tokenService) throws Exception {
        String adminSecret = tokenService.bootstrapAdminIfEmpty();
        if (adminSecret == null) return;

        LOGGER.warn("+--------------------------------------------------------+");
        LOGGER.warn("|              REPOSSIFY FIRST-TIME SETUP                |");
        LOGGER.warn("|                                                        |");
        LOGGER.warn("|  A manager token was created:                          |");
        LOGGER.warn("|    name   : admin                                      |");
        LOGGER.warn("|    secret : {}", adminSecret);
        LOGGER.warn("|                                                        |");
        LOGGER.warn("|  Save it now; it is not stored in recoverable form.    |");
        LOGGER.warn("+--------------------------------------------------------+");
    }
}
