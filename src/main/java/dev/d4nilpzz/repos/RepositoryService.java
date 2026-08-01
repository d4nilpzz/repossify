package dev.d4nilpzz.repos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.d4nilpzz.Repossify;
import dev.d4nilpzz.http.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/**
 * Owns everything about repositories: their configuration in {@code page.json}, where they
 * live on disk, and the browsable tree shown by the dashboard.
 * <p>
 * The tree used to be walked from scratch on every page load, which is O(number of files)
 * per request. It is cached here and invalidated whenever a deploy or delete touches a
 * repository.
 */
public class RepositoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Cache<String, List<RepositoryData.TreeNode>> treeCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(64)
            .build();

    private final Path workingDirectory;

    private volatile RepositoryData cachedConfig;
    private volatile long cachedConfigStamp = -1;

    public RepositoryService(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /** Uses the process-wide working directory. Tests inject their own instead. */
    public RepositoryService() {
        this(Repossify.WORKING_DIR);
    }

    public Path root() {
        return workingDirectory.resolve("repositories");
    }

    public Path pageConfigPath() {
        return workingDirectory.resolve("page.json");
    }

    /* ===================== configuration ===================== */

    /**
     * Reads {@code page.json}, reusing the parsed result until the file changes on disk.
     * Every repository is normalized so callers never have to handle the legacy
     * {@code isPrivate} flag themselves.
     */
    public RepositoryData config() throws IOException {
        Path path = pageConfigPath();
        long stamp = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;

        RepositoryData current = cachedConfig;
        if (current != null && stamp == cachedConfigStamp) return current;

        RepositoryData loaded = MAPPER.readValue(path.toFile(), RepositoryData.class);
        if (loaded.repositories == null) loaded.repositories = new ArrayList<>();
        loaded.repositories.forEach(RepositoryData.Repository::normalize);

        cachedConfig = loaded;
        cachedConfigStamp = stamp;
        return loaded;
    }

    public void saveConfig(RepositoryData data) throws IOException {
        if (data.repositories != null) {
            data.repositories.forEach(repo -> {
                repo.normalize();
                repo.tree = null; // the tree is derived from disk, never persisted
            });
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(pageConfigPath().toFile(), data);
        cachedConfigStamp = -1;
        invalidateAll();
    }

    public List<RepositoryData.Repository> repositories() {
        try {
            return config().repositories;
        } catch (IOException e) {
            LOGGER.error("Cannot read page.json: {}", e.getMessage());
            return List.of();
        }
    }

    public Optional<RepositoryData.Repository> find(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        return repositories().stream().filter(repo -> name.equals(repo.name)).findFirst();
    }

    /**
     * Visibility of a repository. Unknown repositories are treated as PRIVATE so a
     * directory that exists on disk but is not declared in {@code page.json} is never
     * served anonymously.
     */
    public Visibility visibilityOf(String name) {
        return find(name).map(RepositoryData.Repository::resolvedVisibility).orElse(Visibility.PRIVATE);
    }

    public boolean redeploymentAllowed(String name) {
        return find(name).map(repo -> repo.redeployment).orElse(false);
    }

    /* ===================== paths ===================== */

    /** Resolves {@code /repo/<name>/<path>} style input to a real file, or empty when unsafe. */
    public Optional<Path> resolve(String relativePath) {
        Path resolved = PathSafety.resolve(root(), relativePath);
        return Optional.ofNullable(resolved);
    }

    public Optional<Path> repositoryPath(String name) {
        return Optional.ofNullable(PathSafety.resolveChild(root(), name));
    }

    /** First path segment of {@code com/foo/bar.jar}-style input, which is the repository name. */
    public static String repositoryNameOf(String relativePath) {
        if (relativePath == null) return null;
        String cleaned = relativePath.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) return null;
        int slash = cleaned.indexOf('/');
        return slash < 0 ? cleaned : cleaned.substring(0, slash);
    }

    /* ===================== tree ===================== */

    /** Browsable tree of a repository, cached until the repository is written to. */
    public List<RepositoryData.TreeNode> tree(String repositoryName) {
        return treeCache.get(repositoryName, name -> {
            Path base = PathSafety.resolveChild(root(), name);
            if (base == null || !Files.isDirectory(base)) return List.of();
            try {
                return buildTree(base, "/" + name);
            } catch (IOException e) {
                LOGGER.warn("Cannot walk repository {}: {}", name, e.getMessage());
                return List.of();
            }
        });
    }

    private List<RepositoryData.TreeNode> buildTree(Path directory, String basePath) throws IOException {
        List<RepositoryData.TreeNode> nodes = new ArrayList<>();

        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> children = stream
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            for (Path path : children) {
                RepositoryData.TreeNode node = new RepositoryData.TreeNode();
                node.name = path.getFileName().toString();
                node.path = basePath + "/" + node.name;

                if (Files.isDirectory(path)) {
                    node.type = "directory";
                    node.children = buildTree(path, node.path);
                } else {
                    node.type = "file";
                    node.size = Files.size(path);
                    if (node.name.endsWith(".jar") || node.name.endsWith(".pom")) {
                        node.version = path.getParent().getFileName().toString();
                    }
                }
                nodes.add(node);
            }
        }
        return nodes;
    }

    /** Drops the cached tree for one repository after a deploy or delete. */
    public void invalidate(String repositoryName) {
        if (repositoryName == null) {
            invalidateAll();
            return;
        }
        treeCache.invalidate(repositoryName);
    }

    public void invalidateAll() {
        treeCache.invalidateAll();
    }

    /** Total bytes stored by a repository. Used for quota enforcement and statistics. */
    public long sizeOf(String repositoryName) {
        Path base = PathSafety.resolveChild(root(), repositoryName);
        if (base == null || !Files.isDirectory(base)) return 0L;

        try (Stream<Path> stream = Files.walk(base)) {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Creates the on-disk directory for every declared repository, and removes directories
     * for repositories that were deleted from the configuration while still empty.
     */
    public void syncDirectories(Set<String> previousNames) throws IOException {
        Set<String> current = new HashSet<>();
        for (RepositoryData.Repository repo : repositories()) {
            if (repo.name == null || repo.name.isBlank()) continue;
            current.add(repo.name);
            Path path = PathSafety.resolveChild(root(), repo.name);
            if (path != null && Files.notExists(path)) Files.createDirectories(path);
        }

        for (String name : previousNames) {
            if (current.contains(name)) continue;
            Path path = PathSafety.resolveChild(root(), name);
            if (path == null || Files.notExists(path)) continue;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                if (!entries.iterator().hasNext()) Files.delete(path);
            }
        }
    }
}
