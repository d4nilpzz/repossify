package dev.d4nilpzz;

import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.console.CommandConsole;
import dev.d4nilpzz.controllers.*;
import dev.d4nilpzz.params.ParamParser;
import dev.d4nilpzz.utils.LogFile;
import dev.d4nilpzz.utils.RepossifyBanner;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Repossify {
    public static final String VERSION = "1.0.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(Repossify.class);
    private static final String[] AUTHORS = {"d4nilpzz"};

    public static Path WORKING_DIR = Paths.get("./").toAbsolutePath().normalize();
    public static Long MAX_REQUEST_SIZE = 150_000_000L;

    public static void main(String[] args) {
        RepossifyArgs parsed = new RepossifyArgs();
        ParamParser.parse(args, parsed);

        if (parsed.version) {
            LOGGER.info("Repossify {}", VERSION);
            return;
        }

        RepossifyBanner.print(VERSION, AUTHORS);

        if (parsed.maxRequestSize != null) {
            MAX_REQUEST_SIZE = Long.parseLong(parsed.maxRequestSize);
            LOGGER.info("MaxRequestSize has change to: {}", MAX_REQUEST_SIZE);
        }

        if (parsed.workingDirectory != null) {
            WORKING_DIR = Paths.get(parsed.workingDirectory).toAbsolutePath().normalize();
        }
        LOGGER.info("Working directory: {}", WORKING_DIR);

        Path db = WORKING_DIR.resolve("repossify.db");
        Path page = WORKING_DIR.resolve("page.json");

        if (Files.notExists(db) || Files.notExists(page)) {
            LOGGER.info("Missing files detected, running init...");
            RepossifyInit.init(WORKING_DIR);
        }


        run(parsed);
    }

    private static void run(RepossifyArgs args)
    {
        int port = 8080;
        String hostname;

        if (args.workingDirectory != null) {
            WORKING_DIR = Paths.get(args.workingDirectory).toAbsolutePath().normalize();
        }
        LOGGER.info("Working directory: {}", WORKING_DIR);

        if (args.port != null) {
            port = Integer.parseInt(args.port);
        }

        try {
            hostname = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            hostname = "localhost";
        }

        if (args.hostname != null) {
            hostname = args.hostname;
        }

        TokenService tokenService;
        try {
            Path dbPath = WORKING_DIR.resolve("repossify.db");
            tokenService = new TokenService("jdbc:sqlite:" + dbPath.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return;
        }

        Javalin app = Javalin.create(cfg ->{
            cfg.staticFiles.add("/static");
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = MAX_REQUEST_SIZE;
        }).start(port);

        LogFile.info(Repossify.class, "Repossify started on port: "+port);

        new BadgeController(app);
        new PageController(tokenService).registerRoutes(app);
        new ConfigController(tokenService).registerRoutes(app);
        new AuthController(tokenService).registerRoutes(app);
        new FileController(tokenService).registerRoutes(app);

        new Thread(new CommandConsole(tokenService), "console").start();

        LOGGER.info("Running on http://{}:{}", hostname, port);
    }
}
