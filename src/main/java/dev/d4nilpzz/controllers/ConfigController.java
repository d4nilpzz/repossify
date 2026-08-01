package dev.d4nilpzz.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.d4nilpzz.auth.AuthService;
import dev.d4nilpzz.config.ConfigService;
import dev.d4nilpzz.repos.ProxyService;
import dev.d4nilpzz.repos.RepositoryData;
import dev.d4nilpzz.repos.RepositoryService;
import dev.d4nilpzz.repos.StatisticsService;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and writes {@code page.json} (dashboard settings and repository definitions) and
 * exposes the server configuration.
 */
public class ConfigController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Repository names become directory names, so they are restricted to a safe alphabet. */
    private static final Pattern REPOSITORY_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    private final AuthService authService;
    private final RepositoryService repositoryService;
    private final ConfigService configService;
    private final ProxyService proxyService;
    private final StatisticsService statisticsService;

    public ConfigController(AuthService authService,
                            RepositoryService repositoryService,
                            ConfigService configService,
                            ProxyService proxyService,
                            StatisticsService statisticsService) {
        this.authService = authService;
        this.repositoryService = repositoryService;
        this.configService = configService;
        this.proxyService = proxyService;
        this.statisticsService = statisticsService;
    }

    public void registerRoutes(Javalin app) {
        app.put("/api/config/update", this::handleUpdate);
        app.get("/api/config/server", ctx -> {
            authService.requireManager(ctx);
            ctx.json(configService.current());
        });
    }

    private void handleUpdate(Context ctx) throws Exception {
        // Editing repositories, visibility and mirrors is administrative. This used to accept
        // any token with a write route whose prefix covered /api, which let a deploy token
        // reconfigure the server.
        authService.requireManager(ctx);

        RepositoryData incoming = MAPPER.readValue(ctx.body(), RepositoryData.class);
        RepositoryData current = repositoryService.config();

        Set<String> previousNames = new HashSet<>();
        current.repositories.forEach(repository -> previousNames.add(repository.name));

        RepositoryData merged = new RepositoryData();
        merged.title = firstNonNull(incoming.title, current.title);
        merged.author = firstNonNull(incoming.author, current.author);
        merged.group_id = firstNonNull(incoming.group_id, current.group_id);
        merged.description = firstNonNull(incoming.description, current.description);
        merged.avatar_url = firstNonNull(incoming.avatar_url, current.avatar_url);
        merged.domain_url = firstNonNull(incoming.domain_url, current.domain_url);
        merged.links = incoming.links != null ? incoming.links : current.links;
        merged.repositories = incoming.repositories != null ? incoming.repositories : current.repositories;

        validateRepositories(merged);

        // The dashboard sends back the whole payload it received, including the file tree.
        // Persisting that would grow page.json to the size of the repository; saveConfig
        // drops it, but validating first keeps the error messages meaningful.
        repositoryService.saveConfig(merged);
        repositoryService.syncDirectories(previousNames);
        proxyService.reset();

        // Statistics are keyed by repository name, so a removed repository would otherwise
        // keep contributing rows that no longer correspond to anything.
        Set<String> remaining = new HashSet<>();
        merged.repositories.forEach(repository -> remaining.add(repository.name));
        for (String removed : previousNames) {
            if (!remaining.contains(removed)) statisticsService.purge(removed);
        }

        LOGGER.info("Configuration updated by {}", ctx.ip());
        ctx.json(merged);
    }

    private void validateRepositories(RepositoryData data) {
        Set<String> seen = new HashSet<>();

        for (RepositoryData.Repository repository : data.repositories) {
            if (repository.name == null || !REPOSITORY_NAME.matcher(repository.name).matches()) {
                throw new BadRequestResponse("Invalid repository name: '" + repository.name +
                        "'. Use lowercase letters, digits, dot, dash or underscore.");
            }
            if (!seen.add(repository.name)) {
                throw new BadRequestResponse("Duplicate repository name: " + repository.name);
            }
            if (repository.proxied != null) {
                for (RepositoryData.Proxy proxy : repository.proxied) {
                    if (proxy.url == null || !(proxy.url.startsWith("http://") || proxy.url.startsWith("https://"))) {
                        throw new BadRequestResponse("Mirror URL must be http or https: " + proxy.url);
                    }
                }
            }
            repository.normalize();
        }
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
