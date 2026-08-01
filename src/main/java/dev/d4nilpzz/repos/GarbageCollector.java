package dev.d4nilpzz.repos;

import dev.d4nilpzz.http.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps repositories from growing without bound.
 * <p>
 * Snapshot pruning is the important half: a project deploying on every commit accumulates a
 * timestamped build per push, and none of them are ever collected on their own.
 */
public class GarbageCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(GarbageCollector.class);
    private static final Pattern QUOTA = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*([KMGT]?B?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final RepositoryService repositoryService;
    private final MetadataService metadataService;

    public GarbageCollector(RepositoryService repositoryService, MetadataService metadataService) {
        this.repositoryService = repositoryService;
        this.metadataService = metadataService;
    }

    /* ===================== quota ===================== */

    /**
     * Parses a configured quota such as {@code "10GB"}.
     *
     * @return the cap in bytes, or {@code -1} when unlimited
     */
    public static long parseQuota(String raw) {
        if (raw == null || raw.isBlank()) return -1;

        Matcher matcher = QUOTA.matcher(raw);
        if (!matcher.matches()) return -1;

        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toUpperCase(Locale.ROOT);

        long multiplier = switch (unit.isEmpty() ? "B" : unit.substring(0, 1)) {
            case "K" -> 1024L;
            case "M" -> 1024L * 1024;
            case "G" -> 1024L * 1024 * 1024;
            case "T" -> 1024L * 1024 * 1024 * 1024;
            default -> 1L;
        };

        long bytes = (long) (value * multiplier);
        return bytes <= 0 ? -1 : bytes;
    }

    /** Whether {@code incomingBytes} still fits inside the repository's configured quota. */
    public boolean hasSpaceFor(RepositoryData.Repository repository, long incomingBytes) {
        long quota = parseQuota(repository.storageQuota);
        if (quota < 0) return true;
        return repositoryService.sizeOf(repository.name) + incomingBytes <= quota;
    }

    /* ===================== snapshot pruning ===================== */

    /**
     * Deletes old timestamped builds in one snapshot version directory, keeping the
     * {@code preserve} most recent. Metadata is rewritten afterwards so it never advertises
     * a build that was just removed.
     *
     * @return number of files deleted
     */
    public int pruneSnapshotVersion(Path versionDirectory, String artifactId, int preserve) throws IOException {
        if (preserve <= 0 || !Files.isDirectory(versionDirectory)) return 0;

        String version = versionDirectory.getFileName().toString();
        if (!version.endsWith("-SNAPSHOT")) return 0;
        String baseVersion = version.substring(0, version.length() - "-SNAPSHOT".length());

        // Group every file by the build it belongs to, so an artifact and its sources,
        // javadoc and checksums are always removed together.
        Pattern pattern = Pattern.compile("^"
                + Pattern.quote(artifactId + "-" + baseVersion)
                + "-(\\d{8}\\.\\d{6})-(\\d+)(?:[-.].*)?$");

        Map<String, List<Path>> byBuild = new TreeMap<>();
        try (Stream<Path> stream = Files.list(versionDirectory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                Matcher matcher = pattern.matcher(file.getFileName().toString());
                if (!matcher.matches()) continue;
                // Sorting key: timestamp then zero-padded build number.
                String key = matcher.group(1) + "-" + String.format("%09d", Integer.parseInt(matcher.group(2)));
                byBuild.computeIfAbsent(key, ignored -> new ArrayList<>()).add(file);
            }
        }

        if (byBuild.size() <= preserve) return 0;

        List<String> ordered = new ArrayList<>(byBuild.keySet());
        List<String> doomed = ordered.subList(0, ordered.size() - preserve);

        int deleted = 0;
        for (String key : doomed) {
            for (Path file : byBuild.get(key)) {
                try {
                    Files.deleteIfExists(file);
                    deleted++;
                } catch (IOException e) {
                    LOGGER.warn("Cannot delete stale snapshot {}: {}", file, e.getMessage());
                }
            }
        }

        if (deleted > 0) {
            LOGGER.info("Pruned {} stale snapshot files in {}", deleted, versionDirectory);
            metadataService.writeSnapshotMetadata(repositoryService.root(), versionDirectory);
        }
        return deleted;
    }

    /**
     * Prunes every snapshot version in a repository according to its
     * {@code preserveSnapshots} setting.
     *
     * @return number of files deleted
     */
    public int pruneRepository(RepositoryData.Repository repository) throws IOException {
        if (repository.preserveSnapshots <= 0) return 0;

        Path base = PathSafety.resolveChild(repositoryService.root(), repository.name);
        if (base == null || !Files.isDirectory(base)) return 0;

        int deleted = 0;
        try (Stream<Path> stream = Files.walk(base)) {
            List<Path> snapshotDirs = stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith("-SNAPSHOT"))
                    .toList();

            for (Path directory : snapshotDirs) {
                Path artifactDirectory = directory.getParent();
                if (artifactDirectory == null) continue;
                deleted += pruneSnapshotVersion(directory,
                        artifactDirectory.getFileName().toString(), repository.preserveSnapshots);
            }
        }

        if (deleted > 0) repositoryService.invalidate(repository.name);
        return deleted;
    }

    /** Runs pruning across every configured repository. */
    public int pruneAll() {
        int deleted = 0;
        for (RepositoryData.Repository repository : repositoryService.repositories()) {
            try {
                deleted += pruneRepository(repository);
            } catch (IOException e) {
                LOGGER.warn("Cannot prune {}: {}", repository.name, e.getMessage());
            }
        }
        return deleted;
    }

    /**
     * Removes directories left empty after a delete, walking upwards but never past the
     * repository root.
     */
    public void pruneEmptyDirectories(Path start, Path stopAt) {
        Path current = start;
        while (current != null && !current.equals(stopAt) && current.startsWith(stopAt)) {
            try {
                if (!Files.isDirectory(current)) break;
                try (Stream<Path> entries = Files.list(current)) {
                    if (entries.findAny().isPresent()) break;
                }
                Files.delete(current);
            } catch (IOException e) {
                break;
            }
            current = current.getParent();
        }
    }
}
