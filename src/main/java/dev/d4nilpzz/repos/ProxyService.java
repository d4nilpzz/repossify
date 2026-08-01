package dev.d4nilpzz.repos;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.d4nilpzz.http.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Resolves artifacts from upstream repositories when they are missing locally, which is what
 * lets a build point at a single Repossify URL instead of listing Maven Central and every
 * other remote separately.
 * <p>
 * Fetched artifacts are written into the local repository when the mirror has
 * {@code store} enabled, so the upstream is consulted once per artifact. Metadata is never
 * stored: a cached {@code maven-metadata.xml} would freeze the upstream's version list.
 */
public class ProxyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyService.class);
    private static final String USER_AGENT = "Repossify";

    private final RepositoryService repositoryService;
    private final HttpClient client;

    /**
     * Paths recently known to be absent upstream. A failing build asks for the same missing
     * artifact repeatedly; without this every attempt pays a full round trip to every mirror.
     */
    private final Cache<String, Boolean> misses = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10_000)
            .build();

    public ProxyService(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Attempts to satisfy {@code artifactPath} (relative to the repository root, without the
     * repository name) from one of the repository's mirrors.
     *
     * @return the resolved artifact, or empty when no mirror has it
     */
    public Optional<ProxyResult> fetch(RepositoryData.Repository repository, String artifactPath) {
        if (repository == null || repository.proxied == null || repository.proxied.isEmpty()) {
            return Optional.empty();
        }

        String cleaned = artifactPath.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) return Optional.empty();

        String missKey = repository.name + "/" + cleaned;
        if (misses.getIfPresent(missKey) != null) return Optional.empty();

        boolean isMetadata = cleaned.endsWith(MetadataService.METADATA_FILE)
                || cleaned.contains(MetadataService.METADATA_FILE + ".");

        for (RepositoryData.Proxy proxy : repository.proxied) {
            if (proxy == null || proxy.url == null || proxy.url.isBlank()) continue;
            if (!isGroupAllowed(proxy, cleaned)) continue;

            try {
                Optional<ProxyResult> result = fetchFrom(repository, proxy, cleaned, isMetadata);
                if (result.isPresent()) return result;
            } catch (Exception e) {
                LOGGER.warn("Mirror {} failed for {}: {}", proxy.url, cleaned, e.getMessage());
            }
        }

        misses.put(missKey, Boolean.TRUE);
        return Optional.empty();
    }

    private Optional<ProxyResult> fetchFrom(RepositoryData.Repository repository,
                                            RepositoryData.Proxy proxy,
                                            String artifactPath,
                                            boolean isMetadata) throws IOException, InterruptedException {

        String base = proxy.url.endsWith("/") ? proxy.url : proxy.url + "/";
        URI uri = URI.create(base).resolve(artifactPath);

        // Guard against a mirror URL plus a crafted path escaping the upstream base.
        if (!uri.toString().startsWith(base)) {
            LOGGER.warn("Refusing proxied request that escapes {}: {}", base, uri);
            return Optional.empty();
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMillis(Math.max(1_000, proxy.readTimeout)))
                .GET()
                .build();

        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) return Optional.empty();

            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("application/octet-stream");

            if (!proxy.store || isMetadata) {
                return Optional.of(ProxyResult.streamed(body.readAllBytes(), contentType));
            }

            Path stored = store(repository, artifactPath, body);
            if (stored == null) {
                // The path did not map into local storage, which means it was unsafe.
                LOGGER.warn("Cannot store proxied path {} for repository {}", artifactPath, repository.name);
                return Optional.empty();
            }

            LOGGER.info("Cached {} from {}", artifactPath, proxy.url);
            repositoryService.invalidate(repository.name);
            return Optional.of(ProxyResult.stored(stored));
        }
    }

    /**
     * Writes the upstream body into the local repository. The download lands in a temporary
     * file first and is moved into place afterwards, so a request that arrives mid-download
     * never observes a truncated artifact.
     */
    private Path store(RepositoryData.Repository repository, String artifactPath, InputStream body)
            throws IOException {
        Path repositoryRoot = PathSafety.resolveChild(repositoryService.root(), repository.name);
        if (repositoryRoot == null) return null;

        Path target = PathSafety.resolve(repositoryRoot, artifactPath);
        if (target == null) return null;

        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".repossify-", ".part");
        try {
            Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /**
     * Whether a mirror is allowed to serve this path. {@code allowedGroups} holds groupIds
     * in dotted form; they are matched against the leading directories of the path.
     */
    private boolean isGroupAllowed(RepositoryData.Proxy proxy, String artifactPath) {
        List<String> allowed = proxy.allowedGroups;
        if (allowed == null || allowed.isEmpty()) return true;

        for (String group : allowed) {
            if (group == null || group.isBlank()) continue;
            String prefix = group.trim().replace('.', '/');
            if (!prefix.endsWith("/")) prefix = prefix + "/";
            if (artifactPath.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Clears the negative cache, e.g. after a mirror's configuration changes. */
    public void reset() {
        misses.invalidateAll();
    }

    /**
     * Either a file now present in local storage, or an in-memory body for mirrors that do
     * not store.
     */
    public record ProxyResult(Path storedFile, byte[] body, String contentType) {

        static ProxyResult stored(Path file) {
            return new ProxyResult(file, null, null);
        }

        static ProxyResult streamed(byte[] body, String contentType) {
            return new ProxyResult(null, body, contentType);
        }

        public boolean isStored() {
            return storedFile != null;
        }
    }
}
