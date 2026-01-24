package dev.d4nilpzz.console;

import dev.d4nilpzz.Repossify;
import dev.d4nilpzz.auth.AccessToken;
import dev.d4nilpzz.auth.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.management.OperatingSystemMXBean;

import java.io.File;
import java.lang.management.ManagementFactory;

import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.util.*;

/**
 * CommandConsole provides an interactive command-line interface for managing
 * Repossify tokens and server control. Supports token creation, deletion,
 * modification, renaming, secret regeneration, and server commands.
 */
public class CommandConsole implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandConsole.class);
    private final TokenService tokenService;
    private volatile boolean running = true;

    private static final int BAR_LENGTH = 50;

    /**
     * Constructs a CommandConsole instance with the given TokenService.
     *
     * @param tokenService service handling token operations in the database
     */
    public CommandConsole(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Starts the interactive console, reading commands from the standard input.
     * Runs until the "stop" command is issued.
     */
    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        LOGGER.info("Command console started. Type 'help' or '?' for commands.");

        while (running) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();
            handleCommand(input);
        }

        scanner.close();
    }

    /**
     * Handles a single command input, parsing it and invoking the appropriate action.
     *
     * @param input raw command input from the user
     */
    private void handleCommand(String input) {
        if (input.isEmpty()) return;

        String[] parts = input.split("\\s+");
        String command = parts[0].toLowerCase();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        switch (command) {
            case "?":
            case "help":
                LOGGER.info("""
                        Available commands:
                        ➜ help or ?
                        ➜ stop
                        ➜ docs
                        ➜ version
                        ➜ generate_token <name> [<permissions>] [--secret=<secret>] [--silent]
                        ➜ delete_token <name>
                        ➜ delete_all_tokens
                        ➜ token_modify <name> <permissions>
                        ➜ token_rename <oldName> <newName>
                        ➜ token_regenerate <name>
                        ➜ token_add_route <tokenName> <path> <r/w>
                        ➜ token_remove_route <tokenName> <path>
                        ➜ performance
                        """);
                break;
            case "stop":
                LOGGER.info("Stopping Repossify...");
                running = false;
                System.exit(0);
                break;

            case "docs":
                LOGGER.info("https://github.com/d4nilpzz/repossify/");
                break;

            case "version":
                LOGGER.info(Repossify.VERSION);
                break;

            case "generate_token":
                generateToken(args);
                break;

            case "delete_all_tokens":
                try {
                    tokenService.deleteAllTokens();
                    LOGGER.info("All tokens have been deleted from the database!");
                } catch (Exception e) {
                    LOGGER.error("Error deleting all tokens: {}", e.getMessage());
                }

                break;

            case "delete_token":
                if (args.length < 1) {
                    LOGGER.warn("Usage: delete_token <name>");
                    break;
                }

                try {
                    String tokenName = args[0];
                    tokenService.deleteTokenByName(tokenName);
                    LOGGER.info("Token '{}' has been deleted successfully.", tokenName);
                } catch (Exception e) {
                    LOGGER.error("Error deleting token: {}", e.getMessage());
                }

                break;

            case "token_modify":
                if (args.length < 2) {
                    LOGGER.warn("Usage: token_modify <name> <permissions>");
                    break;
                }
                try {
                    String name = args[0];
                    List<String> perms = Arrays.asList(args[1].split(","));
                    tokenService.updateTokenPermissions(name, perms);
                    LOGGER.info("Token '{}' permissions updated to: {}", name, perms);
                } catch (Exception e) {
                    LOGGER.error("Error modifying token: {}", e.getMessage());
                }
                break;

            case "token_rename":
                if (args.length < 2) {
                    LOGGER.warn("Usage: token_rename <oldName> <newName>");
                    break;
                }

                try {
                    String oldName = args[0];
                    String newName = args[1];
                    tokenService.renameToken(oldName, newName);
                    LOGGER.info("Token renamed from '{}' to '{}'", oldName, newName);
                } catch (Exception e) {
                    LOGGER.error("Error renaming token: {}", e.getMessage());
                }
                break;

            case "token_regenerate":
                if (args.length < 1) {
                    LOGGER.warn("Usage: token_regenerate <name>");
                    break;
                }
                try {
                    String name = args[0];
                    String newSecret = tokenService.regenerateTokenSecret(name);
                    LOGGER.info("Token '{}' secret regenerated. New secret: {}", name, newSecret);
                } catch (Exception e) {
                    LOGGER.error("Error regenerating token: {}", e.getMessage());
                }
                break;

            case "token_add_route":
                if (args.length < 3) {
                    LOGGER.warn("Usage: token_add_route <tokenName> <path> <r/w>");
                    break;
                }
                try {
                    String tokenName = args[0];
                    String path = args[1];
                    String routePerm = args[2].toLowerCase();

                    if (!routePerm.equals("r") && !routePerm.equals("w")) {
                        LOGGER.warn("Route permission must be 'r' or 'w'");
                        break;
                    }

                    tokenService.addRouteToToken(tokenName, path, routePerm);
                    LOGGER.info("Route '{}' added to token '{}' with permission '{}'", path, tokenName, routePerm);
                } catch (Exception e) {
                    LOGGER.error("Error adding route: {}", e.getMessage());
                }
                break;

            case "token_remove_route":
                if (args.length < 2) {
                    LOGGER.warn("Usage: token_remove_route <tokenName> <path>");
                    break;
                }
                try {
                    String tokenName = args[0];
                    String path = args[1];
                    tokenService.removeRouteFromToken(tokenName, path);
                    LOGGER.info("Route '{}' removed from token '{}'", path, tokenName);
                } catch (Exception e) {
                    LOGGER.error("Error removing route: {}", e.getMessage());
                }
                break;

            case "performance":
                performance();
                break;

            default:
                LOGGER.warn("Unknown command. Type 'help' to see available commands.");
        }
    }

    /**
     * Processes a single console command, parses its arguments,
     * and dispatches execution to the corresponding handler.
     */
    private static void performance() {
        Runtime rt = Runtime.getRuntime();
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        // CPU: medir % usado por el proceso respetando cores limitados
        int cpuCores = Math.min(rt.availableProcessors(), 2); // ActiveProcessorCount=2
        double cpuUsage = 0.0;
        try {
            long startTime = System.nanoTime();
            long startCpu = os.getProcessCpuTime();
            Thread.sleep(100); // breve espera
            long endTime = System.nanoTime();
            long endCpu = os.getProcessCpuTime();
            cpuUsage = (endCpu - startCpu) / (double)(endTime - startTime) / 1_000_000_000.0 / cpuCores;
            cpuUsage = Math.min(cpuUsage, 1.0); // máximo 100%
        } catch (Exception ignored) {}
        int cpuPercent = (int) Math.round(cpuUsage * 100);
        String cpuBar = buildBar(cpuPercent, BAR_LENGTH);

        // Memoria de la JVM en GB
        double usedMem = (rt.totalMemory() - rt.freeMemory()) / 1024.0 / 1024.0 / 1024.0;
        double maxMem = rt.maxMemory() / 1024.0 / 1024.0 / 1024.0;
        int memPercent = maxMem == 0 ? 0 : (int) ((usedMem * 100) / maxMem);
        String memBar = buildBar(memPercent, BAR_LENGTH);

        String currentDir = System.getProperty("user.dir");
        File jarDrive = new File(currentDir).getAbsoluteFile().toPath().getRoot().toFile();
        long totalStore = jarDrive.getTotalSpace() / 1024 / 1024 / 1024; // GB
        long usedStore = (totalStore - jarDrive.getUsableSpace() / 1024 / 1024 / 1024); // GB
        int storePercent = totalStore == 0 ? 0 : (int) ((usedStore * 100) / totalStore);
        String storeBar = buildBar(storePercent, BAR_LENGTH);

        System.out.printf(
                "CPU     [ %s ] %d%% (%d cores)%n" +
                        "Memory  [ %s ] %.2f/%.2f GB%n" +
                        "Storage [ %s ] %d GB / %d GB%n",
                cpuBar, cpuPercent, cpuCores,
                memBar, usedMem, maxMem,
                storeBar, usedStore, totalStore
        );
    }

    private static String buildBar(int percent, int length) {
        int filled = (percent * length) / 100;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < filled; i++) bar.append("|");
        for (int i = filled; i < length; i++) bar.append(" ");
        return bar.toString();
    }

    /**
     * Handles the 'generate_token' command. Generates a new token with the specified
     * name, optional permissions, and optional secret. If no secret is provided,
     * a random secret is generated.
     *
     * @param args command arguments: <name> [<permissions>] [--secret=<secret>] [--silent]
     */
    private void generateToken(String[] args) {
        if (args.length < 1) {
            LOGGER.warn("Usage: generate_token <name> [<permissions>] [--secret=<secret>] [--silent]");
            return;
        }

        String name = args[0];
        String permArg = "";
        String secret = null;
        boolean silent = false;

        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--secret=")) {
                secret = args[i].substring("--secret=".length());
            } else if (args[i].equalsIgnoreCase("--silent")) {
                silent = true;
            } else {
                permArg = args[i];
            }
        }

        if (secret == null || secret.isEmpty()) {
            secret = UUID.randomUUID().toString().replace("-", "");
        }

        List<String> permissions = permArg.isEmpty() ? new ArrayList<>() :
                Arrays.asList(permArg.split(","));

        try {
            AccessToken token = tokenService.createToken(name, permissions, secret);
            if (!silent) {
                LOGGER.info("New token for \"{}\" [{}] with permissions: {}", name, secret, permissions);
            }
        } catch (IllegalArgumentException e) {
            LOGGER.error("Error creating token: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Unexpected error: {}", e.getMessage());
        }
    }
}
