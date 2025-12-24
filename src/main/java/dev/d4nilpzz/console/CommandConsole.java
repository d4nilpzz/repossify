package dev.d4nilpzz.console;

import dev.d4nilpzz.Repossify;
import dev.d4nilpzz.auth.AccessToken;
import dev.d4nilpzz.auth.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;

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
        LOGGER.info("Command console started. Type 'help' or '0' for commands.");

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
            case "0":
            case "help":
                LOGGER.info("""
                        Available commands:
                        ➜ [0]  help
                        ➜ [1]  stop
                        ➜ [2]  version
                        ➜ [3]  generate_token <name> [<permissions>] [--secret=<secret>] [--silent]
                        ➜ [4]  delete_token <name>
                        ➜ [5]  delete_all_tokens
                        ➜ [6]  token_modify <name> <permissions>
                        ➜ [7]  token_rename <oldName> <newName>
                        ➜ [8]  token_regenerate <name>
                        ➜ [9]  token_add_route <tokenName> <path> <r/w>
                        ➜ [10] token_remove_route <tokenName> <path>
                        ➜ [11] performance
                        """);
                break;
            case "1":
            case "stop":
                LOGGER.info("Stopping Repossify...");
                running = false;
                System.exit(0);
                break;
            case "2":
            case "version":
                LOGGER.info(Repossify.VERSION);
                break;
            case "3":
            case "generate_token":
                generateToken(args);
                break;
            case "4":
            case "delete_all_tokens":
                try {
                    tokenService.deleteAllTokens();
                    LOGGER.info("All tokens have been deleted from the database!");
                } catch (Exception e) {
                    LOGGER.error("Error deleting all tokens: {}", e.getMessage());
                }

                break;
            case "5":
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
            case "6":
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
            case "7":
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
            case "8":
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

            case "9":
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
            case "10":
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
            case "11":
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
    private void performance() {
        Runtime rt = Runtime.getRuntime();
        OperatingSystemMXBean os =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        long usedMem = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long totalMem = rt.totalMemory() / 1024 / 1024;

        int cpuUsage = (int) Math.round(os.getProcessCpuLoad() * 100);

        LOGGER.info("""
            Performance stats:
            ➜ CPU usage       : {} %
            ➜ CPU cores       : {}
            ➜ Memory used     : {} / {} MB
            """,
                cpuUsage,
                rt.availableProcessors(),
                usedMem,
                totalMem
        );
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
