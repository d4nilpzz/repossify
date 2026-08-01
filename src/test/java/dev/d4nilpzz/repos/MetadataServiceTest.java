package dev.d4nilpzz.repos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataServiceTest {

    @TempDir
    Path root;

    private MetadataService service;
    private Path artifactDirectory;

    @BeforeEach
    void setUp() throws IOException {
        service = new MetadataService();
        artifactDirectory = root.resolve("releases/com/example/demo");
        Files.createDirectories(artifactDirectory);
    }

    private void file(String relative) throws IOException {
        Path path = artifactDirectory.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "x");
    }

    /* ===================== version discovery ===================== */

    @Test
    void discoversOnlyDirectoriesHoldingArtifactFiles() throws IOException {
        file("1.0.0/demo-1.0.0.jar");
        file("1.1.0/demo-1.1.0.jar");
        Files.createDirectories(artifactDirectory.resolve("nested-artifact/2.0.0"));
        file("nested-artifact/2.0.0/nested-artifact-2.0.0.jar");

        List<String> versions = service.discoverVersions(artifactDirectory, "demo");

        // "nested-artifact" holds no demo-* file, so it is a groupId/artifactId directory
        // rather than a version. Listing every subdirectory would have included it.
        assertEquals(List.of("1.0.0", "1.1.0"), versions);
    }

    @Test
    void ignoresDirectoriesWithOnlyChecksums() throws IOException {
        file("1.0.0/demo-1.0.0.jar");
        file("9.9.9/demo-9.9.9.jar.sha1");

        assertEquals(List.of("1.0.0"), service.discoverVersions(artifactDirectory, "demo"));
    }

    @Test
    void versionsComeBackInMavenOrder() throws IOException {
        file("1.9.0/demo-1.9.0.jar");
        file("1.10.0/demo-1.10.0.jar");
        file("1.2.0/demo-1.2.0.jar");

        assertEquals(List.of("1.2.0", "1.9.0", "1.10.0"),
                service.discoverVersions(artifactDirectory, "demo"));
    }

    /* ===================== artifact metadata ===================== */

    @Test
    void writesArtifactMetadataWithLatestAndRelease() throws IOException {
        file("1.0.0/demo-1.0.0.jar");
        file("2.0.0/demo-2.0.0.jar");
        file("2.1.0-SNAPSHOT/demo-2.1.0-SNAPSHOT.jar");

        assertTrue(service.writeArtifactMetadata(root, artifactDirectory));

        String xml = Files.readString(artifactDirectory.resolve("maven-metadata.xml"));
        assertTrue(xml.contains("<groupId>com.example</groupId>"));
        assertTrue(xml.contains("<artifactId>demo</artifactId>"));
        assertTrue(xml.contains("<release>2.0.0</release>"), "release must skip snapshots");
        assertTrue(xml.contains("<latest>2.1.0-SNAPSHOT</latest>"));
        assertTrue(xml.contains("<version>1.0.0</version>"));
    }

    @Test
    void omitsReleaseWhenOnlySnapshotsExist() throws IOException {
        file("1.0.0-SNAPSHOT/demo-1.0.0-SNAPSHOT.jar");

        service.writeArtifactMetadata(root, artifactDirectory);
        String xml = Files.readString(artifactDirectory.resolve("maven-metadata.xml"));

        assertFalse(xml.contains("<release>"), "an empty <release> confuses range resolution");
        assertTrue(xml.contains("<latest>1.0.0-SNAPSHOT</latest>"));
    }

    @Test
    void ensureDoesNotOverwriteClientManagedMetadata() throws IOException {
        file("1.0.0/demo-1.0.0.jar");
        Path metadata = artifactDirectory.resolve("maven-metadata.xml");
        Files.writeString(metadata, "<metadata>client owned</metadata>");

        assertFalse(service.ensureArtifactMetadata(root, artifactDirectory));
        assertEquals("<metadata>client owned</metadata>", Files.readString(metadata));
    }

    @Test
    void ensureWritesMetadataWhenAbsent() throws IOException {
        file("1.0.0/demo-1.0.0.jar");

        assertTrue(service.ensureArtifactMetadata(root, artifactDirectory));
        assertTrue(Files.exists(artifactDirectory.resolve("maven-metadata.xml")));
        assertTrue(Files.exists(artifactDirectory.resolve("maven-metadata.xml.sha1")));
    }

    /* ===================== snapshot metadata ===================== */

    @Test
    void snapshotMetadataPointsAtFilesThatExist() throws IOException {
        Path versionDirectory = artifactDirectory.resolve("1.0.0-SNAPSHOT");
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve("demo-1.0.0-20260801.101500-1.jar"), "old");
        Files.writeString(versionDirectory.resolve("demo-1.0.0-20260801.120000-2.jar"), "new");
        Files.writeString(versionDirectory.resolve("demo-1.0.0-20260801.120000-2.pom"), "pom");

        assertTrue(service.writeSnapshotMetadata(root, versionDirectory));
        String xml = Files.readString(versionDirectory.resolve("maven-metadata.xml"));

        // The generated timestamp used to be "now", advertising a build number for files
        // that were never uploaded, so clients resolved a 404.
        assertTrue(xml.contains("<timestamp>20260801.120000</timestamp>"));
        assertTrue(xml.contains("<buildNumber>2</buildNumber>"));
        assertTrue(xml.contains("<value>1.0.0-20260801.120000-2</value>"));
        assertFalse(xml.contains("20260801.101500"), "superseded build must not be advertised");
    }

    @Test
    void snapshotMetadataListsEachClassifierSeparately() throws IOException {
        Path versionDirectory = artifactDirectory.resolve("1.0.0-SNAPSHOT");
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve("demo-1.0.0-20260801.120000-1.jar"), "a");
        Files.writeString(versionDirectory.resolve("demo-1.0.0-20260801.120000-1-sources.jar"), "b");
        Files.writeString(versionDirectory.resolve("demo-1.0.0-20260801.120000-1.pom"), "c");

        service.writeSnapshotMetadata(root, versionDirectory);
        String xml = Files.readString(versionDirectory.resolve("maven-metadata.xml"));

        assertTrue(xml.contains("<classifier>sources</classifier>"));
        assertEquals(3, xml.split("<snapshotVersion>", -1).length - 1);
    }

    @Test
    void nonUniqueSnapshotGetsLocalCopyMetadata() throws IOException {
        Path versionDirectory = artifactDirectory.resolve("1.0.0-SNAPSHOT");
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve("demo-1.0.0-SNAPSHOT.jar"), "a");

        service.writeSnapshotMetadata(root, versionDirectory);
        String xml = Files.readString(versionDirectory.resolve("maven-metadata.xml"));

        assertTrue(xml.contains("<localCopy>true</localCopy>"));
    }

    @Test
    void snapshotMetadataIsSkippedForReleaseDirectories() throws IOException {
        Path versionDirectory = artifactDirectory.resolve("1.0.0");
        Files.createDirectories(versionDirectory);
        assertFalse(service.writeSnapshotMetadata(root, versionDirectory));
    }

    /* ===================== repair ===================== */

    @Test
    void repairRebuildsMetadataAcrossARepository() throws IOException {
        file("1.0.0/demo-1.0.0.jar");
        Path other = root.resolve("releases/com/example/other/2.0.0");
        Files.createDirectories(other);
        Files.writeString(other.resolve("other-2.0.0.jar"), "x");

        int written = service.repairRepository(root, root.resolve("releases"));

        assertEquals(2, written);
        assertTrue(Files.exists(artifactDirectory.resolve("maven-metadata.xml")));
        assertTrue(Files.exists(root.resolve("releases/com/example/other/maven-metadata.xml")));
    }

    @Test
    void checksumDetection() {
        assertTrue(MetadataService.isChecksum("demo-1.0.0.jar.sha1"));
        assertTrue(MetadataService.isChecksum("demo-1.0.0.jar.md5"));
        assertTrue(MetadataService.isChecksum("demo-1.0.0.jar.asc"));
        assertFalse(MetadataService.isChecksum("demo-1.0.0.jar"));
        assertFalse(MetadataService.isChecksum("demo-1.0.0.pom"));
    }
}
