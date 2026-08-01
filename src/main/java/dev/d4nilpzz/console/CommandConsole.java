package dev.d4nilpzz.console;

import com.sun.management.OperatingSystemMXBean;
import dev.d4nilpzz.Repossify;
import dev.d4nilpzz.auth.AccessToken;
import dev.d4nilpzz.auth.RoutePermission;
import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.http.PathSafety;
import dev.d4nilpzz.repos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.*;

/**
 * Interactive administration console, available both on stdin and through the dashboard's
 * terminal tab.
 */
public class CommandConsole implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandConsole.class);
    private static final int BAR_LENGTH = 50;

    private final TokenService tokenService;
    private final RepositoryService repositoryService;
    private final MetadataService metadataService;
    private final GarbageCollector garbageCollector;
    private final StatisticsService statisticsService;

    private volatile boolean running = true;

    public CommandConsole(TokenService tokenService,
                          RepositoryService repositoryService,
                          MetadataService metadataService,
                          GarbageCollector garbageCollector,
                          StatisticsService statisticsService) {
        this.tokenService = tokenService;
        this.repositoryService = repositoryService;
        this.metadataService = metadataService;
        this.garbageCollector = garbageCollector;
        this.statisticsService = statisticsService;
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        LOGGER.info("Command console started. Type 'help' or '?' for commands.");

        while (running) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) break; // stdin closed, e.g. running detached
            handleCommand(scanner.nextLine().trim());
        }
        scanner.close();
    }

    public void handleCommand(String input) {
        if (input == null || input.isEmpty()) return;

        String[] parts = input.split("\\s+");
        String command = parts[0].toLowerCase();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        switch (command) {
            case "?", "help" -> printHelp();
            case "docs" -> LOGGER.info("https://repossify.dev/docs/");
            case "version" -> LOGGER.info(Repossify.VERSION);

            case "generate_token" -> generateToken(args);
            case "list_tokens" -> listTokens();
            case "delete_token" -> deleteToken(args);
            case "delete_all_tokens" -> deleteAllTokens();
            case "token_modify" -> modifyToken(args);
            case "token_rename" -> renameToken(args);
            case "token_regenerate" -> regenerateToken(args);
            case "token_add_route" -> addRoute(args);
            case "token_remove_route" -> removeRoute(args);

            case "repositories" -> listRepositories();
            case "repair_metadata" -> repairMetadata(args);
            case "prune_snapshots" -> pruneSnapshots(args);
            case "statistics", "stats" -> printStatistics();

            case "performance" -> performance();
            case "stop" -> stop();

            default -> LOGGER.warn("Unknown command. Type 'help' to see available commands.");
        }
    }

    private void printHelp() {
        LOGGER.info("""
                Available commands:
                 - help or ?
                 - docs
                 - version
                 - generate_token <name> [<permissions>] [--secret=<secret>] [--silent]
                 - list_tokens
                 - delete_token <name>
                 - delete_all_tokens
                 - token_modify <name> <permissions>
                 - token_rename <oldName> <newName>
                 - token_regenerate <name>
                 - token_add_route <tokenName> <path> <r|w>
                 - token_remove_route <tokenName> <path>
                 - repositories
                 - repair_metadata <repository|*>
                 - prune_snapshots [<repository>]
                 - statistics
                 - performance
                 - stop
                """);
    }

    /* ===================== tokens ===================== */

    private void generateToken(String[] args) {
        if (args.length < 1) {
            LOGGER.warn("Usage: generate_token <name> [<permissions>] [--secret=<secret>] [--silent]");
            return;
        }

        String name = args[0];
        String permissionArg = "";
        String secret = null;
        boolean silent = false;

        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--secret=")) {
                secret = args[i].substring("--secret=".length());
            } else if (args[i].equalsIgnoreCase("--silent")) {
                silent = true;
            } else {
                permissionArg = args[i];
            }
        }

        if (secret == null || secret.isEmpty()) secret = TokenService.generateSecret();

        List<String> permissions = permissionArg.isEmpty()
                ? new ArrayList<>()
                : Arrays.asList(permissionArg.split(","));

        try {
            tokenService.createToken(name, permissions, secret);
            if (!silent) {
                LOGGER.info("New token for \"{}\" [{}] with permissions: {}", name, secret, permissions);
            }
        } catch (IllegalArgumentException e) {
            LOGGER.error("Error creating token: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Unexpected error: {}", e.getMessage());
        }
    }

    private void listTokens() {
        try {
            List<AccessToken> tokens = tokenService.listTokens();
            if (tokens.isEmpty()) {
                LOGGER.info("No tokens defined.");
                return;
            }
            LOGGER.info("{} token(s):", tokens.size());
            for (AccessToken token : tokens) {
                String routes = token.routes.isEmpty()
                        ? "-"
                        : token.routes.stream()
                        .map(route -> route.path() + "(" + route.routePermission() + ")")
                        .reduce((a, b) -> a + ", " + b).orElse("-");
                LOGGER.info("  {} | permissions: {} | routes: {}",
                        token.name, token.permissions.isEmpty() ? "-" : token.permissions, routes);
            }
        } catch (Exception e) {
            LOGGER.error("Error listing tokens: {}", e.getMessage());
        }
    }

    private void deleteToken(String[] args) {
        if (args.length < 1) {
            LOGGER.warn("Usage: delete_token <name>");
            return;
        }
        try {
            tokenService.deleteTokenByName(args[0]);
            LOGGER.info("Token '{}' has been deleted successfully.", args[0]);
        } catch (Exception e) {
            LOGGER.error("Error deleting token: {}", e.getMessage());
        }
    }

    private void deleteAllTokens() {
        try {
            tokenService.deleteAllTokens();
            LOGGER.info("All tokens have been deleted. A new admin token is created on next start.");
        } catch (Exception e) {
            LOGGER.error("Error deleting all tokens: {}", e.getMessage());
        }
    }

    private void modifyToken(String[] args) {
        if (args.length < 2) {
            LOGGER.warn("Usage: token_modify <name> <permissions>");
            return;
        }
        try {
            List<String> permissions = Arrays.asList(args[1].split(","));
            tokenService.updateTokenPermissions(args[0], permissions);
            LOGGER.info("Token '{}' permissions updated to: {}", args[0], permissions);
        } catch (Exception e) {
            LOGGER.error("Error modifying token: {}", e.getMessage());
        }
    }

    private void renameToken(String[] args) {
        if (args.length < 2) {
            LOGGER.warn("Usage: token_rename <oldName> <newName>");
            return;
        }
        try {
            tokenService.renameToken(args[0], args[1]);
            LOGGER.info("Token renamed from '{}' to '{}'", args[0], args[1]);
        } catch (Exception e) {
            LOGGER.error("Error renaming token: {}", e.getMessage());
        }
    }

    private void regenerateToken(String[] args) {
        if (args.length < 1) {
            LOGGER.warn("Usage: token_regenerate <name>");
            return;
        }
        try {
            String secret = tokenService.regenerateTokenSecret(args[0]);
            LOGGER.info("Token '{}' secret regenerated. New secret: {}", args[0], secret);
        } catch (Exception e) {
            LOGGER.error("Error regenerating token: {}", e.getMessage());
        }
    }

    private void addRoute(String[] args) {
        if (args.length < 3) {
            LOGGER.warn("Usage: token_add_route <tokenName> <path> <r|w>");
            return;
        }
        if (RoutePermission.parse(args[2]) == null) {
            LOGGER.warn("Route permission must be 'r' or 'w'");
            return;
        }
        try {
            tokenService.addRouteToToken(args[0], args[1], args[2]);
            LOGGER.info("Route '{}' added to token '{}' with permission '{}'", args[1], args[0], args[2]);
        } catch (Exception e) {
            LOGGER.error("Error adding route: {}", e.getMessage());
        }
    }

    private void removeRoute(String[] args) {
        if (args.length < 2) {
            LOGGER.warn("Usage: token_remove_route <tokenName> <path>");
            return;
        }
        try {
            tokenService.removeRouteFromToken(args[0], args[1]);
            LOGGER.info("Route '{}' removed from token '{}'", args[1], args[0]);
        } catch (Exception e) {
            LOGGER.error("Error removing route: {}", e.getMessage());
        }
    }

    /* ===================== repositories ===================== */

    private void listRepositories() {
        List<RepositoryData.Repository> repositories = repositoryService.repositories();
        if (repositories.isEmpty()) {
            LOGGER.info("No repositories configured.");
            return;
        }
        for (RepositoryData.Repository repository : repositories) {
            LOGGER.info("  {} | {} | redeployment: {} | keep snapshots: {} | mirrors: {} | size: {}",
                    repository.name,
                    repository.resolvedVisibility(),
                    repository.redeployment,
                    repository.preserveSnapshots == 0 ? "all" : repository.preserveSnapshots,
                    repository.proxied == null ? 0 : repository.proxied.size(),
                    formatBytes(repositoryService.sizeOf(repository.name)));
        }
    }

    /**
     * Rebuilds metadata for a repository. Needed after copying artifacts in by hand, which
     * is how most people migrate off another repository manager.
     */
    private void repairMetadata(String[] args) {
        if (args.length < 1) {
            LOGGER.warn("Usage: repair_metadata <repository|*>");
            return;
        }

        List<RepositoryData.Repository> targets = "*".equals(args[0])
                ? repositoryService.repositories()
                : repositoryService.find(args[0]).map(List::of).orElse(List.of());

        if (targets.isEmpty()) {
            LOGGER.warn("Repository '{}' does not exist", args[0]);
            return;
        }

        for (RepositoryData.Repository repository : targets) {
            Path directory = PathSafety.resolveChild(repositoryService.root(), repository.name);
            if (directory == null) continue;
            try {
                int written = metadataService.repairRepository(repositoryService.root(), directory);
                repositoryService.invalidate(repository.name);
                LOGGER.info("Rebuilt {} metadata file(s) in '{}'", written, repository.name);
            } catch (Exception e) {
                LOGGER.error("Error repairing '{}': {}", repository.name, e.getMessage());
            }
        }
    }

    private void pruneSnapshots(String[] args) {
        try {
            if (args.length == 0) {
                LOGGER.info("Pruned {} stale snapshot file(s) across all repositories", garbageCollector.pruneAll());
                return;
            }
            Optional<RepositoryData.Repository> repository = repositoryService.find(args[0]);
            if (repository.isEmpty()) {
                LOGGER.warn("Repository '{}' does not exist", args[0]);
                return;
            }
            LOGGER.info("Pruned {} stale snapshot file(s) in '{}'",
                    garbageCollector.pruneRepository(repository.get()), args[0]);
        } catch (Exception e) {
            LOGGER.error("Error pruning snapshots: {}", e.getMessage());
        }
    }

    private void printStatistics() {
        try {
            StatisticsService.Summary summary = statisticsService.summary();
            LOGGER.info("Total resolutions: {} across {} artifact(s)",
                    summary.totalDownloads(), summary.uniqueArtifacts());
            summary.perRepository().forEach((repository, count) ->
                    LOGGER.info("  {} : {}", repository, count));

            List<StatisticsService.Entry> top = statisticsService.top(5);
            if (!top.isEmpty()) {
                LOGGER.info("Most resolved:");
                top.forEach(entry ->
                        LOGGER.info("  {} {} : {}", entry.repository(), entry.path(), entry.downloads()));
            }
        } catch (Exception e) {
            LOGGER.error("Error reading statistics: {}", e.getMessage());
        }
    }

    /* ===================== host ===================== */

    private void performance() {
        Runtime runtime = Runtime.getRuntime();
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        int cpuCores = runtime.availableProcessors();
        double cpuUsage = 0.0;
        try {
            long startTime = System.nanoTime();
            long startCpu = os.getProcessCpuTime();
            Thread.sleep(100);
            long endTime = System.nanoTime();
            long endCpu = os.getProcessCpuTime();
            cpuUsage = (endCpu - startCpu) / (double) (endTime - startTime) / cpuCores;
            cpuUsage = Math.min(cpuUsage, 1.0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int cpuPercent = (int) Math.round(cpuUsage * 100);
        double usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024.0 / 1024 / 1024;
        double maxMemory = runtime.maxMemory() / 1024.0 / 1024 / 1024;
        int memoryPercent = maxMemory == 0 ? 0 : (int) ((usedMemory * 100) / maxMemory);

        File volume = Repossify.WORKING_DIR.toFile();
        long totalStore = volume.getTotalSpace() / 1024 / 1024 / 1024;
        long usedStore = totalStore - volume.getUsableSpace() / 1024 / 1024 / 1024;
        int storePercent = totalStore == 0 ? 0 : (int) ((usedStore * 100) / totalStore);

        System.out.printf(
                "CPU     [ %s ] %d%% (%d cores)%n" +
                        "Memory  [ %s ] %.2f/%.2f GB%n" +
                        "Storage [ %s ] %d GB / %d GB%n",
                buildBar(cpuPercent), cpuPercent, cpuCores,
                buildBar(memoryPercent), usedMemory, maxMemory,
                buildBar(storePercent), usedStore, totalStore);
    }

    private void stop() {
        LOGGER.info("Shutting down...");
        running = false;
        System.exit(0);
    }

    private static String buildBar(int percent) {
        int filled = Math.clamp((percent * BAR_LENGTH) / 100, 0, BAR_LENGTH);
        return "|".repeat(filled) + " ".repeat(BAR_LENGTH - filled);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f kB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
