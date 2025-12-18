package dev.d4nilpzz;

import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.console.CommandConsole;
import dev.d4nilpzz.controllers.*;
import dev.d4nilpzz.params.ParamParser;
import io.javalin.Javalin;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.*;

public class Repossify {

    public static final String VERSION = "1.0.0";
    private static final Logger logger = Logger.getLogger("Repossify");

    static {
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter());
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
    }

    public static void main(String[] args) {
        RepossifyArgs parsed = new RepossifyArgs();
        ParamParser.parse(args, parsed);

        if (parsed.init) {
            if (!Files.exists(Paths.get("./repossify.properties"))) {
                logger.info("Initializing Repossify...");
                RepossifyInit.init();
                logger.info("Initialization completed.");
            } else {
                logger.warning("Properties file already exists. Initialization skipped.");
            }
            return;
        }

        run(parsed);
    }


    private static void run(RepossifyArgs args) {
        int port = 8080;
        String hostname;

        try {
            hostname = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            hostname = "localhost";
        }

        try {
            RepossifyConfig cfg = new RepossifyConfig(Paths.get("./repossify.properties"));

            port = Integer.parseInt(
                    args.port != null ? args.port : cfg.get("port", String.valueOf(port))
            );

            hostname = args.hostname != null
                    ? args.hostname
                    : cfg.get("hostname", hostname);

        } catch (Exception ignored) {}

        TokenService tokenService;
        try {
            tokenService = new TokenService("jdbc:sqlite:data/repossify.db");
        } catch (Exception e) {
            logger.severe(e.getMessage());
            return;
        }

        Javalin app = Javalin.create(cfg -> cfg.staticFiles.add("/static")).start(port);

        new PageController(app);
        new ConfigController(app);
        new AuthController(tokenService).registerRoutes(app);

        new Thread(new CommandConsole(tokenService), "console").start();

        logger.info("Running on http://" + hostname + ":" + port);
    }
}
