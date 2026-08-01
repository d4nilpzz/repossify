package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AuthService;
import dev.d4nilpzz.repos.RepositoryData;
import dev.d4nilpzz.repos.RepositoryService;
import dev.d4nilpzz.repos.StatisticsService;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolution counts and storage usage. Manager-only: download patterns reveal which private
 * artifacts exist and how they are used.
 */
public class StatisticsController {

    private final AuthService authService;
    private final StatisticsService statisticsService;
    private final RepositoryService repositoryService;

    public StatisticsController(AuthService authService,
                                StatisticsService statisticsService,
                                RepositoryService repositoryService) {
        this.authService = authService;
        this.statisticsService = statisticsService;
        this.repositoryService = repositoryService;
    }

    public void registerRoutes(Javalin app) {
        app.get("/api/statistics", this::summary);
        app.get("/api/statistics/top", this::top);
    }

    private void summary(Context ctx) throws Exception {
        authService.requireManager(ctx);

        StatisticsService.Summary summary = statisticsService.summary();

        List<Map<String, Object>> repositories = new ArrayList<>();
        for (RepositoryData.Repository repository : repositoryService.repositories()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", repository.name);
            entry.put("visibility", repository.resolvedVisibility().name());
            entry.put("sizeBytes", repositoryService.sizeOf(repository.name));
            entry.put("downloads", summary.perRepository().getOrDefault(repository.name, 0L));
            entry.put("quota", repository.storageQuota);
            entry.put("mirrors", repository.proxied == null ? 0 : repository.proxied.size());
            repositories.add(entry);
        }

        ctx.json(Map.of(
                "totalDownloads", summary.totalDownloads(),
                "uniqueArtifacts", summary.uniqueArtifacts(),
                "repositories", repositories
        ));
    }

    private void top(Context ctx) throws Exception {
        authService.requireManager(ctx);

        int limit = 20;
        String raw = ctx.queryParam("limit");
        if (raw != null) {
            try {
                limit = Math.clamp(Integer.parseInt(raw.trim()), 1, 100);
            } catch (NumberFormatException ignored) {
                // Keep the default.
            }
        }

        ctx.json(statisticsService.top(limit));
    }
}
