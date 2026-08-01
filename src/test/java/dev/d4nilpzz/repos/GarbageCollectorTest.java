package dev.d4nilpzz.repos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GarbageCollectorTest {

    @TempDir
    Path root;

    @Test
    void parsesQuotaUnits() {
        assertEquals(1024L, GarbageCollector.parseQuota("1KB"));
        assertEquals(10L * 1024 * 1024, GarbageCollector.parseQuota("10MB"));
        assertEquals(2L * 1024 * 1024 * 1024, GarbageCollector.parseQuota("2GB"));
        assertEquals(500L, GarbageCollector.parseQuota("500"));
        assertEquals(1536L, GarbageCollector.parseQuota("1.5KB"));
        assertEquals(10L * 1024 * 1024 * 1024, GarbageCollector.parseQuota(" 10 gb "));
    }

    @Test
    void treatsAbsentOrZeroQuotaAsUnlimited() {
        assertEquals(-1, GarbageCollector.parseQuota(null));
        assertEquals(-1, GarbageCollector.parseQuota(""));
        assertEquals(-1, GarbageCollector.parseQuota("0"));
        assertEquals(-1, GarbageCollector.parseQuota("nonsense"));
    }

    @Test
    void prunesOldSnapshotBuildsKeepingTheNewest() throws IOException {
        Path repositories = root.resolve("repositories");
        Path versionDirectory = repositories.resolve("snapshots/com/example/demo/1.0.0-SNAPSHOT");
        Files.createDirectories(versionDirectory);

        for (int build = 1; build <= 5; build++) {
            String stamp = "2026080%d.120000".formatted(build);
            Files.writeString(versionDirectory.resolve("demo-1.0.0-" + stamp + "-" + build + ".jar"), "jar");
            Files.writeString(versionDirectory.resolve("demo-1.0.0-" + stamp + "-" + build + ".jar.sha1"), "sum");
            Files.writeString(versionDirectory.resolve("demo-1.0.0-" + stamp + "-" + build + ".pom"), "pom");
        }

        GarbageCollector collector = new GarbageCollector(
                new RepositoryService(root), new MetadataService());

        int deleted = collector.pruneSnapshotVersion(versionDirectory, "demo", 2);

        // Three builds removed, each contributing a jar, its checksum and a pom.
        assertEquals(9, deleted);

        List<String> remaining = listNames(versionDirectory);
        assertTrue(remaining.contains("demo-1.0.0-20260804.120000-4.jar"));
        assertTrue(remaining.contains("demo-1.0.0-20260805.120000-5.jar"));
        assertFalse(remaining.contains("demo-1.0.0-20260801.120000-1.jar"));
        assertFalse(remaining.contains("demo-1.0.0-20260803.120000-3.jar"));
    }

    @Test
    void metadataIsRewrittenAfterPruning() throws IOException {
        Path repositories = root.resolve("repositories");
        Path versionDirectory = repositories.resolve("snapshots/com/example/demo/1.0.0-SNAPSHOT");
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve("demo-1.0.0-20260801.120000-1.jar"), "a");
        Files.writeString(versionDirectory.resolve("demo-1.0.0-20260802.120000-2.jar"), "b");

        GarbageCollector collector = new GarbageCollector(
                new RepositoryService(root), new MetadataService());
        collector.pruneSnapshotVersion(versionDirectory, "demo", 1);

        String xml = Files.readString(versionDirectory.resolve("maven-metadata.xml"));
        assertTrue(xml.contains("<buildNumber>2</buildNumber>"));
        assertFalse(xml.contains("20260801.120000"), "metadata must not point at a deleted build");
    }

    @Test
    void keepsEverythingWhenPreserveIsZero() throws IOException {
        Path versionDirectory = root.resolve("repositories/snapshots/com/example/demo/1.0.0-SNAPSHOT");
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve("demo-1.0.0-20260801.120000-1.jar"), "a");

        GarbageCollector collector = new GarbageCollector(
                new RepositoryService(root), new MetadataService());

        assertEquals(0, collector.pruneSnapshotVersion(versionDirectory, "demo", 0));
        assertEquals(1, listNames(versionDirectory).size());
    }

    @Test
    void removesEmptyDirectoriesUpToTheRepositoryRoot() throws IOException {
        Path repositoryRoot = root.resolve("repositories/releases");
        Path deep = repositoryRoot.resolve("com/example/demo/1.0.0");
        Files.createDirectories(deep);

        GarbageCollector collector = new GarbageCollector(
                new RepositoryService(root), new MetadataService());
        collector.pruneEmptyDirectories(deep, repositoryRoot);

        assertFalse(Files.exists(deep));
        assertTrue(Files.exists(repositoryRoot), "the repository root itself must survive");
    }

    @Test
    void stopsAtTheFirstNonEmptyDirectory() throws IOException {
        Path repositoryRoot = root.resolve("repositories/releases");
        Path deep = repositoryRoot.resolve("com/example/demo/1.0.0");
        Files.createDirectories(deep);
        Files.writeString(repositoryRoot.resolve("com/example/keep.txt"), "keep");

        GarbageCollector collector = new GarbageCollector(
                new RepositoryService(root), new MetadataService());
        collector.pruneEmptyDirectories(deep, repositoryRoot);

        assertFalse(Files.exists(deep));
        assertTrue(Files.exists(repositoryRoot.resolve("com/example")));
    }

    private List<String> listNames(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.map(path -> path.getFileName().toString()).toList();
        }
    }
}
