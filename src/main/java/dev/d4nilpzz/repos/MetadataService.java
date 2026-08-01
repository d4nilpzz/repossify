package dev.d4nilpzz.repos;

import dev.d4nilpzz.utils.ChecksumUtils;
import dev.d4nilpzz.utils.MavenUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds {@code maven-metadata.xml} from what is actually on disk.
 * <p>
 * <b>Ownership.</b> A Maven or Gradle client maintains this file itself: it downloads the
 * current metadata, merges the new version in, and uploads the result. Regenerating it on
 * every deploy therefore fights the client and produces metadata that disagrees with the
 * files present — most visibly for snapshots, where the generated timestamp and build number
 * pointed at filenames that were never uploaded. Generation now only happens when the file
 * is <em>absent</em> (a plain {@code curl} or dashboard upload), or when a repair is
 * explicitly requested.
 */
public class MetadataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataService.class);
    public static final String METADATA_FILE = "maven-metadata.xml";

    private static final DateTimeFormatter LAST_UPDATED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter SNAPSHOT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss");

    private static final Set<String> NON_ARTIFACT_EXTENSIONS =
            Set.of("md5", "sha1", "sha256", "sha512", "asc");

    /* ===================== entry points ===================== */

    /**
     * Writes artifact-level metadata only when it does not already exist, leaving a
     * client-managed file untouched.
     *
     * @return true when a file was written
     */
    public boolean ensureArtifactMetadata(Path repositoriesRoot, Path artifactDirectory) throws IOException {
        Path target = artifactDirectory.resolve(METADATA_FILE);
        if (Files.exists(target)) return false;
        return writeArtifactMetadata(repositoriesRoot, artifactDirectory);
    }

    /** Regenerates artifact-level metadata unconditionally. Used by the repair command. */
    public boolean writeArtifactMetadata(Path repositoriesRoot, Path artifactDirectory) throws IOException {
        MavenCoordinates coordinates = MavenCoordinates.ofArtifactDirectory(repositoriesRoot, artifactDirectory);
        if (coordinates == null) return false;

        List<String> versions = discoverVersions(artifactDirectory, coordinates.artifactId());
        if (versions.isEmpty()) return false;

        String xml = renderArtifactMetadata(coordinates.groupId(), coordinates.artifactId(), versions);
        write(artifactDirectory.resolve(METADATA_FILE), xml);
        return true;
    }

    /** Writes version-level snapshot metadata only when absent. */
    public boolean ensureSnapshotMetadata(Path repositoriesRoot, Path versionDirectory) throws IOException {
        Path target = versionDirectory.resolve(METADATA_FILE);
        if (Files.exists(target)) return false;
        return writeSnapshotMetadata(repositoriesRoot, versionDirectory);
    }

    /**
     * Regenerates version-level snapshot metadata from the timestamped files present in
     * {@code versionDirectory}, so the advertised build always resolves to a real file.
     */
    public boolean writeSnapshotMetadata(Path repositoriesRoot, Path versionDirectory) throws IOException {
        String version = versionDirectory.getFileName().toString();
        if (!version.endsWith("-SNAPSHOT")) return false;

        Path artifactDirectory = versionDirectory.getParent();
        MavenCoordinates coordinates = MavenCoordinates.ofArtifactDirectory(repositoriesRoot, artifactDirectory);
        if (coordinates == null) return false;

        String baseVersion = version.substring(0, version.length() - "-SNAPSHOT".length());
        List<SnapshotBuild> builds = discoverSnapshotBuilds(versionDirectory, coordinates.artifactId(), baseVersion);

        String xml = builds.isEmpty()
                ? renderLocalCopySnapshot(coordinates.groupId(), coordinates.artifactId(), version)
                : renderSnapshotMetadata(coordinates.groupId(), coordinates.artifactId(), version, builds);

        write(versionDirectory.resolve(METADATA_FILE), xml);
        return true;
    }

    /**
     * Rebuilds every metadata file under a repository. Exposed as a console command and REST
     * endpoint so a repository imported by copying files can be made resolvable.
     *
     * @return number of metadata files written
     */
    public int repairRepository(Path repositoriesRoot, Path repositoryDirectory) throws IOException {
        int written = 0;

        try (Stream<Path> stream = Files.walk(repositoryDirectory)) {
            List<Path> directories = stream.filter(Files::isDirectory).toList();

            for (Path directory : directories) {
                String name = directory.getFileName().toString();

                if (name.endsWith("-SNAPSHOT")) {
                    if (writeSnapshotMetadata(repositoriesRoot, directory)) written++;
                    continue;
                }

                MavenCoordinates coordinates = MavenCoordinates.ofArtifactDirectory(repositoriesRoot, directory);
                if (coordinates == null) continue;
                if (discoverVersions(directory, coordinates.artifactId()).isEmpty()) continue;
                if (writeArtifactMetadata(repositoriesRoot, directory)) written++;
            }
        }
        return written;
    }

    /* ===================== disk discovery ===================== */

    /**
     * Version directories under an artifact. A child counts as a version only when it holds
     * a file named after the artifact, which keeps nested groupId directories and stray
     * folders out of the version list.
     */
    public List<String> discoverVersions(Path artifactDirectory, String artifactId) throws IOException {
        if (!Files.isDirectory(artifactDirectory)) return List.of();

        List<String> versions = new ArrayList<>();
        try (Stream<Path> stream = Files.list(artifactDirectory)) {
            for (Path candidate : stream.filter(Files::isDirectory).toList()) {
                if (holdsArtifactFiles(candidate, artifactId)) {
                    versions.add(candidate.getFileName().toString());
                }
            }
        }
        versions.sort(MavenUtils::compareVersions);
        return versions;
    }

    private boolean holdsArtifactFiles(Path directory, String artifactId) throws IOException {
        String prefix = artifactId + "-";
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile).anyMatch(file -> {
                String name = file.getFileName().toString();
                return name.startsWith(prefix) && !isChecksum(name);
            });
        }
    }

    public static boolean isChecksum(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && NON_ARTIFACT_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase());
    }

    /**
     * Timestamped builds present in a snapshot version directory, e.g.
     * {@code demo-1.0-20260801.101500-3-sources.jar}.
     */
    List<SnapshotBuild> discoverSnapshotBuilds(Path versionDirectory, String artifactId, String baseVersion)
            throws IOException {
        if (!Files.isDirectory(versionDirectory)) return List.of();

        Pattern pattern = Pattern.compile("^"
                + Pattern.quote(artifactId + "-" + baseVersion)
                + "-(\\d{8}\\.\\d{6})-(\\d+)"        // timestamp and build number
                + "(?:-([^./]+))?"                    // optional classifier
                + "\\.([A-Za-z0-9._-]+)$");           // extension

        List<SnapshotBuild> builds = new ArrayList<>();
        try (Stream<Path> stream = Files.list(versionDirectory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (isChecksum(name)) continue;

                Matcher matcher = pattern.matcher(name);
                if (!matcher.matches()) continue;

                builds.add(new SnapshotBuild(
                        matcher.group(1),
                        Integer.parseInt(matcher.group(2)),
                        matcher.group(3),
                        matcher.group(4),
                        baseVersion + "-" + matcher.group(1) + "-" + matcher.group(2)
                ));
            }
        }
        return builds;
    }

    /* ===================== rendering ===================== */

    String renderArtifactMetadata(String groupId, String artifactId, List<String> versions) {
        String latest = versions.isEmpty() ? "" : versions.getLast();
        String release = versions.stream()
                .filter(version -> !version.endsWith("-SNAPSHOT"))
                .reduce("", (first, second) -> second);

        StringBuilder versionsXml = new StringBuilder();
        for (String version : versions) {
            versionsXml.append("      <version>").append(escape(version)).append("</version>\n");
        }

        StringBuilder builder = new StringBuilder();
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<metadata>\n")
                .append("  <groupId>").append(escape(groupId)).append("</groupId>\n")
                .append("  <artifactId>").append(escape(artifactId)).append("</artifactId>\n")
                .append("  <versioning>\n");

        // An empty <release/> is what Maven reads as "no release published yet"; emitting the
        // element with empty content confuses version-range resolution, so it is omitted.
        if (!release.isEmpty()) {
            builder.append("    <release>").append(escape(release)).append("</release>\n");
        }
        builder.append("    <latest>").append(escape(latest)).append("</latest>\n")
                .append("    <versions>\n")
                .append(versionsXml)
                .append("    </versions>\n")
                .append("    <lastUpdated>").append(lastUpdated()).append("</lastUpdated>\n")
                .append("  </versioning>\n")
                .append("</metadata>\n");

        return builder.toString();
    }

    String renderSnapshotMetadata(String groupId, String artifactId, String version, List<SnapshotBuild> builds) {
        SnapshotBuild newest = builds.stream()
                .max(Comparator.comparing(SnapshotBuild::timestamp).thenComparingInt(SnapshotBuild::buildNumber))
                .orElseThrow();

        // One entry per classifier/extension pair, each pointing at its newest build.
        Map<String, SnapshotBuild> newestPerKind = new LinkedHashMap<>();
        for (SnapshotBuild build : builds) {
            String key = (build.classifier() == null ? "" : build.classifier()) + ":" + build.extension();
            SnapshotBuild current = newestPerKind.get(key);
            if (current == null
                    || build.timestamp().compareTo(current.timestamp()) > 0
                    || (build.timestamp().equals(current.timestamp()) && build.buildNumber() > current.buildNumber())) {
                newestPerKind.put(key, build);
            }
        }

        String updated = lastUpdated();
        StringBuilder entries = new StringBuilder();
        for (SnapshotBuild build : newestPerKind.values()) {
            entries.append("      <snapshotVersion>\n");
            if (build.classifier() != null) {
                entries.append("        <classifier>").append(escape(build.classifier())).append("</classifier>\n");
            }
            entries.append("        <extension>").append(escape(build.extension())).append("</extension>\n")
                    .append("        <value>").append(escape(build.value())).append("</value>\n")
                    .append("        <updated>").append(updated).append("</updated>\n")
                    .append("      </snapshotVersion>\n");
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<metadata>\n"
                + "  <groupId>" + escape(groupId) + "</groupId>\n"
                + "  <artifactId>" + escape(artifactId) + "</artifactId>\n"
                + "  <version>" + escape(version) + "</version>\n"
                + "  <versioning>\n"
                + "    <snapshot>\n"
                + "      <timestamp>" + escape(newest.timestamp()) + "</timestamp>\n"
                + "      <buildNumber>" + newest.buildNumber() + "</buildNumber>\n"
                + "    </snapshot>\n"
                + "    <lastUpdated>" + updated + "</lastUpdated>\n"
                + "    <snapshotVersions>\n"
                + entries
                + "    </snapshotVersions>\n"
                + "  </versioning>\n"
                + "</metadata>\n";
    }

    /**
     * Metadata for a non-unique snapshot, where files keep the literal {@code -SNAPSHOT}
     * name. {@code localCopy} tells the client to request that name directly instead of
     * looking for a timestamped build.
     */
    String renderLocalCopySnapshot(String groupId, String artifactId, String version) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<metadata>\n"
                + "  <groupId>" + escape(groupId) + "</groupId>\n"
                + "  <artifactId>" + escape(artifactId) + "</artifactId>\n"
                + "  <version>" + escape(version) + "</version>\n"
                + "  <versioning>\n"
                + "    <snapshot>\n"
                + "      <localCopy>true</localCopy>\n"
                + "    </snapshot>\n"
                + "    <lastUpdated>" + lastUpdated() + "</lastUpdated>\n"
                + "  </versioning>\n"
                + "</metadata>\n";
    }

    /** Timestamp Maven uses to name a new unique-snapshot build. */
    public static String newSnapshotTimestamp() {
        return LocalDateTime.now(ZoneOffset.UTC).format(SNAPSHOT_STAMP);
    }

    private static String lastUpdated() {
        return LocalDateTime.now(ZoneOffset.UTC).format(LAST_UPDATED);
    }

    private void write(Path target, String xml) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, xml, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ChecksumUtils.writeChecksums(target);
        LOGGER.debug("Wrote metadata {}", target);
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** A single timestamped snapshot build found on disk. */
    public record SnapshotBuild(String timestamp, int buildNumber, String classifier,
                                String extension, String value) {
    }
}
