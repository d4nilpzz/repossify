package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AccessToken;
import dev.d4nilpzz.auth.AuthRoute;
import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.utils.MavenUtils;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public class FileController {

    private static final Path BASE_PATH = Paths.get("./data/repos");
    private final TokenService tokenService;

    public FileController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public void registerRoutes(Javalin app) {
        app.post("/api/file/upload", this::handleFileUpload);
        app.delete("/api/file/delete", this::handleDeletePath);

        app.get("/api/file/view/*", this::handleFileView);
    }

    private void handleFileView(Context ctx) throws IOException {
        String fullPath = ctx.path();
        String prefix = "/api/file/view/";
        if (!fullPath.startsWith(prefix)) {
            ctx.status(400).result("Invalid path");
            return;
        }

        String filePath = fullPath.substring(prefix.length());

        final Path BASE_PATH_VIEW = Paths.get("./data/repos").toAbsolutePath().normalize();
        Path target = BASE_PATH_VIEW.resolve(filePath).normalize();
        if (!target.startsWith(BASE_PATH_VIEW) || !Files.exists(target) || Files.isDirectory(target)) {
            ctx.status(404).result("File not found");
            return;
        }

        String contentType = Files.probeContentType(target);
        if (contentType == null) contentType = "application/octet-stream";
        ctx.contentType(contentType);
        ctx.result(Files.newInputStream(target));
    }

    private void handleFileUpload(Context ctx) throws IOException {
        AccessToken token = AuthRoute.requireManagerOrWrite(ctx, "/api/file/upload", tokenService);

        String repo = ctx.formParam("repo");
        String path = ctx.formParam("path");
        boolean match = Boolean.parseBoolean(ctx.formParam("mach"));
        boolean generatePom = Boolean.parseBoolean(ctx.formParam("generate_pom_file"));

        String groupId = ctx.formParam("maven[groupId]");
        String artifactId = ctx.formParam("maven[artifactId]");
        String version = ctx.formParam("maven[version]");

        if (!match) {
            ctx.status(400).result("Path does not match maven coordinates");
            return;
        }

        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            ctx.status(400).result("File missing");
            return;
        }

        Path targetDir = BASE_PATH.resolve(repo).resolve(path);
        Files.createDirectories(targetDir);

        Path targetFile = targetDir.resolve(file.filename());
        Files.copy(file.content(), targetFile, StandardCopyOption.REPLACE_EXISTING);

        Path artifactBase = BASE_PATH
                .resolve(repo)
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId);

        Files.createDirectories(artifactBase);

        Set<String> versions = loadExistingVersions(artifactBase);
        versions.add(version);

        Path metadataFile = artifactBase.resolve("maven-metadata.xml");
        Files.writeString(
                metadataFile,
                MavenUtils.generateMavenMetadata(groupId, artifactId, versions),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        if (generatePom) {
            Path pomPath = artifactBase
                    .resolve(version)
                    .resolve(artifactId + "-" + version + ".pom");

            Files.createDirectories(pomPath.getParent());

            Files.writeString(
                    pomPath,
                    MavenUtils.generatePom(groupId, artifactId, version),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        }

        ctx.status(201);
    }

    private void handleDeletePath(Context ctx) throws IOException {
        AccessToken token = AuthRoute.requireManagerOrWrite(ctx, "/api/file/delete", tokenService);

        String repo = ctx.queryParam("repo");
        String path = ctx.queryParam("path");

        System.out.println("handleDeletePath called with:");
        System.out.println("repo = " + repo);
        System.out.println("path = " + path);

        if (repo == null || path == null || repo.isEmpty() || path.isEmpty()) {
            ctx.status(400).result("Missing repo or path");
            return;
        }

        if (path.startsWith("/" + repo + "/")) {
            path = path.substring(repo.length() + 2);
        } else if (path.startsWith(repo + "/")) {
            path = path.substring(repo.length() + 1);
        }

        // Resuelve el path correctamente
        Path target = BASE_PATH.resolve(repo);
        for (String segment : path.split("/")) {
            target = target.resolve(segment);
        }

        System.out.println("Resolved target path: " + target.toAbsolutePath());

        if (!Files.exists(target)) {
            ctx.status(404).result("Path not found");
            return;
        }

        // Borra archivo o carpeta recursivamente
        Files.walk(target)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {}
                });

        ctx.status(204);
    }

    private Set<String> loadExistingVersions(Path artifactBase) throws IOException {
        Set<String> versions = new HashSet<>();
        if (!Files.exists(artifactBase)) return versions;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifactBase)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    versions.add(p.getFileName().toString());
                }
            }
        }
        return versions;
    }
}
