package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AuthRoute;
import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.repos.RepositoryData;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.javalin.http.Context;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class PageController {
    private final TokenService tokenService;

    public PageController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public void registerRoutes(Javalin app) {
        app.get("/", ctx -> {
            try (InputStream is = getClass().getResourceAsStream("/static/index.html")) {
                ctx.contentType("text/html");
                assert is != null;
                ctx.result(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        });

        app.get("/api/page/content", this::handlePageContent);
    }

    private void handlePageContent(Context ctx) {
        try {
            RepositoryData data = RepositoryData.loadPageConfig();
            data.repositories = loadRepositoriesWithPrivacy();

            boolean logged = true;
            try {
                AuthRoute.requireManagerOrWrite(ctx, "/api/page/content", tokenService);
            } catch (Exception e) {
                logged = false;
            }

            if (!logged) {
                data.repositories = data.repositories.stream()
                        .filter(repo -> !repo.isPrivate)
                        .toList();
            }

            ctx.contentType(ContentType.APPLICATION_JSON);
            ctx.json(data);
        } catch (Exception e) {
            ctx.status(500).result("{\"error\":\"Cannot load page or repos\"}");
        }
    }


    private List<RepositoryData.Repository> loadRepositoriesWithPrivacy() throws Exception {
        List<RepositoryData.Repository> repos = new ArrayList<>();
        File reposDir = new File("./data/repos");

        if (!reposDir.exists() || !reposDir.isDirectory()) return repos;

        File[] files = reposDir.listFiles(File::isDirectory);
        if (files == null) return repos;

        RepositoryData pageConfig = RepositoryData.loadPageConfig();

        for (File repoDir : files) {
            RepositoryData.Repository repo = new RepositoryData.Repository();
            repo.name = repoDir.getName();
            repo.path = "/" + repoDir.getName();
            repo.tree = loadRepoTree(repoDir.toPath(), repoDir.toPath(), "/" + repoDir.getName());

            RepositoryData.Repository savedRepo = pageConfig.repositories.stream()
                    .filter(r -> r.name.equals(repo.name))
                    .findFirst().orElse(null);

            repo.isPrivate = savedRepo != null ? savedRepo.isPrivate : false;

            repos.add(repo);
        }

        return repos;
    }


    private List<RepositoryData.TreeNode> loadRepoTree(Path rootPath, Path currentPath, String basePath) throws Exception {
        List<RepositoryData.TreeNode> nodes = new ArrayList<>();
        if (!Files.exists(currentPath) || !Files.isDirectory(currentPath)) return nodes;

        Files.list(currentPath)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .forEach(path -> {
                    RepositoryData.TreeNode node = new RepositoryData.TreeNode();
                    node.name = path.getFileName().toString();
                    node.path = basePath + "/" + node.name;

                    if (Files.isDirectory(path)) {
                        node.type = "directory";
                        try {
                            node.children = loadRepoTree(rootPath, path, node.path);
                        } catch (Exception e) {
                            node.children = new ArrayList<>();
                        }
                        node.size = null;
                        node.version = null;
                        node.artifactId = null;
                        node.groupId = null;
                    } else {
                        node.type = "file";
                        node.size = path.toFile().length();
                        node.children = null;

                        if (node.name.endsWith(".jar") || node.name.endsWith(".pom")) {
                            Path versionDir = path.getParent();
                            node.version = versionDir.getFileName().toString();
                        }
                    }

                    nodes.add(node);
                });

        return nodes;
    }

}
