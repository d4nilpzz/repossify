package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AuthService;
import dev.d4nilpzz.auth.RoutePermission;
import dev.d4nilpzz.http.ErrorResponse;
import dev.d4nilpzz.repos.*;
import dev.d4nilpzz.utils.ChecksumUtils;
import dev.d4nilpzz.utils.MavenUtils;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Upload and delete endpoints used by the dashboard.
 * <p>
 * Both previously resolved their target by concatenating request parameters straight onto
 * the repositories directory, so a {@code path} of {@code ../../} wrote — or recursively
 * deleted — anywhere the process could reach. Every path now goes through
 * {@link RepositoryService#resolve}, and the repository must be one declared in the
 * configuration.
 */
public class FileApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileApiController.class);

    private final AuthService authService;
    private final RepositoryService repositoryService;
    private final MetadataService metadataService;
    private final GarbageCollector garbageCollector;

    public FileApiController(AuthService authService,
                             RepositoryService repositoryService,
                             MetadataService metadataService,
                             GarbageCollector garbageCollector) {
        this.authService = authService;
        this.repositoryService = repositoryService;
        this.metadataService = metadataService;
        this.garbageCollector = garbageCollector;
    }

    public void registerRoutes(Javalin app) {
        app.post("/api/file/upload", this::handleUpload);
        app.delete("/api/file/delete", this::handleDelete);
    }

    private void handleUpload(Context ctx) throws IOException {
        String repositoryName = ctx.formParam("repo");
        String relativePath = ctx.formParam("path");
        String groupId = ctx.formParam("maven[groupId]");
        String artifactId = ctx.formParam("maven[artifactId]");
        String version = ctx.formParam("maven[version]");
        boolean generatePom = Boolean.parseBoolean(ctx.formParam("generate_pom_file"));

        if (isBlank(repositoryName) || isBlank(groupId) || isBlank(artifactId) || isBlank(version)) {
            throw new BadRequestResponse("repo, groupId, artifactId and version are required");
        }

        RepositoryData.Repository repository = repositoryService.find(repositoryName)
                .orElseThrow(() -> new NotFoundResponse("Repository '" + repositoryName + "' does not exist"));

        // The coordinates are the source of truth; the client-supplied path is only accepted
        // when it agrees with them, which is what the old "mach" flag was meant to assert.
        String coordinatePath = groupId.replace('.', '/') + "/" + artifactId + "/" + version;
        if (!isBlank(relativePath) && !normalize(relativePath).equals(coordinatePath)) {
            throw new BadRequestResponse("Path does not match the Maven coordinates");
        }

        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) throw new BadRequestResponse("File missing");

        String targetRelative = repositoryName + "/" + coordinatePath + "/" + sanitizeFilename(file.filename());
        authService.require(ctx, "/repo/" + targetRelative, RoutePermission.WRITE);

        Path target = repositoryService.resolve(targetRelative)
                .orElseThrow(() -> new BadRequestResponse("Invalid upload path"));

        if (!garbageCollector.hasSpaceFor(repository, Math.max(file.size(), 0))) {
            ctx.status(507).json(ErrorResponse.of(507,
                    "Repository '" + repositoryName + "' is over its storage quota"));
            return;
        }

        Files.createDirectories(target.getParent());
        try (InputStream content = file.content()) {
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        }

        if (!MetadataService.isChecksum(target.getFileName().toString())) {
            ChecksumUtils.writeChecksums(target);
        }

        Path versionDirectory = target.getParent();
        Path artifactDirectory = versionDirectory.getParent();

        if (generatePom) {
            writePomIfAbsent(versionDirectory, groupId, artifactId, version);
        }

        // A dashboard upload has no client maintaining metadata, so it is rebuilt here.
        Path root = repositoryService.root();
        if (version.endsWith("-SNAPSHOT")) {
            metadataService.writeSnapshotMetadata(root, versionDirectory);
        }
        metadataService.writeArtifactMetadata(root, artifactDirectory);

        repositoryService.invalidate(repositoryName);
        LOGGER.info("Uploaded {} to {} by {}", file.filename(), repositoryName, ctx.ip());
        ctx.status(201).json(java.util.Map.of("path", "/" + targetRelative));
    }

    /**
     * Writes a stub POM when the dashboard explicitly asks for one. Never overwrites a real
     * POM: the generated file declares no dependencies, so replacing a genuine one would
     * silently break every consumer of the artifact.
     */
    private void writePomIfAbsent(Path versionDirectory, String groupId, String artifactId, String version)
            throws IOException {
        Path pom = versionDirectory.resolve(artifactId + "-" + version + ".pom");
        if (Files.exists(pom)) return;

        Files.createDirectories(pom.getParent());
        Files.writeString(pom, MavenUtils.generatePom(groupId, artifactId, version),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ChecksumUtils.writeChecksums(pom);
    }

    private void handleDelete(Context ctx) throws IOException {
        String repositoryName = ctx.queryParam("repo");
        String path = ctx.queryParam("path");

        if (isBlank(repositoryName) || isBlank(path)) {
            throw new BadRequestResponse("Missing repo or path");
        }

        repositoryService.find(repositoryName)
                .orElseThrow(() -> new NotFoundResponse("Repository '" + repositoryName + "' does not exist"));

        // The dashboard sends paths that already include the repository name; accept both forms.
        String cleaned = normalize(path);
        if (cleaned.equals(repositoryName)) {
            throw new BadRequestResponse("Refusing to delete the repository root");
        }
        if (cleaned.startsWith(repositoryName + "/")) {
            cleaned = cleaned.substring(repositoryName.length() + 1);
        }

        String targetRelative = repositoryName + "/" + cleaned;
        authService.require(ctx, "/repo/" + targetRelative, RoutePermission.WRITE);

        Path repositoryRoot = repositoryService.repositoryPath(repositoryName)
                .orElseThrow(() -> new BadRequestResponse("Invalid repository"));
        Path target = repositoryService.resolve(targetRelative)
                .orElseThrow(() -> new BadRequestResponse("Invalid path"));

        if (target.equals(repositoryRoot)) {
            throw new BadRequestResponse("Refusing to delete the repository root");
        }
        if (!Files.exists(target)) throw new NotFoundResponse("Path not found");

        MavenController.deleteRecursively(target);
        garbageCollector.pruneEmptyDirectories(target.getParent(), repositoryRoot);
        repositoryService.invalidate(repositoryName);

        LOGGER.info("Deleted {}/{} by {}", repositoryName, cleaned, ctx.ip());
        ctx.status(204);
    }

    /** Collapses separators and strips leading and trailing slashes. */
    private static String normalize(String path) {
        String cleaned = path.replace('\\', '/').replaceAll("/+", "/");
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    /** Keeps an uploaded filename to a single path segment. */
    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) throw new BadRequestResponse("File has no name");
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            throw new BadRequestResponse("Invalid file name");
        }
        return name;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
