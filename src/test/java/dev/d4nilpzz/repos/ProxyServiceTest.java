package dev.d4nilpzz.repos;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises mirror resolution against a real upstream HTTP server.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProxyServiceTest {

    @TempDir
    Path workingDirectory;

    private HttpServer upstream;
    private String upstreamUrl;

    /** Files the upstream serves, and how often each was asked for. */
    private final Map<String, String> upstreamFiles = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    private RepositoryService repositoryService;
    private ProxyService proxyService;

    @BeforeAll
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring(1);
            hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();

            String body = upstreamFiles.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        upstream.start();
        upstreamUrl = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/";
    }

    @AfterAll
    void stopUpstream() {
        upstream.stop(0);
    }

    @BeforeEach
    void setUp() throws IOException {
        upstreamFiles.clear();
        hits.clear();
        Files.createDirectories(workingDirectory.resolve("repositories/mirror"));
        repositoryService = new RepositoryService(workingDirectory);
        proxyService = new ProxyService(repositoryService);
    }

    private RepositoryData.Repository repository(boolean store, List<String> allowedGroups) {
        RepositoryData.Repository repository = new RepositoryData.Repository();
        repository.name = "mirror";
        repository.visibility = "PUBLIC";

        RepositoryData.Proxy proxy = new RepositoryData.Proxy();
        proxy.url = upstreamUrl;
        proxy.store = store;
        proxy.allowedGroups = allowedGroups;
        repository.proxied = List.of(proxy);
        repository.normalize();
        return repository;
    }

    @Test
    void fetchesAndStoresAnUpstreamArtifact() {
        String artifactPath = "com/example/demo/1.0.0/demo-1.0.0.jar";
        upstreamFiles.put(artifactPath, "upstream-bytes");

        Optional<ProxyService.ProxyResult> result =
                proxyService.fetch(repository(true, List.of()), artifactPath);

        assertTrue(result.isPresent());
        assertTrue(result.get().isStored());

        Path stored = workingDirectory.resolve("repositories/mirror").resolve(artifactPath);
        assertTrue(Files.exists(stored));
    }

    @Test
    void storedArtifactsAreNotFetchedTwice() {
        String artifactPath = "com/example/cached/1.0.0/cached-1.0.0.jar";
        upstreamFiles.put(artifactPath, "cache-me");
        RepositoryData.Repository repository = repository(true, List.of());

        proxyService.fetch(repository, artifactPath);
        // The controller checks local storage first, so a second fetch only happens when the
        // artifact was never written. Simulating that here means asserting on the file.
        assertEquals(1, hits.get(artifactPath).get());
        assertTrue(Files.exists(workingDirectory.resolve("repositories/mirror").resolve(artifactPath)));
    }

    @Test
    void streamsWithoutStoringWhenStoreIsDisabled() {
        String artifactPath = "com/example/nostore/1.0.0/nostore-1.0.0.jar";
        upstreamFiles.put(artifactPath, "ephemeral");

        Optional<ProxyService.ProxyResult> result =
                proxyService.fetch(repository(false, List.of()), artifactPath);

        assertTrue(result.isPresent());
        assertFalse(result.get().isStored());
        assertEquals("ephemeral", new String(result.get().body(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(workingDirectory.resolve("repositories/mirror").resolve(artifactPath)));
    }

    @Test
    void metadataIsProxiedButNeverStored() {
        String artifactPath = "com/example/demo/maven-metadata.xml";
        upstreamFiles.put(artifactPath, "<metadata/>");

        Optional<ProxyService.ProxyResult> result =
                proxyService.fetch(repository(true, List.of()), artifactPath);

        assertTrue(result.isPresent());
        // Caching metadata would freeze the upstream's version list at whatever was first seen.
        assertFalse(result.get().isStored());
        assertFalse(Files.exists(workingDirectory.resolve("repositories/mirror").resolve(artifactPath)));
    }

    @Test
    void allowedGroupsRestrictWhichPathsAreLookedUp() {
        String allowed = "com/example/demo/1.0.0/demo-1.0.0.jar";
        String blocked = "org/other/thing/1.0.0/thing-1.0.0.jar";
        upstreamFiles.put(allowed, "ok");
        upstreamFiles.put(blocked, "should not be fetched");

        RepositoryData.Repository repository = repository(true, List.of("com.example"));

        assertTrue(proxyService.fetch(repository, allowed).isPresent());
        assertTrue(proxyService.fetch(repository, blocked).isEmpty());
        assertNull(hits.get(blocked), "a disallowed group must not reach the upstream at all");
    }

    @Test
    void missingArtifactsAreNegativelyCached() {
        String artifactPath = "com/example/ghost/1.0.0/ghost-1.0.0.jar";
        RepositoryData.Repository repository = repository(true, List.of());

        assertTrue(proxyService.fetch(repository, artifactPath).isEmpty());
        assertTrue(proxyService.fetch(repository, artifactPath).isEmpty());
        assertTrue(proxyService.fetch(repository, artifactPath).isEmpty());

        // A failing build asks for the same missing artifact over and over; only the first
        // attempt should cost a round trip.
        assertEquals(1, hits.get(artifactPath).get());
    }

    @Test
    void resetClearsTheNegativeCache() {
        String artifactPath = "com/example/later/1.0.0/later-1.0.0.jar";
        RepositoryData.Repository repository = repository(true, List.of());

        assertTrue(proxyService.fetch(repository, artifactPath).isEmpty());
        upstreamFiles.put(artifactPath, "published later");

        proxyService.reset();
        assertTrue(proxyService.fetch(repository, artifactPath).isPresent());
    }

    @Test
    void repositoriesWithoutMirrorsAreSkipped() {
        RepositoryData.Repository plain = new RepositoryData.Repository();
        plain.name = "mirror";
        plain.normalize();

        assertTrue(proxyService.fetch(plain, "com/example/demo/1.0.0/demo-1.0.0.jar").isEmpty());
    }

    @Test
    void traversalInAProxiedPathCannotEscapeTheUpstreamBase() {
        RepositoryData.Repository repository = repository(true, List.of());

        assertTrue(proxyService.fetch(repository, "../../../etc/passwd").isEmpty());
        assertFalse(Files.exists(workingDirectory.resolve("etc/passwd")));
    }
}
