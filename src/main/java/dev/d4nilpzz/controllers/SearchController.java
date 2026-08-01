package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AuthService;
import dev.d4nilpzz.auth.RoutePermission;
import dev.d4nilpzz.http.PathSafety;
import dev.d4nilpzz.repos.*;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Artifact discovery: search by coordinate fragment, and per-artifact details.
 * <p>
 * Results are filtered by what the caller may read, so a private repository never leaks
 * artifact names through search.
 */
public class SearchController {

    private static final int MAX_RESULTS = 200;

    private final AuthService authService;
    private final RepositoryService repositoryService;
    private final MetadataService metadataService;

    public SearchController(AuthService authService,
                            RepositoryService repositoryService,
                            MetadataService metadataService) {
        this.authService = authService;
        this.repositoryService = repositoryService;
        this.metadataService = metadataService;
    }

    public void registerRoutes(Javalin app) {
        app.get("/api/maven/search", this::search);
        app.get("/api/maven/details/{repository}/<path>", this::details);
    }

    private void search(Context ctx) throws IOException {
        String query = ctx.queryParam("q");
        if (query == null || query.isBlank()) throw new BadRequestResponse("Query parameter 'q' is required");

        String needle = query.trim().toLowerCase().replace(':', '/').replace('.', '/');
        int limit = parseLimit(ctx.queryParam("limit"));
        String repositoryFilter = ctx.queryParam("repository");

        List<Result> results = new ArrayList<>();

        for (RepositoryData.Repository repository : readableRepositories(ctx)) {
            if (repositoryFilter != null && !repositoryFilter.equals(repository.name)) continue;

            Path base = PathSafety.resolveChild(repositoryService.root(), repository.name);
            if (base == null || !Files.isDirectory(base)) continue;

            collect(base, repository.name, needle, limit, results);
            if (results.size() >= limit) break;
        }

        ctx.json(Map.of("query", query, "count", results.size(), "results", results));
    }

    /**
     * Walks a repository looking for artifact directories whose coordinates contain the
     * query. Matching happens on the directory holding the versions, so one hit is one
     * artifact rather than one file.
     */
    private void collect(Path base, String repositoryName, String needle, int limit, List<Result> results)
            throws IOException {
        Path root = repositoryService.root();

        try (Stream<Path> stream = Files.walk(base, 12)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                if (results.size() >= limit) return;
                if (directory.equals(base)) continue;

                MavenCoordinates coordinates = MavenCoordinates.ofArtifactDirectory(root, directory);
                if (coordinates == null) continue;

                List<String> versions = metadataService.discoverVersions(directory, coordinates.artifactId());
                if (versions.isEmpty()) continue;

                String haystack = (coordinates.groupId() + "/" + coordinates.artifactId()).toLowerCase();
                if (!haystack.contains(needle)) continue;

                results.add(new Result(
                        repositoryName,
                        coordinates.groupId(),
                        coordinates.artifactId(),
                        versions.reversed(),
                        "/repo/" + repositoryName + "/"
                                + coordinates.groupId().replace('.', '/') + "/" + coordinates.artifactId()));
            }
        }
    }

    /** Versions and files of a single artifact, for the dashboard's detail view. */
    private void details(Context ctx) throws IOException {
        String repositoryName = ctx.pathParam("repository");
        String artifactPath = ctx.pathParam("path");

        RepositoryData.Repository repository = repositoryService.find(repositoryName)
                .orElseThrow(() -> new NotFoundResponse("Repository '" + repositoryName + "' does not exist"));

        if (!repository.resolvedVisibility().allowsAnonymousListing()) {
            authService.require(ctx, "/repo/" + repositoryName, RoutePermission.READ);
        }

        Path directory = repositoryService.resolve(repositoryName + "/" + artifactPath)
                .orElseThrow(() -> new BadRequestResponse("Invalid path"));

        if (!Files.isDirectory(directory)) throw new NotFoundResponse("Artifact not found");

        MavenCoordinates coordinates = MavenCoordinates.ofArtifactDirectory(repositoryService.root(), directory);
        if (coordinates == null) throw new NotFoundResponse("Path does not identify an artifact");

        List<String> versions = metadataService.discoverVersions(directory, coordinates.artifactId());
        if (versions.isEmpty()) throw new NotFoundResponse("Artifact has no published versions");

        List<Map<String, Object>> versionDetails = new ArrayList<>();
        for (String version : versions.reversed()) {
            Path versionDirectory = directory.resolve(version);
            List<Map<String, Object>> files = new ArrayList<>();

            try (Stream<Path> stream = Files.list(versionDirectory)) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (MetadataService.isChecksum(name)) continue;
                    files.add(Map.of(
                            "name", name,
                            "size", Files.size(file),
                            "path", "/repo/" + repositoryService.root().relativize(file)
                                    .toString().replace('\\', '/')));
                }
            }
            versionDetails.add(Map.of("version", version, "files", files));
        }

        ctx.json(Map.of(
                "repository", repositoryName,
                "groupId", coordinates.groupId(),
                "artifactId", coordinates.artifactId(),
                "latest", versions.getLast(),
                "versions", versionDetails
        ));
    }

    private List<RepositoryData.Repository> readableRepositories(Context ctx) {
        List<RepositoryData.Repository> readable = new ArrayList<>();
        for (RepositoryData.Repository repository : repositoryService.repositories()) {
            boolean allowed = repository.resolvedVisibility().allowsAnonymousListing()
                    || authService.canAccess(ctx, "/repo/" + repository.name, RoutePermission.READ);
            if (allowed) readable.add(repository);
        }
        return readable;
    }

    private static int parseLimit(String raw) {
        if (raw == null) return 50;
        try {
            return Math.clamp(Integer.parseInt(raw.trim()), 1, MAX_RESULTS);
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    private record Result(String repository, String groupId, String artifactId,
                          List<String> versions, String path) {
    }
}
