package dev.d4nilpzz;

import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.console.CommandConsole;
import dev.d4nilpzz.controllers.AuthController;
import dev.d4nilpzz.controllers.ConfigController;
import dev.d4nilpzz.controllers.PageController;
import dev.d4nilpzz.params.ParamParser;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Repossify {
    public static final String VERSION = "1.0.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(Repossify.class);

    public static void main(String[] args) {
        RepossifyArgs parsed = new RepossifyArgs();
        ParamParser.parse(args, parsed);

        if (parsed.init) {
            if (!Files.exists(Paths.get("./repossify.properties"))) {
                LOGGER.info("Initializing Repossify...");
                RepossifyInit.init();
                LOGGER.info("Initialization completed.");
            } else {
                LOGGER.warn("Properties file already exists. Initialization skipped.");
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

        } catch (Exception ignored) {
        }

        TokenService tokenService;
        try {
            tokenService = new TokenService("jdbc:sqlite:data/repossify.db");
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return;
        }

        Javalin app = Javalin.create(cfg -> cfg.staticFiles.add("/static")).start(port);

        new PageController(app);
        new ConfigController(app);
        new AuthController(tokenService).registerRoutes(app);

        new Thread(new CommandConsole(tokenService), "console").start();

        LOGGER.info("Running on http://{}:{}", hostname, port);
    }
}
