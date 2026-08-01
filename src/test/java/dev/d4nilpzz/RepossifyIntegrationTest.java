package dev.d4nilpzz;

import dev.d4nilpzz.auth.TokenService;
import dev.d4nilpzz.config.ConfigService;
import dev.d4nilpzz.config.ServerConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage of the Maven endpoint against a real server, which is the only place
 * the interaction between authentication, path safety and HTTP semantics is observable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepossifyIntegrationTest {

    @TempDir
    static Path workingDirectory;

    private static RepossifyServer server;
    private static HttpClient client;
    private static String baseUrl;

    private static final String MANAGER_SECRET = "manager-secret-value";
    private static final String DEPLOY_SECRET = "deploy-secret-value";
    private static final String READER_SECRET = "reader-secret-value";

    @BeforeAll
    void startServer() throws Exception {
        Files.createDirectories(workingDirectory.resolve("content"));
        Files.createDirectories(workingDirectory.resolve("repositories"));
        Files.writeString(workingDirectory.resolve("page.json"), """
                {
                  "title": "Test",
                  "author": "Test",
                  "group_id": "com.example",
                  "description": "test instance",
                  "avatar_url": "/public/repossify.png",
                  "domain_url": "http://localhost/",
                  "links": [],
                  "repositories": [
                    { "name": "releases",  "path": "/releases",  "visibility": "PUBLIC",  "redeployment": false },
                    { "name": "snapshots", "path": "/snapshots", "visibility": "PUBLIC",  "redeployment": true  },
                    { "name": "private",   "path": "/private",   "visibility": "PRIVATE", "redeployment": true  }
                  ]
                }
                """);

        ServerConfig config = new ServerConfig();
        config.hostname = "127.0.0.1";
        config.port = 0; // let the OS choose
        config.garbageCollectorIntervalMinutes = 0;

        ConfigService configService = new ConfigService(workingDirectory);
        server = new RepossifyServer(workingDirectory, config, configService);

        TokenService tokens = server.tokenService();
        tokens.createToken("manager", List.of("M"), MANAGER_SECRET);

        // Scoped exactly at one artifact: this is the shape of a CI deploy token.
        tokens.createToken("deployer", List.of(), DEPLOY_SECRET);
        tokens.addRouteToToken("deployer", "/repo/releases/com/example/demo", "w");
        tokens.addRouteToToken("deployer", "/repo/snapshots/com/example/demo", "w");

        tokens.createToken("reader", List.of(), READER_SECRET);
        tokens.addRouteToToken("reader", "/repo/private", "r");

        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterAll
    void stopServer() {
        if (server != null) server.stop();
    }

    /* ===================== helpers ===================== */

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path));
    }

    private static String basic(String name, String secret) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((name + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body, String authorization)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = request(path).PUT(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) builder.header("Authorization", authorization);
        return send(builder.build());
    }

    private HttpResponse<String> get(String path, String authorization)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = request(path).GET();
        if (authorization != null) builder.header("Authorization", authorization);
        return send(builder.build());
    }

    /* ===================== authentication ===================== */

    @Test
    @Order(1)
    void anonymousDeployIsRejectedAndChallenged() throws Exception {
        HttpResponse<String> response =
                put("/repo/releases/com/example/demo/1.0.0/demo-1.0.0.jar", "jar", null);

        assertEquals(401, response.statusCode());
        // Without this header Maven reports a bare 401 instead of retrying with the
        // credentials from settings.xml.
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic"),
                "a 401 must challenge so Maven retries with credentials");
    }

    @Test
    void scopedDeployTokenCanDeployToItsOwnArtifact() throws Exception {
        // Regression: the PUT handler checked the literal prefix "/repo" before the real
        // path, which no scoped route can match, so only managers could ever deploy.
        HttpResponse<String> response = put(
                "/repo/releases/com/example/demo/2.0.0/demo-2.0.0.jar",
                "scoped-deploy",
                basic("deployer", DEPLOY_SECRET));

        assertEquals(201, response.statusCode(), "a scoped write token must be able to deploy");
        assertTrue(Files.exists(workingDirectory.resolve(
                "repositories/releases/com/example/demo/2.0.0/demo-2.0.0.jar")));
    }

    @Test
    void deployTokenCannotWriteOutsideItsScope() throws Exception {
        HttpResponse<String> response = put(
                "/repo/releases/com/example/other/1.0.0/other-1.0.0.jar",
                "nope",
                basic("deployer", DEPLOY_SECRET));

        assertEquals(403, response.statusCode());
    }

    @Test
    void bearerTokensAlsoWork() throws Exception {
        HttpResponse<String> response = put(
                "/repo/releases/com/example/bearer/1.0.0/bearer-1.0.0.jar",
                "bearer",
                "Bearer " + MANAGER_SECRET);

        assertEquals(201, response.statusCode());
    }

    @Test
    void invalidCredentialsAreRejected() throws Exception {
        HttpResponse<String> response = put(
                "/repo/releases/com/example/demo/9.9.9/demo-9.9.9.jar",
                "x",
                basic("manager", "wrong-secret"));

        assertEquals(401, response.statusCode());
    }

    /* ===================== visibility ===================== */

    @Test
    void publicArtifactsResolveAnonymously() throws Exception {
        put("/repo/releases/com/example/pub/1.0.0/pub-1.0.0.jar", "public-bytes",
                basic("manager", MANAGER_SECRET));

        HttpResponse<String> response = get("/repo/releases/com/example/pub/1.0.0/pub-1.0.0.jar", null);

        assertEquals(200, response.statusCode());
        assertEquals("public-bytes", response.body());
    }

    @Test
    void privateRepositoriesRequireAReadToken() throws Exception {
        put("/repo/private/com/example/secret/1.0.0/secret-1.0.0.jar", "classified",
                basic("manager", MANAGER_SECRET));

        assertEquals(401,
                get("/repo/private/com/example/secret/1.0.0/secret-1.0.0.jar", null).statusCode());

        HttpResponse<String> authorized = get(
                "/repo/private/com/example/secret/1.0.0/secret-1.0.0.jar",
                basic("reader", READER_SECRET));

        // Regression: only WRITE was ever accepted, so a read-only token could not resolve
        // anything from a private repository.
        assertEquals(200, authorized.statusCode());
        assertEquals("classified", authorized.body());
    }

    @Test
    void readTokensCannotDeploy() throws Exception {
        HttpResponse<String> response = put(
                "/repo/private/com/example/secret/1.0.1/secret-1.0.1.jar",
                "nope",
                basic("reader", READER_SECRET));

        assertEquals(403, response.statusCode());
    }

    @Test
    void privateRepositoriesAreHiddenFromTheRootListing() throws Exception {
        String anonymous = get("/repo/", null).body();
        assertFalse(anonymous.contains("private"), "a private repository must not be listed anonymously");
        assertTrue(anonymous.contains("releases"));

        String asManager = get("/repo/", basic("manager", MANAGER_SECRET)).body();
        assertTrue(asManager.contains("private"), "manager listing was: " + asManager);
    }

    /* ===================== deployment policy ===================== */

    @Test
    void releaseRedeploymentIsRefused() throws Exception {
        String path = "/repo/releases/com/example/immutable/1.0.0/immutable-1.0.0.jar";
        assertEquals(201, put(path, "first", basic("manager", MANAGER_SECRET)).statusCode());

        HttpResponse<String> second = put(path, "second", basic("manager", MANAGER_SECRET));
        assertEquals(409, second.statusCode());
        assertEquals("first", get(path, null).body(), "the published artifact must be untouched");
    }

    @Test
    void snapshotsMayAlwaysBeRepublished() throws Exception {
        String path = "/repo/snapshots/com/example/demo/1.0.0-SNAPSHOT/demo-1.0.0-SNAPSHOT.jar";
        assertEquals(201, put(path, "build-1", basic("deployer", DEPLOY_SECRET)).statusCode());
        assertEquals(201, put(path, "build-2", basic("deployer", DEPLOY_SECRET)).statusCode());
        assertEquals("build-2", get(path, null).body());
    }

    @Test
    void deployingToAnUndeclaredRepositoryIsRefused() throws Exception {
        HttpResponse<String> response = put(
                "/repo/nonexistent/com/example/demo/1.0.0/demo-1.0.0.jar",
                "x",
                basic("manager", MANAGER_SECRET));

        assertEquals(404, response.statusCode());
        assertFalse(Files.exists(workingDirectory.resolve("repositories/nonexistent")),
                "an undeclared repository must not be created by a deploy");
    }

    /* ===================== path safety ===================== */

    @Test
    void traversalInAnUploadPathIsRefused() throws Exception {
        HttpResponse<String> response = put(
                "/repo/releases/../../escaped.jar", "pwned", basic("manager", MANAGER_SECRET));

        assertTrue(response.statusCode() >= 400, "traversal must not succeed");
        assertFalse(Files.exists(workingDirectory.resolve("escaped.jar")));
        assertFalse(Files.exists(workingDirectory.getParent().resolve("escaped.jar")));
    }

    @Test
    void traversalInTheDeleteApiIsRefused() throws Exception {
        Path victim = workingDirectory.resolve("victim.txt");
        Files.writeString(victim, "do not delete me");

        HttpResponse<String> response = send(request(
                "/api/file/delete?repo=releases&path=../../victim.txt")
                .header("Authorization", basic("manager", MANAGER_SECRET))
                .DELETE()
                .build());

        assertTrue(response.statusCode() >= 400);
        assertTrue(Files.exists(victim), "a delete must never reach outside the repository");
    }

    /* ===================== HTTP semantics ===================== */

    @Test
    void conditionalRequestsAvoidRedownloading() throws Exception {
        String path = "/repo/releases/com/example/cond/1.0.0/cond-1.0.0.jar";
        put(path, "cacheable", basic("manager", MANAGER_SECRET));

        HttpResponse<String> first = get(path, null);
        assertEquals(200, first.statusCode());

        String etag = first.headers().firstValue("ETag").orElse(null);
        assertNotNull(etag, "an artifact must carry a validator");
        assertTrue(first.headers().firstValue("Last-Modified").isPresent());
        assertEquals("bytes", first.headers().firstValue("Accept-Ranges").orElse(null));

        HttpResponse<String> revalidated =
                send(request(path).header("If-None-Match", etag).GET().build());

        assertEquals(304, revalidated.statusCode(), "a matching ETag must produce 304");
        assertTrue(revalidated.body().isEmpty());
    }

    @Test
    void rangeRequestsAreHonoured() throws Exception {
        String path = "/repo/releases/com/example/ranged/1.0.0/ranged-1.0.0.jar";
        put(path, "0123456789", basic("manager", MANAGER_SECRET));

        HttpResponse<String> response =
                send(request(path).header("Range", "bytes=2-5").GET().build());

        assertEquals(206, response.statusCode());
        assertEquals("2345", response.body());
        assertEquals("bytes 2-5/10", response.headers().firstValue("Content-Range").orElse(null));
    }

    @Test
    void contentLengthIsAlwaysPresent() throws Exception {
        String path = "/repo/releases/com/example/sized/1.0.0/sized-1.0.0.jar";
        put(path, "0123456789", basic("manager", MANAGER_SECRET));

        HttpResponse<String> response = get(path, null);
        assertEquals("10", response.headers().firstValue("Content-Length").orElse(null));
    }

    @Test
    void headReturnsMetadataWithoutABody() throws Exception {
        String path = "/repo/releases/com/example/headed/1.0.0/headed-1.0.0.jar";
        put(path, "abcdef", basic("manager", MANAGER_SECRET));

        HttpResponse<String> response = send(request(path).method("HEAD",
                HttpRequest.BodyPublishers.noBody()).build());

        assertEquals(200, response.statusCode());
        assertEquals("6", response.headers().firstValue("Content-Length").orElse(null));
        assertTrue(response.body().isEmpty());
    }

    /**
     * A gzip-capable client is the normal case: Maven always sends {@code Accept-Encoding}.
     * Compressing the body after {@code Content-Length} was set makes the declared size
     * disagree with what is actually sent, and Maven aborts with "Premature end of
     * Content-Length delimited message body".
     */
    @Test
    void gzipCapableClientsReceiveIntactArtifacts() throws Exception {
        // Over the 1500 byte threshold below which nothing is compressed at all.
        String payload = "0123456789abcdef".repeat(400);
        String path = "/repo/releases/com/example/big/1.0.0/big-1.0.0.jar";
        assertEquals(201, put(path, payload, basic("manager", MANAGER_SECRET)).statusCode());

        assertEquals(payload, fetchAcceptingGzip(path));
    }

    @Test
    void gzipCapableClientsReceiveIntactTextArtifacts() throws Exception {
        // application/xml is compressible, so this is the path where the body really is
        // rewritten on the way out.
        String payload = "<project>" + "<!-- padding -->".repeat(300) + "</project>";
        String path = "/repo/releases/com/example/big/1.0.0/big-1.0.0.pom";
        assertEquals(201, put(path, payload, basic("manager", MANAGER_SECRET)).statusCode());

        assertEquals(payload, fetchAcceptingGzip(path));
    }

    /**
     * Fetches a path the way a real Maven client does, and verifies that any declared
     * {@code Content-Length} matches the bytes actually delivered.
     */
    private String fetchAcceptingGzip(String path) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(
                request(path).header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        byte[] body = response.body();

        response.headers().firstValue("Content-Length").ifPresent(declared ->
                assertEquals(Long.parseLong(declared), body.length,
                        "Content-Length must match the bytes on the wire"));

        boolean gzipped = response.headers().firstValue("Content-Encoding")
                .map(encoding -> encoding.toLowerCase().contains("gzip"))
                .orElse(false);

        if (!gzipped) return new String(body, StandardCharsets.UTF_8);

        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void missingArtifactsReturnAJsonError() throws Exception {
        HttpResponse<String> response = get("/repo/releases/com/example/ghost/1.0.0/ghost-1.0.0.jar", null);

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("\"status\":404"), "errors should be uniform JSON");
    }

    /* ===================== metadata and checksums ===================== */

    @Test
    void checksumsAreGeneratedForDeployedArtifacts() throws Exception {
        String path = "/repo/releases/com/example/summed/1.0.0/summed-1.0.0.jar";
        put(path, "checksum me", basic("manager", MANAGER_SECRET));

        assertEquals(200, get(path + ".sha1", null).statusCode());
        assertEquals(200, get(path + ".md5", null).statusCode());
    }

    @Test
    void metadataIsGeneratedWhenTheClientDoesNotSupplyIt() throws Exception {
        put("/repo/releases/com/example/meta/1.0.0/meta-1.0.0.jar", "a", basic("manager", MANAGER_SECRET));

        HttpResponse<String> metadata =
                get("/repo/releases/com/example/meta/maven-metadata.xml", null);

        assertEquals(200, metadata.statusCode());
        assertTrue(metadata.body().contains("<artifactId>meta</artifactId>"));
        assertTrue(metadata.body().contains("<version>1.0.0</version>"));
    }

    @Test
    void clientSuppliedMetadataIsNotOverwritten() throws Exception {
        String metadataPath = "/repo/releases/com/example/owned/maven-metadata.xml";
        put("/repo/releases/com/example/owned/1.0.0/owned-1.0.0.jar", "a", basic("manager", MANAGER_SECRET));

        String clientMetadata = "<metadata><artifactId>owned</artifactId><!--client--></metadata>";
        assertEquals(201, put(metadataPath, clientMetadata, basic("manager", MANAGER_SECRET)).statusCode());

        // A second deploy must leave the client's merged metadata alone; regenerating it
        // here is what used to produce snapshot metadata pointing at absent files.
        put("/repo/releases/com/example/owned/2.0.0/owned-2.0.0.jar", "b", basic("manager", MANAGER_SECRET));

        assertEquals(clientMetadata, get(metadataPath, null).body());
    }

    @Test
    void noPomIsInventedForADeployedJar() throws Exception {
        put("/repo/releases/com/example/nopom/1.0.0/nopom-1.0.0.jar", "jar-only",
                basic("manager", MANAGER_SECRET));

        // A generated POM declares no dependencies, so consumers compile and then fail at
        // runtime. Deployment must never fabricate one.
        assertEquals(404, get("/repo/releases/com/example/nopom/1.0.0/nopom-1.0.0.pom", null).statusCode());
    }

    /* ===================== listings, search and deletion ===================== */

    @Test
    void directoriesAreBrowsable() throws Exception {
        put("/repo/releases/com/example/listed/1.0.0/listed-1.0.0.jar", "x", basic("manager", MANAGER_SECRET));

        HttpResponse<String> response = get("/repo/releases/com/example/listed/1.0.0/", null);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("listed-1.0.0.jar"));
    }

    @Test
    void searchFindsDeployedArtifacts() throws Exception {
        put("/repo/releases/com/example/searchable/1.0.0/searchable-1.0.0.jar", "x",
                basic("manager", MANAGER_SECRET));

        HttpResponse<String> response = get("/api/maven/search?q=searchable", null);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("searchable"));
    }

    @Test
    void searchDoesNotLeakPrivateArtifacts() throws Exception {
        put("/repo/private/com/example/hidden/1.0.0/hidden-1.0.0.jar", "x", basic("manager", MANAGER_SECRET));

        // The response echoes the query, so the result count is what actually shows whether
        // the private artifact was exposed.
        assertTrue(get("/api/maven/search?q=hidden", null).body().contains("\"count\":0"),
                "an anonymous search must not reach into a private repository");
        assertTrue(get("/api/maven/search?q=hidden", basic("manager", MANAGER_SECRET)).body()
                .contains("\"artifactId\":\"hidden\""));
    }

    @Test
    void deletingAnArtifactRemovesIt() throws Exception {
        String path = "/repo/releases/com/example/doomed/1.0.0/doomed-1.0.0.jar";
        put(path, "bye", basic("manager", MANAGER_SECRET));
        assertEquals(200, get(path, null).statusCode());

        HttpResponse<String> deletion = send(request(path)
                .header("Authorization", basic("manager", MANAGER_SECRET))
                .DELETE().build());

        assertEquals(204, deletion.statusCode());
        assertEquals(404, get(path, null).statusCode());
    }

    /* ===================== management API ===================== */

    @Test
    void tokenApiIsManagerOnly() throws Exception {
        assertEquals(401, get("/api/tokens", null).statusCode());
        assertEquals(403, get("/api/tokens", basic("deployer", DEPLOY_SECRET)).statusCode());
        assertEquals(200, get("/api/tokens", basic("manager", MANAGER_SECRET)).statusCode());
    }

    @Test
    void tokensCanBeCreatedAndDeletedOverHttp() throws Exception {
        HttpResponse<String> created = send(request("/api/tokens")
                .header("Authorization", basic("manager", MANAGER_SECRET))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name":"ci-bot","permissions":[],
                         "routes":[{"path":"/repo/releases","permission":"w"}]}
                        """))
                .build());

        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"secret\""), "the plaintext secret is returned once");

        HttpResponse<String> deleted = send(request("/api/tokens/ci-bot")
                .header("Authorization", basic("manager", MANAGER_SECRET))
                .DELETE().build());
        assertEquals(204, deleted.statusCode());
    }

    @Test
    void theLastManagerTokenCannotBeDeleted() throws Exception {
        HttpResponse<String> response = send(request("/api/tokens/manager")
                .header("Authorization", basic("manager", MANAGER_SECRET))
                .DELETE().build());

        assertEquals(409, response.statusCode(), "deleting the only manager would lock everyone out");
    }

    @Test
    void tokenResponsesNeverIncludeTheSecretHash() throws Exception {
        String body = get("/api/tokens", basic("manager", MANAGER_SECRET)).body();

        assertTrue(body.contains("manager"));
        assertFalse(body.contains("$2a$"), "the BCrypt hash must not be serialized");
        assertFalse(body.contains("\"secret\""));
    }

    @Test
    void consoleExecutionIsManagerOnly() throws Exception {
        HttpResponse<String> asDeployer = send(request("/api/console/exec")
                .header("Authorization", basic("deployer", DEPLOY_SECRET))
                .POST(HttpRequest.BodyPublishers.ofString("generate_token evil M"))
                .build());

        // A deploy token reaching the console could mint itself a manager token.
        assertEquals(403, asDeployer.statusCode());
        assertNull(server.tokenService().getTokenByName("evil"));
    }

    @Test
    void configurationUpdatesAreManagerOnly() throws Exception {
        HttpResponse<String> response = send(request("/api/config/update")
                .header("Authorization", basic("deployer", DEPLOY_SECRET))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"title\":\"hijacked\"}"))
                .build());

        assertEquals(403, response.statusCode());
    }

    @Test
    void statisticsAreRecordedForResolvedArtifacts() throws Exception {
        String path = "/repo/releases/com/example/counted/1.0.0/counted-1.0.0.jar";
        put(path, "x", basic("manager", MANAGER_SECRET));
        get(path, null);
        get(path, null);

        server.statisticsService().flush();
        HttpResponse<String> response = get("/api/statistics", basic("manager", MANAGER_SECRET));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("totalDownloads"));
    }
}
