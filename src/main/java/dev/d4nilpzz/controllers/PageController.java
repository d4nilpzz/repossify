package dev.d4nilpzz.controllers;

import dev.d4nilpzz.Repossify;
import dev.d4nilpzz.auth.AuthService;
import dev.d4nilpzz.auth.RoutePermission;
import dev.d4nilpzz.repos.RepositoryData;
import dev.d4nilpzz.repos.RepositoryService;
import dev.d4nilpzz.repos.Visibility;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PageController {

    private final AuthService authService;
    private final RepositoryService repositoryService;

    public PageController(AuthService authService, RepositoryService repositoryService) {
        this.authService = authService;
        this.repositoryService = repositoryService;
    }

    public void registerRoutes(Javalin app) {
        app.get("/", this::serveIndex);
        app.get("/api/page/content", this::handlePageContent);
        app.get("/api/version", ctx -> ctx.json(java.util.Map.of("version", Repossify.VERSION)));
    }

    private void serveIndex(Context ctx) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/static/index.html")) {
            if (stream == null) {
                ctx.status(500).result("Dashboard assets are missing from this build");
                return;
            }
            ctx.contentType("text/html");
            ctx.result(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Page configuration plus the repository trees the caller is allowed to see.
     * <p>
     * The trees come from {@link RepositoryService}, which caches them; this endpoint used
     * to walk every file in every repository on each request.
     */
    private void handlePageContent(Context ctx) throws Exception {
        RepositoryData config = repositoryService.config();
        RepositoryData response = new RepositoryData();

        response.title = config.title;
        response.author = config.author;
        response.group_id = config.group_id;
        response.description = config.description;
        response.avatar_url = config.avatar_url;
        response.domain_url = config.domain_url;
        response.links = config.links;
        response.repositories = new ArrayList<>();

        boolean manager = authService.resolve(ctx).map(token -> token.isManager).orElse(false);

        for (RepositoryData.Repository repository : config.repositories) {
            Visibility visibility = repository.resolvedVisibility();
            boolean readable = visibility.allowsAnonymousListing()
                    || authService.canAccess(ctx, "/repo/" + repository.name, RoutePermission.READ);
            if (!readable) continue;

            RepositoryData.Repository view = new RepositoryData.Repository();
            view.name = repository.name;
            view.path = repository.path;
            view.visibility = visibility.name();
            view.isPrivate = visibility == Visibility.PRIVATE;
            view.tree = repositoryService.tree(repository.name);

            // Deployment policy is operational detail; only managers need it in the UI.
            if (manager) {
                view.redeployment = repository.redeployment;
                view.preserveSnapshots = repository.preserveSnapshots;
                view.storageQuota = repository.storageQuota;
                view.proxied = repository.proxied;
            } else {
                view.proxied = List.of();
            }

            response.repositories.add(view);
        }

        ctx.json(response);
    }
}
