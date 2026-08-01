package dev.d4nilpzz.controllers;

import dev.d4nilpzz.auth.AuthService;
import dev.d4nilpzz.auth.RoutePermission;
import dev.d4nilpzz.http.ErrorResponse;
import dev.d4nilpzz.http.HttpFiles;
import dev.d4nilpzz.repos.*;
import dev.d4nilpzz.utils.ChecksumUtils;
import io.javalin.Javalin;
import io.javalin.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Serves the Maven endpoint at {@code /repo/*}: resolution, deployment and deletion.
 * <p>
 * This is the surface Maven and Gradle talk to, so it is where correctness matters most:
 * conditional requests, ranges, redeployment policy and mirror fallback all live here.
 */
public class MavenController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenController.class);
    private static final String PREFIX = "/repo/";

    private final AuthService authService;
    private final RepositoryService repositoryService;
    private final MetadataService metadataService;
    private final ProxyService proxyService;
    private final StatisticsService statisticsService;
    private final GarbageCollector garbageCollector;

    public MavenController(AuthService authService,
                           RepositoryService repositoryService,
                           MetadataService metadataService,
                           ProxyService proxyService,
                           StatisticsService statisticsService,
                           GarbageCollector garbageCollector) {
        this.authService = authService;
        this.repositoryService = repositoryService;
        this.metadataService = metadataService;
        this.proxyService = proxyService;
        this.statisticsService = statisticsService;
        this.garbageCollector = garbageCollector;
    }

    public void registerRoutes(Javalin app) {
        // Javalin's "*" does not match an empty remainder, so the repository index has to be
        // registered on both forms of the root path rather than relying on /repo/*.
        app.get("/repo", ctx -> renderRepositoryIndex(ctx));
        app.get(PREFIX, ctx -> renderRepositoryIndex(ctx));
        app.get(PREFIX + "*", ctx -> handleRead(ctx, true));
        app.head(PREFIX + "*", ctx -> handleRead(ctx, false));
        app.put(PREFIX + "*", this::handleDeploy);
        app.delete(PREFIX + "*", this::handleDelete);
    }

    /* ===================== resolution ===================== */

    private void handleRead(Context ctx, boolean includeBody) throws IOException {
        String relativePath = relativePathOf(ctx);
        if (relativePath == null) throw new BadRequestResponse("Invalid repository path");

        if (relativePath.isEmpty()) {
            renderRepositoryIndex(ctx);
            return;
        }

        String repositoryName = RepositoryService.repositoryNameOf(relativePath);
        RepositoryData.Repository repository = repositoryService.find(repositoryName)
                .orElseThrow(() -> new NotFoundResponse("Repository '" + repositoryName + "' does not exist"));

        Visibility visibility = repository.resolvedVisibility();
        String routePath = PREFIX + relativePath;

        if (!visibility.allowsAnonymousRead()) {
            authService.require(ctx, routePath, RoutePermission.READ);
        }

        Path target = repositoryService.resolve(relativePath)
                .orElseThrow(() -> new BadRequestResponse("Invalid repository path"));

        if (Files.isDirectory(target)) {
            // Listing exposes more than resolving a known path, so HIDDEN requires a token here.
            if (!visibility.allowsAnonymousListing()) {
                authService.require(ctx, routePath, RoutePermission.READ);
            }
            renderDirectory(ctx, repositoryName, relativePath, target);
            return;
        }

        if (Files.isRegularFile(target)) {
            serveFile(ctx, repositoryName, relativePath, target, includeBody);
            return;
        }

        // Not held locally: try the repository's mirrors before giving up.
        String artifactPath = stripRepositoryName(relativePath);
        Optional<ProxyService.ProxyResult> proxied = proxyService.fetch(repository, artifactPath);

        if (proxied.isPresent()) {
            ProxyService.ProxyResult result = proxied.get();
            if (result.isStored()) {
                serveFile(ctx, repositoryName, relativePath, result.storedFile(), includeBody);
            } else {
                ctx.status(200);
                ctx.contentType(result.contentType());
                ctx.header("Content-Length", String.valueOf(result.body().length));
                if (includeBody) ctx.result(result.body());
            }
            return;
        }

        throw new NotFoundResponse("Artifact not found: " + relativePath);
    }

    private void serveFile(Context ctx, String repositoryName, String relativePath, Path file, boolean includeBody)
            throws IOException {
        if (HttpFiles.handleConditional(ctx, file)) return;

        HttpFiles.serve(ctx, file, includeBody);
        if (includeBody && ctx.status() == HttpStatus.OK) {
            statisticsService.record(repositoryName, stripRepositoryName(relativePath));
        }
    }

    /* ===================== deployment ===================== */

    private void handleDeploy(Context ctx) throws IOException {
        String relativePath = relativePathOf(ctx);
        if (relativePath == null || relativePath.isEmpty()) {
            throw new BadRequestResponse("Invalid repository path");
        }

        String routePath = PREFIX + relativePath;
        authService.require(ctx, routePath, RoutePermission.WRITE);

        String repositoryName = RepositoryService.repositoryNameOf(relativePath);
        RepositoryData.Repository repository = repositoryService.find(repositoryName)
                .orElseThrow(() -> new NotFoundResponse("Repository '" + repositoryName + "' does not exist"));

        Path target = repositoryService.resolve(relativePath)
                .orElseThrow(() -> new BadRequestResponse("Invalid repository path"));

        String filename = target.getFileName().toString();
        boolean isChecksum = MetadataService.isChecksum(filename);
        boolean isMetadata = filename.startsWith(MetadataService.METADATA_FILE);

        if (!repository.redeployment && Files.exists(target) && !isChecksum && !isMetadata) {
            // Snapshots are expected to be republished; released coordinates are not.
            if (!relativePath.contains("-SNAPSHOT")) {
                throw new ConflictResponse(
                        "Redeployment is disabled for '" + repositoryName + "'; " + filename + " already exists");
            }
        }

        // Checked before writing, using the declared body size, so an oversized upload is
        // rejected instead of being written and then rolled back.
        long declaredSize = declaredContentLength(ctx);
        if (!garbageCollector.hasSpaceFor(repository, Math.max(declaredSize, 0))) {
            ctx.status(507).json(ErrorResponse.of(507,
                    "Repository '" + repositoryName + "' is over its storage quota"));
            return;
        }

        Files.createDirectories(target.getParent());
        long written = writeAtomically(ctx, target);

        if (!isChecksum) {
            ChecksumUtils.writeChecksums(target);
        }

        maintainMetadata(target, isMetadata);

        if (repository.preserveSnapshots > 0 && relativePath.contains("-SNAPSHOT")) {
            Path versionDirectory = target.getParent();
            Path artifactDirectory = versionDirectory.getParent();
            if (artifactDirectory != null) {
                garbageCollector.pruneSnapshotVersion(versionDirectory,
                        artifactDirectory.getFileName().toString(), repository.preserveSnapshots);
            }
        }

        repositoryService.invalidate(repositoryName);
        LOGGER.info("Deployed {} ({} bytes) from {}", relativePath, written, ctx.ip());
        ctx.status(201);
    }

    /**
     * Streams the request body to a temporary file and moves it into place, so a concurrent
     * resolution never observes a half-written artifact, and a failed upload leaves the
     * previous version intact.
     *
     * @return bytes written
     */
    private long writeAtomically(Context ctx, Path target) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".repossify-", ".part");
        try {
            long written;
            try (InputStream body = ctx.bodyInputStream()) {
                written = Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return written;
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /**
     * Fills in metadata the client is not managing itself.
     * <p>
     * A client that uploads its own {@code maven-metadata.xml} owns it: it merged the
     * existing versions and knows which timestamped build it just published. Only when no
     * metadata exists — a {@code curl} upload, or a repository populated by copying files —
     * does Repossify write one.
     */
    private void maintainMetadata(Path target, boolean isMetadata) throws IOException {
        if (isMetadata) return; // uploaded verbatim, nothing to do

        Path versionDirectory = target.getParent();
        Path artifactDirectory = versionDirectory == null ? null : versionDirectory.getParent();
        if (artifactDirectory == null) return;

        Path root = repositoryService.root();

        if (versionDirectory.getFileName().toString().endsWith("-SNAPSHOT")) {
            metadataService.ensureSnapshotMetadata(root, versionDirectory);
        }
        metadataService.ensureArtifactMetadata(root, artifactDirectory);
    }

    /* ===================== deletion ===================== */

    private void handleDelete(Context ctx) throws IOException {
        String relativePath = relativePathOf(ctx);
        if (relativePath == null || relativePath.isEmpty()) {
            throw new BadRequestResponse("Invalid repository path");
        }

        authService.require(ctx, PREFIX + relativePath, RoutePermission.WRITE);

        String repositoryName = RepositoryService.repositoryNameOf(relativePath);
        repositoryService.find(repositoryName)
                .orElseThrow(() -> new NotFoundResponse("Repository '" + repositoryName + "' does not exist"));

        Path repositoryRoot = repositoryService.repositoryPath(repositoryName)
                .orElseThrow(() -> new BadRequestResponse("Invalid repository"));

        Path target = repositoryService.resolve(relativePath)
                .orElseThrow(() -> new BadRequestResponse("Invalid repository path"));

        if (target.equals(repositoryRoot)) {
            throw new BadRequestResponse("Refusing to delete the repository root; remove it from the configuration");
        }
        if (!Files.exists(target)) throw new NotFoundResponse("Path not found: " + relativePath);

        deleteRecursively(target);
        garbageCollector.pruneEmptyDirectories(target.getParent(), repositoryRoot);
        repositoryService.invalidate(repositoryName);

        LOGGER.info("Deleted {} by {}", relativePath, ctx.ip());
        ctx.status(204);
    }

    static void deleteRecursively(Path target) throws IOException {
        if (Files.isRegularFile(target)) {
            Files.delete(target);
            return;
        }
        // Reverse order so children are removed before the directories holding them.
        try (Stream<Path> stream = Files.walk(target)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    /* ===================== listings ===================== */

    private void renderRepositoryIndex(Context ctx) {
        List<DirectoryEntry> entries = new ArrayList<>();

        for (RepositoryData.Repository repository : repositoryService.repositories()) {
            Visibility visibility = repository.resolvedVisibility();
            boolean visible = visibility.allowsAnonymousListing()
                    || authService.canAccess(ctx, PREFIX + repository.name, RoutePermission.READ);
            if (!visible) continue;
            entries.add(new DirectoryEntry(repository.name, repository.name + "/", true, null));
        }

        respondWithListing(ctx, "/", entries);
    }

    private void renderDirectory(Context ctx, String repositoryName, String relativePath, Path directory)
            throws IOException {
        List<DirectoryEntry> entries = new ArrayList<>();

        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> children = stream
                    .sorted(Comparator.comparing((Path path) -> !Files.isDirectory(path))
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();

            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.startsWith(".repossify-")) continue; // in-flight upload
                boolean directoryEntry = Files.isDirectory(child);
                entries.add(new DirectoryEntry(name, name + (directoryEntry ? "/" : ""),
                        directoryEntry, directoryEntry ? null : Files.size(child)));
            }
        }

        respondWithListing(ctx, "/" + relativePath, entries);
    }

    /**
     * Directory listings are served as HTML for browsers and JSON for tooling. Maven itself
     * never lists directories, but Gradle's Ivy-style layouts and humans both do.
     */
    private void respondWithListing(Context ctx, String displayPath, List<DirectoryEntry> entries) {
        String accept = Optional.ofNullable(ctx.header("Accept")).orElse("");
        if (accept.contains("application/json")) {
            ctx.json(java.util.Map.of("path", displayPath, "entries", entries));
            return;
        }

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<title>Index of ").append(escapeHtml(displayPath)).append("</title>")
                .append("<style>body{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;margin:2rem;}")
                .append("a{text-decoration:none;}a:hover{text-decoration:underline;}")
                .append("td{padding:.15rem 1.5rem .15rem 0;}</style></head><body>")
                .append("<h2>Index of ").append(escapeHtml(displayPath)).append("</h2><table>");

        if (!"/".equals(displayPath)) {
            html.append("<tr><td><a href=\"../\">../</a></td><td></td></tr>");
        }
        for (DirectoryEntry entry : entries) {
            html.append("<tr><td><a href=\"").append(escapeHtml(entry.href())).append("\">")
                    .append(escapeHtml(entry.name())).append(entry.directory() ? "/" : "")
                    .append("</a></td><td>")
                    .append(entry.size() == null ? "-" : formatSize(entry.size()))
                    .append("</td></tr>");
        }
        html.append("</table></body></html>");

        ctx.contentType("text/html").result(html.toString());
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f kB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /* ===================== helpers ===================== */

    /** Everything after {@code /repo/}, or null when the request is not under that prefix. */
    private String relativePathOf(Context ctx) {
        String path = ctx.path();
        if (path.equals("/repo")) return "";
        if (!path.startsWith(PREFIX)) return null;
        return decodePath(path.substring(PREFIX.length()));
    }

    /**
     * Percent-decodes a URL path segment.
     * <p>
     * {@link java.net.URLDecoder} is not usable here: it decodes {@code +} as a space, which
     * is correct for query strings and wrong for paths, and Maven versions such as
     * {@code 1.0+build3} do occur.
     */
    static String decodePath(String raw) {
        if (raw.indexOf('%') < 0) return raw;

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int high = Character.digit(raw.charAt(i + 1), 16);
                int low = Character.digit(raw.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            if (c < 0x80) {
                out.write(c);
            } else {
                // Already-decoded non-ASCII: re-encode so the byte stream stays valid UTF-8.
                byte[] encoded = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(encoded, 0, encoded.length);
            }
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long declaredContentLength(Context ctx) {
        String header = ctx.header("Content-Length");
        if (header == null) return -1;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Drops the leading repository name, leaving the path as the upstream would know it. */
    private static String stripRepositoryName(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash < 0 ? "" : relativePath.substring(slash + 1);
    }

    private record DirectoryEntry(String name, String href, boolean directory, Long size) {
    }
}
