package dev.d4nilpzz.console;

import dev.d4nilpzz.Repossify;
import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.auth.AccessToken;

import java.util.*;
import java.util.logging.Logger;

/**
 * CommandConsole provides an interactive command-line interface for managing
 * Repossify tokens and server control. Supports token creation, deletion,
 * modification, renaming, secret regeneration, and server commands.
 */
public class CommandConsole implements Runnable {

    private static final Logger logger = Logger.getLogger(CommandConsole.class.getName());
    private volatile boolean running = true;
    private final TokenService tokenService;

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
        logger.info("Command console started. Type 'help' for commands.");

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
        String prefix = " ➜ ";
        if (input.isEmpty()) return;

        String[] parts = input.split("\\s+");
        String command = parts[0].toLowerCase();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        switch (command) {
            case "help":
                System.out.println("\nAvailable commands:");
                System.out.println(prefix + " help");
                System.out.println(prefix + " stop");
                System.out.println(prefix + " version");
                System.out.println(prefix + " generate_token <name> [<permissions>] [--secret=<secret>] [--silent]");
                System.out.println(prefix + " delete_token <name>");
                System.out.println(prefix + " delete_all_tokens");
                System.out.println(prefix + " token_modify <name> <permissions>");
                System.out.println(prefix + " token_rename <oldName> <newName>");
                System.out.println(prefix + " token_regenerate <name>");
                System.out.println();
                break;

            case "stop":
                logger.info("Stopping Repossify...");
                running = false;
                System.exit(0);
                break;

            case "version":
                System.out.println(Repossify.VERSION);
                break;

            case "generate_token":
                generateToken(args);
                break;

            case "delete_all_tokens":
                try {
                    tokenService.deleteAllTokens();
                    System.out.println("All tokens have been deleted from the database!");
                } catch (Exception e) {
                    System.out.println("Error deleting all tokens: " + e.getMessage());
                }
                break;

            case "delete_token":
                if (args.length < 1) {
                    System.out.println("Usage: delete_token <name>");
                    break;
                }
                try {
                    String tokenName = args[0];
                    tokenService.deleteTokenByName(tokenName);
                    System.out.println("Token '" + tokenName + "' has been deleted successfully.");
                } catch (Exception e) {
                    System.out.println("Error deleting token: " + e.getMessage());
                }
                break;

            case "token_modify":
                if (args.length < 2) {
                    System.out.println("Usage: token_modify <name> <permissions>");
                    break;
                }
                try {
                    String name = args[0];
                    List<String> perms = Arrays.asList(args[1].split(","));
                    tokenService.updateTokenPermissions(name, perms);
                    System.out.println("Token '" + name + "' permissions updated to: " + perms);
                } catch (Exception e) {
                    System.out.println("Error modifying token: " + e.getMessage());
                }
                break;

            case "token_rename":
                if (args.length < 2) {
                    System.out.println("Usage: token_rename <oldName> <newName>");
                    break;
                }
                try {
                    String oldName = args[0];
                    String newName = args[1];
                    tokenService.renameToken(oldName, newName);
                    System.out.println("Token renamed from '" + oldName + "' to '" + newName + "'");
                } catch (Exception e) {
                    System.out.println("Error renaming token: " + e.getMessage());
                }
                break;

            case "token_regenerate":
                if (args.length < 1) {
                    System.out.println("Usage: token_regenerate <name>");
                    break;
                }
                try {
                    String name = args[0];
                    String newSecret = tokenService.regenerateTokenSecret(name);
                    System.out.println("Token '" + name + "' secret regenerated. New secret: " + newSecret);
                } catch (Exception e) {
                    System.out.println("Error regenerating token: " + e.getMessage());
                }
                break;

            default:
                System.out.println("Unknown command. Type 'help' to see available commands.");
        }
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
            System.out.println("Usage: generate_token <name> [<permissions>] [--secret=<secret>] [--silent]");
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
                System.out.println("New token for \""+name+"\" ["+secret+"] with permissions: "+permissions);
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Error creating token: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Unexpected error: " + e.getMessage());
        }
    }
}
